# RuneLite 1.13.3 launcher restoration

The owner explicitly approved accepting RuneLite's new `net.runelite:jagexauth` runtime dependency. Its entry is added to `approvedRuntimeModules`; checks for other unexpected dependencies remain enabled. This fixes the packaging gate without changing launcher authentication, account storage, character selection or process startup.

RuneLite remains pinned to production version 1.13.3. The helper is supplied transitively by RuneLite, not duplicated as a direct dependency. Its seven native resources must remain packaged at their original paths so RuneLite can resolve the appropriate executable.

## Provenance

- Client metadata: https://repo.runelite.net/net/runelite/client/1.13.3/client-1.13.3.pom
- Artifact: https://repo.runelite.net/net/runelite/jagexauth/0.3/jagexauth-0.3.jar
- SHA-256: `36048b7c23726906bd2cba4318ab56a4d9d330a7392ce5fd63910379d941ad18`
- Upstream integration: https://github.com/runelite/runelite/commit/fd4653571f0d2d01430c258d7ffe922d4d7fcbae
- Resources: Linux amd64/aarch64, Windows amd64/aarch64/x86, macOS x86_64/aarch64.

No license/notice is embedded in this artifact and its POM declares none. No license has been inferred or invented. Accepting this known upstream dependency is the owner's explicit project decision; the normal inventory still records missing embedded notices honestly.

## Verification

The clean Java 11 CI checks pass: 552 desktop tests and 28 bridge tests, standalone/bridge JAR validation, runtime dependency checks and license inventory generation. All seven helper resources in `lumbridge.jar` match the upstream artifact byte-for-byte; the manifest records RuneLite 1.13.3. The launcher UI's 12 tests pass.

The Linux test package builds successfully using an isolated Rust toolchain under `/tmp` and Java 11 on PATH. Native tests: 45 passed, two opt-in tests initially ignored; the real-archive opt-in test subsequently passed. The remaining opt-in public-feed network test was not run.

The packaged RuneLite pin, archive extraction/runtime validation, installer/frontend startup and first-run permission checks pass. The real packaged update helper passes activation, startup acknowledgement, failure rollback, parent waiting and preserved-data checks. Smoke tests use disposable homes and virtual displays; no game client is automated or live launcher/account data changed.

Artifacts are under `build/distribution/` with version `0.0.0-ci`; the full TAR contains the new app/runtime payload, while the `.run` is a launcher-only bootstrap. The owner confirmed the local test build works. Windows packaging is now being checked in hosted CI.

Manual acceptance: existing launcher sign-in, saved-account reuse, character selection, Play, LumBridge startup, gameplay bridge events and HapticScape outputs. Account/login consolidation is a separate feature and is not part of this restoration.

The owner confirmed the local test and authorized advancing the existing update PR for Windows validation. No public release is published by this fix.
