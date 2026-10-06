# Linux keyring and PipeWire port

## Scope and starting point

This is a phased implementation plan, not a declaration of Linux support.
Work branch: `linux/keyring-pipewire`, based on `master` (this repository's
mainline). The pre-existing executable-bit change to `gradlew` belongs to the
user and must remain untouched and outside port commits.

Initial source audit:

| Area | Windows implementation | Current Linux behavior |
| --- | --- | --- |
| Saved unlock keys | Current-user DPAPI through `UnlockKeyProtector` | Factory selects DPAPI; unavailable |
| Discord device credential | Separate current-user DPAPI protector | Factory selects DPAPI; unavailable |
| Default/selected output audio | WASAPI loopback and endpoint catalog | Capture throws; catalog offers only the default placeholder |
| Application audio | WASAPI process-tree PCM loopback and session catalog | Capture throws; application catalog is empty |
| Audio UI/persistence | Shared capture interfaces and saved selection | Default label and several descriptions assume Windows |
| Data directory | `LOCALAPPDATA/HapticScape`, otherwise `~/.hapticscape` | Home-directory fallback exists |
| Startup/release integration | Windows startup service and Windows packaging | Separate work; not covered by keyring/audio parity |

The README describes application capture as a peak meter, but the active factory
uses `WasapiApplicationLoopbackCapture` and PCM. Linux must deliver actual PCM to
the shared analyzer. Clicks, UI interactions, and vibrations alone do not prove
either secure persistence or correct audio-source isolation.

## Reviewable implementation phases

1. **Baseline and backend design.** Run existing Gradle tests and standalone
   artifact checks. Inspect credential-store replacement/deletion and failure
   handling, audio lifecycle, and persisted identities in detail. Verify the
   Secret Service and PipeWire APIs against official documentation before
   selecting native bindings or helper processes. Establish explicit OS routing
   with unchanged Windows backends and useful unsupported-platform errors.

2. **Secure Linux persistence.** Implement the desktop Secret Service protocol
   backed by the user's keyring, including KDE's available provider. Prefer a
   keyring-held encryption key with authenticated encrypted local store payloads
   if it fits the existing protector lifecycle; decide after checking deletion,
   profiles, and recovery semantics. Separate unlock-key and Discord purposes.
   Handle missing service, locked keyring, cancelled unlock, missing key, corrupt
   ciphertext, and persistence failures without plaintext fallback. Keep secrets
   out of command arguments, logs, and temporary files. Version Linux payloads;
   reject foreign DPAPI blobs without overwriting them. Windows DPAPI data cannot
   simply be decrypted on Linux; any future migration needs an explicit flow.

3. **PipeWire output capture.** Implement graph discovery and default/selected
   sink monitor PCM capture. Use durable node identity rather than persisting
   transient graph IDs. Define default-following behavior, device disappearance,
   reconnect, format negotiation, channel conversion, and cleanup. Preserve
   selected-device failure visibility rather than silently capturing another
   output. Add bounded startup/shutdown and actionable dependency errors.

4. **PipeWire application-only capture.** Enumerate playback applications and
   resolve their live streams from durable application identity. Capture only the
   selected application's PCM without moving its playback or capturing the whole
   sink. Define grouping for multiple streams/processes, stream restart, app exit,
   permissions, and unavailable selections. Verify routing leaves the user's
   listening experience intact.

5. **Integration and acceptance.** Wire Linux backends into existing factories,
   remove misleading Windows-only UI text, document runtime dependencies and
   limitations, and run the full relevant Gradle suite plus artifact checks.
   Keep changes grouped by phase for review. Do not include the user's `gradlew`
   mode change in port commits. Windows native regression testing and KDE
   acceptance remain separate from Java tests on Linux.

## Automated verification

- Backend selection and unsupported-platform behavior without loading foreign
  native libraries.
- Protector/store round trips, purpose separation, malformed/foreign payloads,
  keyring errors, atomic replacement, and no plaintext fallback. Mocked keyring
  tests verify contracts, not actual KDE protection.
- Graph fixtures for outputs, defaults, application grouping, transient IDs,
  missing targets, and reconnect behavior.
- Known PCM signals for decoding/channel handling and application isolation;
  lifecycle tests for startup failure, cancellation, resource cleanup, and errors.
- Existing saved-key, Discord credential, Music Sync, and WASAPI tests; final
  `./gradlew test` (which also enforces standalone artifact boundaries).
- Identify native tests that skip on the current OS explicitly; a Linux Gradle
  pass does not prove Windows DPAPI/WASAPI still operate on Windows.

## User acceptance in KDE

- Save, reopen, use, replace, and delete unlock keys and Discord credentials;
  restart HapticScape and log out/in to verify persistence. Inspect local files
  for absence of plaintext secrets and verify the actual keyring provider.
- Exercise locked/unlocked keyring, cancelled unlock prompt, unavailable service,
  and missing key. Verify failures preserve existing data and never downgrade
  storage security.
- Play a known signal on the default output, change the default while capture is
  running, select another output, and unplug/reconnect it. Verify the captured
  signal/source, not merely resulting vibrations.
- Play distinguishable signals from two applications concurrently. Capture each
  independently and demonstrate exclusion of the other application and system
  notifications; test multiple streams, app restart, and routing/device changes.
- Verify playback remains audible, capture stops cleanly, repeated switching
  leaves no orphan capture processes/links, and suspend/resume recovers or reports
  an actionable failure.

Track each phase as implemented, automated checks passed, and native acceptance
pending/passed separately. Only claim the requested Linux features complete after
the required native acceptance evidence is available.

## Phase status

- Phase 1 implemented: explicit platform routing, fail-closed pending Linux
  backends, visible startup errors, and backend/lifecycle design documented in
  [linux-backend-design.md](linux-backend-design.md).
- Automated checks: `./gradlew test --offline` passed after the routing change,
  including standalone artifact verification. The main test task executed; the
  unchanged bridge tests were up to date from the fresh baseline run.
- Native acceptance: Windows DPAPI/WASAPI operation and KDE keyring/PipeWire
  behavior remain untested. Phase 1 makes no Linux capability claim.
- Phase 2 implementation: Linux libsecret/JNA protection, authenticated envelopes,
  preserved existing stores, background wallet operations, and Discord recovery UI.
  See [linux-keyring-testing.md](linux-keyring-testing.md) for behavior and acceptance.
- Phase 2 automated checks: `./gradlew test verifyLinuxKeyringBindings --offline`
  passed. All 508 main tests executed with no failures or skips; artifact checks
  and the isolated native bindings check passed. The unchanged bridge suite
  remained up to date from its 27-test baseline.
- Phase 2 native acceptance remains pending: successful KDE wallet persistence,
  locking/unlocking, cancelled prompts, and actual Discord restoration.
- Next implementation: Phase 3, PipeWire output capture. Phase 2 KDE acceptance
  can proceed separately before claiming secure-storage support verified.
