# Unified launcher design

Status: unified launcher beta published, 2026-10-06.
Linux Jagex sign-in, Play launch, and launcher-driven beta updates accepted by the user.
Windows packaging and migration checks pass in CI; hands-on Windows acceptance remains pending.

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

Bundled Java modules are shared by Linux and Windows packaging through
`packaging/java-runtime-modules.txt`. LumBridge's `--verify-runtime` entry point
checks RuneLite's HTTP server, compiler, JShell, attach and desktop APIs using the
actual bundled runtime before a package is emitted. A missing `jdk.httpserver`
previously let RuneLite's main thread fail while preload threads kept its JVM
alive. The LumBridge bootstrap now validates the runtime before startup and exits
with status 1 on fatal startup errors, so process status cannot remain running
solely because those preload threads survived the main-thread exception.

## Launcher-driven suite updates

The local Updates page compares semantic versions and offers **Install update and
restart** only for a newer selected-channel release with the matching OS/architecture package,
checksum and `HapticScape-Suite-VERSION.json` descriptor. Release publishers must
attach that descriptor alongside the existing Windows ZIP/LumBridge ZIP/checksums
and the Linux `.tar.gz`/checksum (the `.run`/`.deb` remain installation downloads).
Both packagers now generate the descriptor. Existing releases without it are shown
but cannot be installed as suite updates. No release is published automatically. Settings offers an opt-in beta channel,
which includes published prereleases and selects the highest semantic version;
the default stable channel continues using GitHub’s latest stable release.

The backend re-fetches the release before installation, checks exact repository/tag
asset URLs, streams bounded downloads, verifies SHA-256, rejects unsafe archive
paths/links and expansion limits, checks the staged suite version and required
components, and runs LumBridge's runtime probe before handing off. Hashes protect
integrity using the HTTPS GitHub release as the trust source; this is not a signed
update feed. Downloads and verification leave the installed version unchanged.

Installation is blocked while managed apps, the gameplay listener or matching
external Java clients are running, including clients left alive by a prior launcher.
A second check occurs after downloading. Other launcher actions cannot start apps
while an update is in progress. Installation resumes when the user closes the apps
and clicks the update button again; there is no queued unattended installation.

Windows uses the staged migration helper and retains its startup-confirmation and
rollback transaction. Its initial directory move retries briefly while the historical
compatibility bootstrap finishes exiting. Per-user Linux installations copy the
launcher into staging as a detached helper, wait for the old launcher to exit,
validate again, move the new suite into a unique release directory and atomically
switch the current link. The new frontend must acknowledge startup within 90 seconds;
failure restores the previous link and reopens the previous launcher. Existing Linux
release directories and user data remain untouched. Package-manager installations
(e.g. the `.deb` under /opt) show that updates belong in the system package manager
and cannot be replaced through the per-user updater.

Coverage includes version/asset selection, trusted repository URLs, manifest mismatch,
archive traversal/link rejection, controlled Linux activation/rollback, real Windows
migration transactions, extraction/runtime checks on both actual platform archives,
and a Linux Xvfb test of the packaged helper starting the real updated launcher then
rolling back a deliberately broken one. The user accepted the live Linux beta.1 to beta.2 launcher update. The download progress currently shows its phase,
not a byte-level progress bar; app data and account sessions survive restarts.

Saved preferences also contain component paths. On a versioned installation,
startup now rebases bundled paths to the active release while preserving external
JAR/Java overrides, the profile and preferences. This prevents a preference save
from pinning Java/apps to an older retained payload after the launcher updates.


## Post-beta cleanup and remaining acceptance

HapticScape no longer displays its own update controls or runs the old Java
release-check/preferences services. Its version label remains. The unified
launcher owns suite updates; Windows compatibility and migration helpers remain
for existing installations. Old updater preference files are left untouched.

The user accepted launcher-driven Linux beta updates. Remaining work includes
hands-on Windows account/Play and legacy migration acceptance and automatic
account token refresh. Expired sessions currently require signing in again.
The user attributed the apparent Wayland startup failure to an old launcher
instance still running; it is no longer tracked as an unresolved Wayland issue.

Update checks show a Checking state and the saved Stable/Beta channel. Errors
remain on the Updates page until another check instead of disappearing with a
toast or being overwritten by an old installation result. Rechecking clears stale
release details and installation actions.


## Launcher tray and identity

The launcher icon combines the existing HapticScape crystal and LumBridge bridge
icons, with an editable SVG and native PNG/ICO exports. The sidebar, window,
application entry, Windows bootstrap and tray share this launcher identity;
individual app cards retain their own icons.

X hides the main window to the tray. Restore shows and focuses it; Exit quits
only the launcher. Reopening the launcher also restores its existing window.
Update handoff still exits explicitly. Tray Exit is ignored during installation
to avoid interrupting verification. Without a tray or a Linux StatusNotifier
watcher, X closes normally so the window cannot become inaccessible.

The Play preference hides the launcher to the tray after a successful launch,
falling back to normal minimization if no tray host is available. Its persisted
preference key is unchanged. Linux uses a distinct combined-icon theme name
(`com.hapticscape.launcher.suite`) to avoid reusing the cached HapticScape icon;
the desktop application identifier stays `com.hapticscape.launcher`.

KDE taskbar acceptance passed after switching the desktop entry to the direct
installed `current/icon.png` path and reopening the launcher. Installers now
write direct icon paths; the separate theme name still serves the GTK window.

### Connection links through the unified launcher

The Linux desktop entry registers `x-scheme-handler/hapticscape` and passes one URI via `%u`. Per-user installers set that handler as the default. The Windows compatibility bootstrap registers its existing `HapticScape.exe` protocol target but forwards connection links to the unified launcher; legacy profile and maintenance entry points remain separate.

Cold-start arguments and single-instance callbacks use the same validated queue. Links start only HapticScape, using the selected profile and its existing Java data-directory inbox. An occupied gameplay port never counts as acknowledgement. Java retains participant validation, consent, duplicate-join handling and Remote Play focus. The launcher restores from its tray on a second invocation and shows handoff errors on Home.

Pending links live in launcher configuration, outside replaceable release directories. Submission markers prevent replay after Java consumes a request; a crash between enqueue and marker creation can deliver once again, so the handoff is at least once and Java's existing join deduplication remains necessary. Unix inboxes are private and records are written atomically. Links expire after five minutes, are never printed, and stay queued while update installation is in progress. Profile changes require restoring the original profile before delivering its queued link.

Automated checks cover strict URI validation, profile inbox paths, private atomic writes, repeated submission and acknowledgement across restart, plus Linux desktop registration. Interactive Discord cold-start/running/tray acceptance and Windows protocol activation still require a packaged build check before publishing a corrected stable release. The existing `v3.2.0` tag is unchanged.
