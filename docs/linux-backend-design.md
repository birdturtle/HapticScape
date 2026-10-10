# Phase 1: Linux backend design

The routing descriptions below record the Phase 1 baseline. Phase 2 now wires
Linux secret protection; see [Linux keyring implementation and testing](linux-keyring-testing.md)
for current storage behavior and pending acceptance. Phase 3 now implements
output capture; see [PipeWire implementation and testing](linux-pipewire-testing.md)
for current audio behavior. The routing section below remains the Phase 1 record.
Phase 4 also implements [application-only PCM capture](linux-application-capture-testing.md).

## Routing and current capability

`DesktopPlatform` selects Windows, Linux, or unsupported without opening a native
library. The factories keep all existing Windows classes. Linux and unsupported
platforms receive fail-closed protectors and capture sources; their catalogs are
empty. Linux messages explicitly describe the backends as not implemented.
`MusicSyncService` now preserves a synchronous output-start failure message,
matching its existing application-start behavior.

This phase does not implement keyring storage or PipeWire capture. Platform tests
instantiate Windows classes but do not invoke DPAPI, COM, or WASAPI on Linux.

## Secret-store findings and decision

Both stores persist Base64 protector payloads alongside public metadata. They
write a sibling temporary file and replace the destination before publishing
updated in-memory state; atomic move falls back to ordinary replacement when
unsupported. Temporary files contain protected payloads. Plaintext byte buffers
are cleared, although Discord credentials also live as Java strings in memory.

Saved keys are decrypted on reveal, not on load. Loading validates metadata and
schema; a foreign payload could therefore remain undetected until reveal. Discord
credentials decrypt during construction; a failure makes the store unavailable
for the lifetime of that instance. Neither constructor retries loading when a
previously unavailable protector becomes available. Phase 2 must address retry
semantics explicitly, without allowing a skipped load to overwrite existing data.

Saved-key replacement and deletion modify only the file. Discord clear deletes
its file and temporary file. `UnlockKeyProtector` has no item deletion API.
Therefore, use **keyring-held encryption keys and AES-256-GCM file payloads**,
rather than a keyring secret for each record. Normal record deletion then requires
no remote keyring transaction and replacement cannot orphan a per-record secret.
Keep purpose keys while ciphertext may remain in any profile or backup; removing
them would destroy access. Document this distinction in the eventual user docs.

Use separate purpose keys for unlock keys and Discord, with a random key ID in
non-secret lookup attributes. Create a new ID/key only when protecting new data;
read existing payloads by their embedded ID and never create a replacement key
when retrieval fails. A store failure may leave an unused wrapping key, but must
never invalidate a previous payload. Concurrent writers must not replace an
existing key: random key IDs and immutable keys avoid the lookup/create race.
Profiles retain their existing separate files; keys are scoped to the desktop
user and purpose, as DPAPI is today. No automatic Windows-to-Linux migration.

Version the binary envelope with a Linux-specific magic, version, purpose, key ID,
96-bit random nonce, ciphertext, and GCM tag. Authenticate the header as additional
data. Strict bounds checks and rejection of unknown versions, foreign payloads,
and mismatched purposes precede keyring access. No plaintext or local-key fallback.
Phase 2 must also prevent changing a vault containing foreign payloads until its
format is identified; detecting failure only at reveal is insufficient.

Use **libsecret through the existing JNA dependency**, initially its non-varargs
`secret_password_lookupv_sync` and `secret_password_storev_sync` APIs plus GLib/GIO
for attributes, errors, cancellation, and freeing returned secret buffers. These
APIs distinguish missing secrets from errors. Use the persistent default collection,
not the session collection. Load libraries only on Linux and on demand. Native
calls need a cancellable deadline and must run outside the Swing event thread;
store/UI call sites currently assume synchronous DPAPI and need adjustment in
Phase 2. Availability checks must not create keys or prompt on every UI refresh.
Key material must not be cached indefinitely across wallet locking.

The Secret Service specification defines unlock prompts and treats attributes as
public metadata. Its availability does not prove a particular KDE provider or
collection's protection policy. KDE acceptance must identify the active provider,
verify persistence and locking, and exercise cancellation and missing-service
behavior. A wallet used exclusively through its legacy API may need a compatible
Secret Service provider; do not assume all KDE installations expose the service.

References: [Secret Service specification](https://specifications.freedesktop.org/secret-service/latest-single/),
[libsecret lookup](https://gnome.pages.gitlab.gnome.org/libsecret/func.password_lookupv_sync.html),
[libsecret store](https://gnome.pages.gitlab.gnome.org/libsecret/func.password_storev_sync.html).
The specification currently labels itself a draft; use established libsecret APIs
rather than assuming recently added protocol features are deployed.

## Audio lifecycle findings and decision

The shared factory already separates output and application sources. Music Sync
creates a source, starts it, accepts mono floats with rate and output-volume scale,
and closes it on switch, disable, or failure. Generation checks reject stale
callbacks. Capture startup must be asynchronous and close must be idempotent and
bounded. Avoid joining a callback worker while holding the Music Sync lock: that
worker may itself need the lock to report samples/errors.

Use **PipeWire helper processes** initially: `pw-dump` for JSON graph discovery,
`pw-cat --record --raw` for negotiated PCM, and `pw-link` for application links.
This avoids implementing PipeWire/SPA native structs and callbacks in Java and
adds no JVM dependencies. Pass argument arrays directly without a shell; drain
stderr separately, bound discovery output, and terminate/reap all owned processes
on failure or close. The installed helpers here report PipeWire 1.6.9. Minimum
supported versions remain to be established through capability tests in Phase 3.

For output mode, select sink monitor capture with `stream.capture.sink=true`.
Resolve the default *sink* from graph metadata: generic capture auto-selection
could otherwise select a microphone. Persist namespaced `node.name` identities;
use current serials/IDs only for live operations. Watch default changes and
reconnect the default source deliberately. A selected missing sink must report
unavailability rather than switch to default.

For application mode, create a capture node with automatic connection disabled
(`--target=0`) and explicitly connect only selected playback output ports to its
input ports, preserving the application's original playback links. Resolve
application ID first, then an unambiguous executable identity; avoid relying on
PIDs or display names across restarts. The shared application model lowercases
IDs, so encode case-sensitive Linux identities losslessly before persistence.
Group multiple matching streams deliberately; mixing and channel mapping require
signal tests. Links must be owned/removed with the capture node. `pw-link` links
linger by default, so process exit alone is not a cleanup strategy.

The documented commands establish the mechanism, not proof that all playback
streams expose usable ports under every session policy. Application isolation,
multiple-stream mixing, default following, reconnect, and volume/mute semantics
must be verified in later phases with known PCM signals. Do not claim volume
parity until checking whether captured PCM already includes sink/stream gain;
applying gain twice would change Music Sync response.

References: [pw-dump](https://docs.pipewire.org/page_man_pw-dump_1.html),
[pw-cat](https://docs.pipewire.org/page_man_pw-cat_1.html),
[pw-link](https://docs.pipewire.org/page_man_pw-link_1.html),
[stream properties](https://docs.pipewire.org/devel/group__pw__keys.html).

## Phase 1 verification and remaining acceptance

Automated routing tests cover all three platform branches, preserved Windows
backend types, unavailable protection in both directions, empty catalogs, capture
startup errors, and repeated cleanup. A Music Sync regression test checks that a
synchronous backend error reaches the snapshot and stops live output.

The existing Gradle suite covers store persistence, audio analysis/service behavior,
and Windows format/identity logic. Native Windows behavior remains untested here.
No live KDE secrets have been written and no audio stream has been captured in
Phase 1. Phase 2 requires KDE wallet acceptance; Phases 3–4 require source/PCM and
isolation acceptance as listed in `linux-port-plan.md`.
