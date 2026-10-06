# Unified launcher design

Status: Tauri development prototype implemented, 2026-10-06.
Linux Jagex sign-in and Play launch accepted by the user. Windows execution,
packaging, and coordinated installation remain acceptance work; this is not a completed launcher replacement.

## Product direction

One launcher is the normal entry point on Windows and Linux. It manages
HapticScape, LumBridge, Jagex account sign-in, and updates together. The user has
accepted the current Linux audio and secure-storage behavior for continued use;
additional edge-case acceptance is deferred unless problems emerge. This is not
a claim that the previously listed checks have all been performed.

Opening the desktop shortcut shows a home screen with:

- Account and selected character, with browser sign-in and sign-out controls.
- A primary **Play** action that starts HapticScape and LumBridge.
- **Open HapticScape** for remote control, music, and other use without a game.
- Installed versions, update availability, and download/install progress.
- Clear states for missing components, running apps, failed starts, and recovery.

HapticScape remains usable without Jagex sign-in. Character selection is distinct
from HapticScape's local test/configuration profiles. Closing the launcher does
not forcibly close either application. App exits still use their existing consent
and protected-exit behavior; an updater must never kill a protected client.

## Existing implementation

| Area | Current code and behavior |
| --- | --- |
| Windows entry point | `launcher/HapticScapeLauncher.cs`: WinForms/C# launcher, profile-scoped mutex, Discord deep-link handoff, update checks, then a Java child process it waits for |
| Companion installation | `launcher/UpdateCore.cs` and `TryEnsureLumBridge`: matching LumBridge release downloaded and verified; automatic opening occurs after installation, rather than as a persistent unified Play flow |
| Updates | `launcher/HapticScapeUpdater.cs`, `ApplicationLayoutValidation.cs`, and `launcher-tests/UpdateCoreTests.cs`: existing Windows update/install validation to preserve during migration |
| LumBridge entry point | `bridge-launcher/LumBridgeLauncher.cs`: independent Windows wrapper; searches bundled runtime, RuneLite runtime, JAVA_HOME, and PATH |
| Game bootstrap | `runelite-bridge-client/.../HapticScapeBridgeClient.java`: loads Local Event Bridge, then calls RuneLite.main; RuneLite is pinned to 1.13.1 in RUNTIME.properties |
| Java update UI | `src/main/java/.../update`: version checking/preferences, not a complete cross-platform installer |
| Secure storage | DPAPI on Windows, libsecret-backed authenticated envelopes on Linux; separate purposes already exist for unlock keys and Discord |
| Packaging | PowerShell Windows packages and two release bundles; Linux has a working desktop JAR but no equivalent unified installation package |

The Git remote is now `birdturtle/HapticScape`, while several updater/package
constants still name `ashy0019/HapticScape`. Resolve the intended release source
as part of launcher manifest migration, including compatibility for existing
installations. Do not silently change where old installed binaries obtain updates.

## Shared architecture

Use a separate Tauri 2 launcher in `suite-launcher/`: a local HTML/CSS/JavaScript
interface and Rust services for processes, authentication, storage, and updates.
HapticScape and LumBridge remain Java applications. Native webviews are WebKitGTK
on Linux and WebView2 on Windows. The user chose Tauri for a broader visual overhaul.

The launcher owns process coordination, component installation, account sessions,
and desktop entry points. HapticScape and LumBridge run in separate JVMs and own
their existing UI and shutdown lifecycle. The launcher must remain small enough
to open and repair a damaged or missing application installation.

Use explicit services for component discovery, process management, release
planning, downloads/install activation, browser authentication, and token storage.
Keep filesystem/process/network effects outside the UI and off the webview thread.
Use a dedicated account namespace: AES-256-GCM encrypted session envelopes with
a random wrapping key held in Linux Secret Service or Windows Credential Manager.
Only the small wrapping key goes into the wallet, avoiding Windows credential
size limits. There is no plaintext fallback or dependency on the Java runtime.

Start HapticScape first and establish readiness through a defined local status
contract before launching LumBridge. A running process alone is not proof that
the gameplay bridge is listening. Preserve profile/port options; make LumBridge's
bridge endpoint configurable before offering game launches on nondefault ports.
An already-running app should be detected and opened, rather than duplicated.
Do not kill unrelated Java or RuneLite processes.

## Browser authentication feasibility

The user-facing goal is browser sign-in, character selection, and secure session
reuse, without `.runelite/credentials.properties` or a credential-export setup.
Passwords and MFA codes belong on Jagex's pages in the user's browser.

Research checked on 2026-10-06:

- [RuneLite's development account guide](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts)
  still documents insecure credential export. It does not establish a public
  OAuth registration route for an independent launcher.
- [Jagex's launcher page](https://www.jagex.com/launcher) describes its own launcher;
  it does not supply the integration contract needed here.
- The locally resolved RuneLite 1.13.1 injected-client bytecode recognizes
  JX_SESSION_ID, JX_CHARACTER_ID, JX_DISPLAY_NAME, JX_ACCESS_TOKEN, and
  JX_REFRESH_TOKEN. This identifies a candidate child-process handoff; it does
  not prove browser authentication or determine which fields are required.
- [Bolt's archived GitHub README](https://github.com/Adamcake/Bolt)
  directs development to Codeberg. Its current source could not be inspected
  through the available web fetch during this review. It is a research lead,
  not a selected dependency or verified integration.

The [RSClient source](https://github.com/SilverBoi78/jagex-launcher-linux)
provides a concrete Linux protocol reference. Its launcher uses authorization
code with PKCE, then a second identity/consent step in the same embedded browser,
then the game-session and character APIs. The prototype independently implements
this sequence; no AGPL source was incorporated. Callback navigation is intercepted
before requesting the registered HTTPS redirect or localhost destination.

[Jagex's published OIDC metadata](https://account.jagex.com/.well-known/openid-configuration)
confirms the issuer, authorization/token endpoints, RS256 signing keys, and PKCE.
The implementation verifies signatures, issuer, audience, expiry, state, and the
consent nonce/subject. This validates the protocol components; it does not establish
official support or public client registration for this launcher. The user verified successful Linux sign-in and launch after fixing blocked blank
login frames. Windows account acceptance remains required.

The Jagex window is separate from the local launcher, receives no launcher
capabilities, and cannot invoke privileged app commands. Passwords and MFA remain
on Jagex pages. The verified candidate game handoff is JX_SESSION_ID,
JX_CHARACTER_ID, and JX_DISPLAY_NAME, supplied only to the LumBridge child.

For an available authorization-code flow, use state validation and PKCE where
supported, and nonce/issuer/audience validation when applicable to OIDC. Use only
redirects the provider actually permits; do not assume arbitrary localhost ports
or custom URI schemes will work. Keep callback endpoints short-lived and bound
to loopback, and never log callback URLs, tokens, or authorization responses.

Persist required reusable secrets only through native wallet-backed encryption with a separate
account namespace. A locked/unavailable wallet gives a visible retry/sign-in
path, never a plaintext fallback. Deliver only the required launch fields to
LumBridge's child environment or another verified private handoff, not command
arguments, temporary credential files, logs, or HapticScape's environment.
Child environments remain accessible within the user's OS security boundary;
they remove persistent plaintext files, not every possible local token exposure.

Avoid enabling RuneLite's insecure credential-write option. Verify that startup
and sign-out create no plaintext credentials. An existing credential file must
not be deleted automatically: provide explicit migration/removal after successful
new sign-in. Local sign-out deletes launcher-owned tokens; remote revocation is
reported separately and only claimed when the provider confirms it.

## Coordinated updates and installation

Publish a suite manifest describing OS/architecture assets, launcher version,
HapticScape version, LumBridge/RuneLite version, bridge compatibility, runtime
requirements, and verification data. Components may have different versions;
the manifest defines a tested compatible set. Preserve bridge provenance/licenses.

Keep application data outside versioned installation directories. Download and
extract into staging, validate paths/layout/compatibility and integrity, then
activate a completed version through a small atomic state change. Never overwrite
a running JAR or runtime. Keep the prior known-good set for recovery. A failed
download or activation leaves installed components launchable.

Reconcile existing Windows SHA-256 verification and layout tests with the shared
installer. A checksum published beside an asset provides integrity checking but
not independent publisher authentication; establish a signed-manifest/key policy
before claiming authenticated updates. Launcher self-update needs its own small
bootstrap/helper because the running launcher cannot replace itself safely.

Allow launching installed components while offline and deferring updates.
HapticScape-only operation remains available when LumBridge is missing. A Play
failure names the missing or incompatible component and provides retry/repair.
Do not remove old working bundles until activation and recovery checks succeed.

Provide one Windows shortcut and one Linux desktop entry. Route autostart and
Discord links through the launcher with explicit actions/profile routing, without
opening a game or requiring Jagex authentication for a remote invitation. Existing
direct Java entry points remain available for development and recovery.

## Implementation sequence and acceptance

| Milestone | Deliverable | Required evidence |
| --- | --- | --- |
| 1: shared front door | Separate launcher module, home/status UI, existing-install discovery, HapticScape-only and Play paths, process readiness/duplicate handling; account UI accurately marks authentication as unavailable until proven | Local fake-child tests plus launching both real apps on Linux; Windows launch verification; no changes to running artifacts or protected exits |
| 2: account proof | Verified browser flow, character selection, secure token persistence, refresh/sign-out, LumBridge handoff without credential files | Live successful login on both OSes; cancellation/expiry/wallet failure checks; no secrets in arguments/logs/files; document provider contract and support status |
| 3: coordinated updates | Suite manifest, verified staged installation, compatible-set activation, repair/rollback and launcher self-update | Interrupted download/extraction/activation tests, malicious archive/path rejection, running-app update deferral, recovery and offline launch |
| 4: single installation | Runtime bundles, Windows/Linux installers, shortcut, autostart and Discord routing; old-layout migration | Clean-machine install on each OS, update from an existing Windows package, reinstall without losing user data, uninstall behavior |

Authentication contract research precedes live account implementation. The first
launcher milestone can proceed independently of it, but cannot be described as
the completed credential-file replacement. Full launcher delivery includes all
four milestones. No additional audio/backend work is planned unless a concrete
bug is reported.

## Development prototype status

Implemented: home/accounts/updates/settings UI; configurable Java/JAR paths;
HapticScape-only and authenticated Play actions; serialized child launches;
content-addressed immutable JAR snapshots; PKCE/two-stage identity exchange;
character selection; automatic secure multi-account storage and individual removal; and
real GitHub release metadata checks. Existing application lifecycle is preserved.
Eleven Rust unit tests cover callback and login-frame validation, profile validation, immutable
snapshots, and authenticated encryption; frontend tests cover window capabilities
and content policy.

Current limits: no automatic token refresh or remote
revocation; bridge readiness uses port availability rather than an identity/status
handshake; an unrelated listener is refused and existing external processes are
not adopted. Release checks do not download or install. Default component paths
point into the development checkout. Installers, runtime distribution, desktop
integration, update activation/repair/rollback, and Windows account/launch acceptance
remain the next milestones. Existing credential files are never deleted.

The launcher UI follows HapticScapeTheme colors and WorkspaceShell navigation,
with square controls and the existing component icons. Account authorization
and character selection sit beside Play on Home. The background asset and its
publisher license are documented in `suite-launcher/ui/assets/CREDITS.md`.

User-facing Settings now separates preferences from collapsed troubleshooting
overrides. Minimize-after-Play is applied only after a successful Play action;
saved accounts always load automatically; release checks run once at startup when
enabled. Defaults preserve manual launch behavior and older settings files migrate
without resetting component paths. Frontend behavior tests cover startup actions,
non-repetition during polling, and preservation of unsaved preference edits.

Account UX: authenticate once to add an account automatically, select it from the
Home list, and remove it explicitly when desired. Additional accounts accumulate;
reauthentication replaces the same identity rather than duplicating it. Selected
account/character persist. Existing single-account encrypted storage migrates on
load, and wallet failures preserve existing data. Multi-account persistence,
switching, removal, repeat login, and migration have unit coverage; live acceptance
of the new multi-account flow remains to be checked by the user.

## Existing Windows updater migration candidate

Build with `./package-all.ps1 -Version 0.0.0-ci -UnifiedLauncher` on Windows
with Node 22, stable Rust, and JDK 11 installed. CI builds this candidate;
normal packaging stays opt-in until Windows acceptance is complete.

The desktop ZIP retains the historical asset name, top-level `HapticScape`
directory, `HapticScape.exe`, legacy JAR alias, `app/release.json`, and Java
runtime expected by already-installed updaters. It additionally includes the
Tauri launcher, the complete LumBridge bundle, a Microsoft-signed WebView2
Evergreen bootstrapper, and `app/suite.json`. Publish the matching separate
LumBridge ZIP and both checksums too: existing updaters require those assets.
The packager verifies the browser bootstrapper's Authenticode signature before
shipping it; the bootstrap and update helper install it only when needed.

Existing shortcuts launch the suite through the compatibility bootstrap.
Discord protocol links, explicit profiles/ports, minimized startup and update
settings retain their historical behavior through `HapticScapeLegacy.exe`.
Installed launcher defaults discover the bundled Java/JAR paths and leave the
profile blank, using the existing normal HapticScape user-data directory.
No account credentials, pairing history, profiles or wallet keys are deleted.
Jagex accounts are added through sign-in in the new launcher; old credential
files are not imported or automatically removed.

An old updater runs the newly staged update helper. That helper keeps its backup
until the new launcher's frontend renders and the installed component files
are found. It uses a unique acknowledgement token in the update staging folder,
independent of saved development paths or wallet availability. Early exit or a
90-second startup timeout triggers an ordinary window-close request and rollback
when filesystem locks permit it. The helper never kills Java clients; if rollback
is blocked, the backup remains available rather than being deleted.

The legacy repository address `ashy0019/HapticScape` currently resolves through
GitHub's API to `birdturtle/HapticScape` (checked 2026-10-06). Keep the historical
repository value in the legacy manifest because installed updater parsers require
it; the suite marker and launcher's release check use the current repository.

Release gate: on Windows, update from the actual currently released package,
verify the existing shortcut opens the suite and prior app data remains available,
exercise Jagex sign-in/Play, and repeat with a deliberately broken launcher to
verify rollback. Test a machine without WebView2 and an installation with running
clients/locked files. Native acknowledgement tests and candidate packaging run in
Windows CI; Linux unit tests alone do not establish Windows upgrade acceptance.
Use a version newer than the current stable release and publish both versioned
ZIPs plus checksums only after that gate. No stable release is published by this
implementation. Suite self-update activation and a Linux installer remain separate
work: the launcher's Updates screen currently checks release metadata.

## Automated migration transactions and Linux distribution

`launcher-tests/test-migration.ps1` compiles controlled native launcher fixtures
and invokes the production updater transaction (`HapticScapeUpdater.Run`). It
covers replacement and startup-failure rollback from both the legacy JAR layout
and the standalone bundled-runtime layout, verifies the existing executable target,
staging/backup cleanup, runtime prerequisite invocation and unchanged external user
data. Runtime installation and error presentation are injected at the transaction
boundary; directory replacement, validation, process launch, acknowledgement and
rollback are the production implementation. These tests do not establish browser
rendering, real Windows credential persistence or Jagex/Java launch acceptance.

`bash package-linux.sh 0.0.0-ci` builds and checks both Java apps and the Tauri
launcher, bundles a jlink Java runtime and licenses, then emits a checksummed
self-extracting `.run`, a portable `.tar.gz`, and a `.deb` when dpkg-deb is available.
Release packaging requires a clean matching version tag. Linux CI builds on Ubuntu
22.04, avoiding a dependency on the newer glibc used by the development machine.

The `.run` and archive installer need no root privileges and install versioned
payloads under XDG_DATA_HOME/hapticscape/releases. An atomic current symlink switches
the application-menu entry to the new release without overwriting a running binary.
Existing app data and launcher account settings stay outside the payload. Installer
checks cover fresh installation, upgrade, paths containing spaces, preservation of
the old payload/data and rejection of missing components. Previous versions are
retained for recovery; automatic launcher-driven download/update activation is still
separate work. Debian installation uses /opt/hapticscape with dependency metadata
and a desktop entry managed by the package manager. GTK/WebKit and a working Secret
Service wallet remain system prerequisites; PipeWire tools support music capture.

`packaging/linux/test-package.py` also installs the actual self-extracting bundle
into an isolated home, executes bundled Java, and opens the installed native
launcher on Xvfb. It requires the real frontend's startup acknowledgement, covering
installed path discovery and initial rendering. When present, the `.deb` is
extracted and checked for both apps, Java, the launcher, desktop entry and declared
WebKit/libsecret dependencies. This is a startup smoke check, not live Jagex login,
wallet persistence, game rendering or hardware acceptance.
