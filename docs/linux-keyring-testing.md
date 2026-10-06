# Linux secure storage: implementation and KDE acceptance

Phase 2 implements Linux secret protection. KDE acceptance is still pending;
this is not a declaration that the Linux port is complete. Output-mode Music Sync
now has an experimental [PipeWire backend](linux-pipewire-testing.md);
application-only capture is also implemented experimentally; see
[application capture testing](linux-application-capture-testing.md).

## Implemented behavior

Windows still uses the original current-user DPAPI protectors. Linux uses libsecret
through JNA and a compatible Secret Service provider on the desktop session bus.
The default persistent collection stores a random AES-256 encryption key for each
protected payload. Keys are tagged by HapticScape, purpose, format, and random ID.
Unlock-key and Discord payloads cannot be used interchangeably.

Local JSON files contain public metadata and Base64 authenticated ciphertext.
The binary envelope authenticates its version, purpose, key ID, and random GCM
nonce. No encryption keys or plaintext secrets are written to local temporary
files, command arguments, or logs. Decryption retrieves a key each time; wrapping
keys are not cached in HapticScape across wallet locks. Discord's active device
credential remains in application memory while linked, as on Windows.

Forgetting a key or unlinking Discord removes its local protected payload. The
wrapping key remains in the wallet so existing backups can still be opened.
Replacing a payload creates an immutable wrapping key rather than overwriting
one another profile or backup might require. Failed local persistence may leave
an unused wrapping key. Do not delete HapticScape encryption keys from the wallet
unless you intend to lose access to the corresponding files and backups.

Metadata and envelope validation run without prompting. Existing Discord secrets
open on a background worker; **Retry saved link** appears if wallet access fails.
Copying a saved unlock key and accepted-key saves also wait in the background.
Accepted keys are never saved before participant acceptance. A cancelled lock
cannot be resurrected by a late wallet response. Save failure reports that the key
was not saved; it does not retain plaintext indefinitely or downgrade protection.

Wallet calls request cancellation after 30 seconds using GIO. Locked wallets,
cancelled prompts, unavailable providers, and missing wrapping keys preserve
existing data. Copy/recovery can be retried. Authentication failures and foreign
payloads block replacement rather than silently resetting the stores. Files
skipped because a library is unavailable are still inspected before later writes.

Linux cannot decrypt Windows DPAPI data. Windows cannot open Linux envelopes.
There is no automatic cross-platform migration. Use a separate Linux profile;
retain original files and the original platform's wallet/account for recovery.

## Automated checks

Run:

```bash
./gradlew test verifyLinuxKeyringBindings --offline
```

The ordinary suite tests encryption round trips, purpose separation, tampering,
missing keys, cancelled wallet access, preserved foreign/corrupt files, failed
persistence, metadata edits during decryption, cancellation during saving, and
Remote Play responsiveness while a simulated wallet prompt is open.

The cancellation test uses real GLib cancellation objects with a simulated
libsecret operation. It never contacts a desktop wallet. It skips on non-Linux
systems or when GIO is unavailable.

`verifyLinuxKeyringBindings` is an explicit Linux check that requires installed
native libraries. It gives the test JVM a nonexistent session-bus socket and
exercises libsecret lookup/store failure handling plus the Swing-thread guard.
It does not read or write the user's real wallet. It is skipped on non-Linux
systems and is not a substitute for a successful KDE save/load test.

## KDE acceptance to perform

Prerequisites: Java 11 or newer, the libsecret, GLib/GIO native libraries, a desktop
session bus, and a compatible persistent Secret Service provider. Identify which
provider owns `org.freedesktop.secrets`; do not assume KDE's wallet configuration
exposes that interface or that its default collection has a particular lock policy.
Install prerequisites before launch. If library availability changes at runtime,
restart HapticScape to refresh initial feature state.

Build and launch a dedicated test profile, using a different gameplay port if
another instance is already running:

```bash
./gradlew standaloneJar --offline
java -jar build/libs/hapticscape-desktop.jar --profile linux-keyring-test --gameplay-port 41715
```

Unless `LOCALAPPDATA` is explicitly set, its files are under
`~/.hapticscape/profiles/linux-keyring-test/`. Use real Remote Play acceptance and
Discord linking for the corresponding features; clicking buttons alone is not
proof of secure persistence. Record provider, library/desktop versions, and the
result of each case. Do not put real unlock keys or credentials in test reports.

| Case | Required evidence |
| --- | --- |
| Accepted unlock key | Key saves only after acceptance, copies correctly, survives app restart and logout/login |
| Profile replacement and Forget | Correct key/profile remains after replacement; forgotten local entry is removed |
| Discord linking and restart | Device link restores and connects after restart; unlink removes local credential file |
| Wallet inspection | HapticScape purpose/key-ID entries exist in the persistent collection; local JSON/temp files contain encrypted payloads, not the original secret or wrapping key |
| Lock and cancelled unlock | Copy fails or prompts according to provider policy; UI stays responsive; cancellation preserves files; retry succeeds after unlocking |
| Startup with locked/unavailable wallet | Existing Discord file is preserved; Retry saved link restores it when the provider becomes available |
| Timeout | An unanswered prompt requests cancellation after about 30 seconds; no indefinite UI or Remote Play stall; subsequent retry works |
| Missing wrapping key | On a disposable backed-up profile/wallet entry, missing key gives a recovery error without generating a replacement or changing the file |
| Lock cancellation during save | Cancelling while a wallet prompt is pending does not create the key after that prompt finishes |

Use copies of disposable test data for corruption, foreign-file, or missing-key
experiments. The automated suite already verifies these file-preservation paths.
Live wallet locking, unlock cancellation, persistence across login sessions, and
successful Discord restoration remain native acceptance requirements.

Windows acceptance must also verify actual DPAPI save/reopen and WASAPI behavior
on Windows. Linux tests instantiate Windows backend classes and test shared logic;
they do not invoke those Windows APIs.

Native API references: [libsecret lookup](https://gnome.pages.gitlab.gnome.org/libsecret/func.password_lookupv_sync.html),
[libsecret store](https://gnome.pages.gitlab.gnome.org/libsecret/func.password_storev_sync.html),
[GIO cancellation](https://docs.gtk.org/gio/method.Cancellable.cancel.html).
