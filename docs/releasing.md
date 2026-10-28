# Releasing HapticScape

1. Merge the release changes into `master`. RuneLite's version is pinned in `runelite-bridge-client/RUNTIME.properties`.
2. Wait for CI to pass. Release notes are generated automatically; a nonempty `docs/releases/VERSION.md` can optionally supply custom notes.
3. Tag that commit and push the tag:

```sh
git tag -a v3.2.2 -m 'HapticScape 3.2.2'
git push origin v3.2.2
```

GitHub's Release workflow reuses CI to test and package Windows and Linux. It verifies the required assets, checksums, both suite manifests and the RuneLite pin inside each packaged LumBridge JAR. It stages the files in a draft, verifies the uploaded names and GitHub SHA-256 digests against the local assets, then publishes the complete release. Failed packaging or validation leaves the release unpublished. Tags are not moved or overwritten; a failed workflow can be rerun after resolving an infrastructure failure. An existing draft can be resumed; an already published release is never overwritten.

Stable tags must point to commits reachable from `master`. A tag such as `v3.2.2-rc.1` publishes a prerelease without replacing the latest stable release. Release notes are optional for prereleases too. Prerelease tags may point to a working branch for testing.

Public downloads remain the launcher EXE on Windows and the `.run` installer or `.deb` on Linux. Full ZIP/TAR archives, suite metadata and checksum files remain release assets because first-run downloads, existing launchers and legacy migration depend on them. A separate LumBridge ZIP is not published.

Branch and PR CI continue producing disposable `0.0.0-ci` packages. Standalone Windows packaging scripts still produce their own ZIPs when invoked directly. Unified packaging skips those intermediate archives and creates the final suite ZIP once.

This workflow does not merge RuneLite update PRs, choose release versions, or push tags automatically. A tag remains the explicit publish action. SHA-256 files preserve the current updater contract; this change does not introduce signed update manifests or executable code signing.

Local release-assembly checks:

```sh
python3 -m unittest discover -s packaging/release -p 'test_*.py'
python3 packaging/release/prepare.py v3.2.2
```
