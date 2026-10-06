# HapticScape

HapticScape is a standalone Windows application that turns Old School RuneScape gameplay events, Windows system audio, and approved Remote Play actions into configurable haptic feedback and optional clicker sounds.

Haptics are sent through [Intiface Central](https://intiface.com/). Gameplay events are received through a small local bridge client.

Prebuilt releases target Windows 10 and newer.

> [!IMPORTANT]
> HapticScape, LumBridge, and the Local Event Bridge are unofficial software. They are not endorsed by Jagex, RuneLite, Intiface, or any device manufacturer.

 <img width="1920" height="1032" alt="image" src="https://github.com/user-attachments/assets/580678a7-47d7-4c2c-8fd1-048a0f7a1a6c" />

## HapticScape 3 components

| Component | Purpose |
| --- | --- |
| **HapticScape** | Standalone desktop application. Owns haptics, clicks, music sync, settings, Remote Play, safety controls, updates, and the UI. |
| **Local Event Bridge** | Small RuneLite-side plugin that observes an allowlisted set of gameplay facts and publishes neutral events to HapticScape over localhost. |
| **LumBridge** | RuneLite package with the Local Event Bridge built in. HapticScape 3 installs the matching LumBridge release automatically when it is missing. |
| **Intiface Central** | Connects supported haptic devices and exposes them to HapticScape over the Buttplug protocol. |
| **Remote Play relay** | Optional WebSocket relay used to connect two HapticScape clients for encrypted remote sessions. |

The default gameplay bridge endpoint is `127.0.0.1:41713`. It binds only to IPv4 loopback and is not exposed to the LAN or internet.

## Install on Windows

### Requirements

- Windows 10 or newer.
- [Intiface Central](https://intiface.com/) if you want haptic device output.
- A device supported by Intiface if you want haptic output.
- The official [RuneLite launcher](https://runelite.net/) or another Java 11+ runtime if you use LumBridge. LumBridge checks for the RuneLite JRE first.

HapticScape itself includes a packaged Java runtime. Java is only needed separately for LumBridge.

### New installation

1. Open [HapticScape Releases](https://github.com/ashy0019/HapticScape/releases).
2. Open the latest stable release.
3. Download `HapticScape-Windows-x64-X.Y.Z.zip` or the matching package for your architecture.
4. Extract the ZIP to a writable folder.
5. Run `HapticScape.exe`.
6. If LumBridge is not installed, HapticScape downloads the matching LumBridge release, verifies its SHA-256 checksum, extracts it into `HapticScape\LumBridge`, and opens it.
7. Start Intiface Central if you use haptics.
8. Connect your device in Intiface Central.
9. In HapticScape, connect to Intiface. The default address is `ws://localhost:12345`.
10. Use a low intensity and short test pattern before normal use.

Keep the complete HapticScape folder together. Do not move `HapticScape.exe` away from its `app` and `runtime` folders.

### Connect Intiface

1. Open Intiface Central.
2. Start the engine or server.
3. Scan for and connect the device in Intiface.
4. Confirm the device responds to Intiface's own controls.
5. Open HapticScape.
6. Leave the address at `ws://localhost:12345` when Intiface is on the same computer.
7. Select **Connect**.
8. Use the HapticScape test controls at a low intensity.

Some devices ignore very low vibration values. Increase intensity gradually if the device is connected but does not respond.

### Jagex Accounts and LumBridge

LumBridge is a custom RuneLite development client. Jagex Account sessions may require RuneLite's development credential setup.

Use RuneLite's guide:

<https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts>

The usual setup is:

1. Install the current official RuneLite launcher.
2. Open **RuneLite (configure)** from the Windows Start menu.
3. Add `--insecure-write-credentials` to **Client arguments**.
4. Launch RuneLite through the Jagex Launcher once.
5. Close RuneLite.
6. Start LumBridge.

This creates `.runelite/credentials.properties`. Treat that file as account credentials. Do not upload, commit, package, or send it to another person.

### SmartScreen and checksums

HapticScape releases are not code-signed, so Windows may show a SmartScreen warning.

Each Windows ZIP also has a `.zip.sha256` file. To verify a download:

```powershell
Get-FileHash ".\HapticScape-Windows-x64-X.Y.Z.zip" -Algorithm SHA256
```

Compare the displayed hash with the value in the matching `.zip.sha256` file.

## Migrating from HapticScape 2.x

HapticScape 3 changes the RuneLite integration model.

HapticScape 2.x ran HapticScape code inside a custom RuneLite client. HapticScape 3 runs as a separate desktop application. RuneLite only provides neutral gameplay events through the Local Event Bridge.

### Upgrade through the built-in updater

When an existing 2.x installation updates to 3.x:

1. The existing updater installs HapticScape 3 normally.
2. The new HapticScape 3 launcher starts.
3. If the matching LumBridge package is missing, HapticScape downloads it from the same GitHub release.
4. The LumBridge checksum is verified.
5. LumBridge is extracted into the HapticScape folder and opened.
6. HapticScape starts as a separate application.

A LumBridge download failure does not roll back an otherwise valid HapticScape update. HapticScape still starts and will try the companion setup again later.

### Settings after migration

HapticScape 3 stores its standalone settings under:

```text
%LOCALAPPDATA%\HapticScape
```

Updater preferences are migrated from the older HapticScape update location when possible.

The old RuneLite plugin configuration is not the HapticScape 3 settings store. Review your XP, alert, click, Intiface, Remote Play, and custom pattern settings after the first 3.x launch before relying on them.

### What changes for normal use

- Start `HapticScape.exe` for the main application.
- Start LumBridge for gameplay events.
- Intiface still runs separately.
- Remote Play still runs between HapticScape clients, not between RuneLite clients.
- HapticScape no longer depends on RuneLite APIs, UI classes, settings APIs, or device control code.

## Features

### XP and skill feedback

HapticScape receives XP changes as neutral gameplay events and applies HapticScape-owned feedback rules.

Global haptic controls include:

- Minimum haptic XP gain.
- Intensity from 0% to 100%.
- Pattern selection.
- Duration from 50 ms to 10 seconds for built-in patterns.
- Level-up feedback.
- Milestone feedback.

Each skill has separate haptic and click enable controls.

Each skill can also inherit the global XP profile or use its own override. A per-skill override includes:

- Minimum haptic XP gain.
- Haptic intensity.
- Haptic pattern.
- Haptic duration.
- Minimum clicker XP gain.
- XP gain click sequence.
- Level-up click sequence.
- Milestone click sequence.

The selected skill is highlighted in the skill list while its override is being edited.

### Level-up & milestone
Level-ups and decade milestones can use patterns separate from ordinary XP feedback.

### Built-in haptic patterns

Built-in patterns are:

- Single
- Double
- Triple
- Ascending
- Descending

### Semantic gameplay alerts

HapticScape has a Generic notification profile plus separate profiles for recognized gameplay events:

- Direct message
- Trade request
- Low hitpoints
- Low prayer
- Valuable drop
- Full inventory
- Poison or venom
- Special attack ready
- Player death

Each specific alert can be disabled, inherit the Generic profile, or use its own haptic pattern, intensity, and duration.

### Pattern Forge

Pattern Forge creates reusable custom haptic patterns.

- Set one beat from 250 ms to 10 seconds.
- Repeat the beat from 1 to 72 times.
- Preview the complete output timeline.
- Undo recent edits.
- Clear and redraw the curve.
- Add, rename, and delete patterns.
- Store up to 100 custom patterns.
- Assign custom patterns to XP feedback, skill overrides, alerts, milestones, previews, and Remote Play actions.

Custom patterns store their own curve, beat duration, and repeat count. Renaming a pattern preserves its assignments. Deleting an assigned pattern falls back to the built-in Single pattern.

On wide windows, the Forge can remain visible beside the main workspace. On smaller windows, the layout collapses and scrolls.

### Music sync

Music sync is available on Windows. **Entire output** analyzes a selected Windows output endpoint with WASAPI loopback capture. **Application audio** follows the local peak meter for one application currently represented in Windows Volume Mixer, allowing music or a game client to drive haptics without reacting to every system sound. **Default Windows output** preserves the original follow-the-system behavior, and other active render endpoints—including generic virtual audio routes—remain selectable.

Controls include:

- Smooth, Rhythmic, and Punchy response modes.
- Sensitivity.
- Minimum haptic output.
- Maximum haptic output.
- Live output meter.
- Windows master volume and mute scaling.
- Local capture-mode, output-source, and mixer-application selection.
- Manual refresh after an output or application starts, stops, or moves.

Finite XP, alert, preview, remote pattern, and Live Forge output can temporarily take the haptic channel. Music sync resumes afterward.

Music sync analyzes audio in memory. It does not record or upload the audio stream.
The selected endpoint ID is stored only on that computer and is never included in Remote Play settings. If an explicitly selected endpoint disappears, Music sync stops with a visible error rather than silently capturing a different output.

### Audio click feedback

The clicker is independent of Intiface and can be used with or without a haptic device.

Global click controls include:

- Enable or disable click output.
- Volume from 0% to 100%.
- Global XP click threshold.
- XP gain sequence of one, two, or three clicks.
- Optional level-up override of zero to three clicks.
- Optional milestone override of zero to three clicks.
- Generic notification click sequence.
- Per-alert click sequence.

Double and triple sequences use fixed spacing so they read as separate clicks instead of one muddy sound.

Per-skill click thresholds and click sequences are part of each skill's XP override.

### Phrase click rules

HapticScape can play click sequences when local chat text matches configured rules.

- Up to 50 rules.
- Contains matching.
- Exact matching.
- Java regular expression matching.
- One, two, or three clicks per rule.
- Individual enable or disable state.
- Case-insensitive Contains and Exact matching.

Phrase matching happens locally. Chat messages used for matching are not sent through Remote Play.

### Intiface and device control

HapticScape connects directly to an Intiface WebSocket server.

- Default address: `ws://localhost:12345`.
- Discover connected devices.
- Send output to compatible connected devices.
- Test configured patterns.
- Reconnect after Intiface restarts.
- Stop all device output immediately.

### Remote Play

Remote Play connects a controller and participant running separate HapticScape clients.

The default relay is:

```text
wss://hapticscape-remote-relay.hapticscape.workers.dev/relay
```

The relay address can be changed under **Advanced** for self-hosted deployments.

Remote session messages are encrypted before they reach the relay.

#### Manual pairing

1. The controller selects **Create & copy code**.
2. The controller sends the temporary code to the participant through a private channel.
3. The participant selects **Paste & join**.
4. The participant reviews and accepts the session.
5. The participant's allowed settings and permissions are synchronized to the controller.

#### Discord pairing

The hosted relay includes an optional Discord companion flow.

- Install the Discord app from HapticScape.
- Run `/hapticscape link` and link each HapticScape installation once.
- A controller can run `/hapticscape connect` in a one-to-one DM with the participant.
- The participant receives Accept and Deny controls in Discord.
- HapticScape still shows a local consent prompt before the session begins.
- `/hapticscape status` reports linked-client status.
- `/hapticscape unlink` removes the Discord link.

#### Remote settings control

If the participant allows remote settings changes, the controller can edit the participant's HapticScape feedback profile.

Synchronized settings include:

- XP feedback.
- Skill enablement and skill overrides.
- Alert profiles and thresholds.
- Custom patterns.
- Music sync settings.
- Click settings.
- Phrase click rules.
- Application startup settings when separately permitted and selected for a lock request.

Changes are saved on the participant's computer. The controller's own local settings are not replaced.

The participant's Intiface address, Intiface connection state, Emergency Off control, and Remote Play permissions remain user-owned.

#### Remote actions

If permitted, the controller can request:

- A built-in haptic pattern.
- A participant-saved custom haptic pattern.
- Haptic intensity and duration within participant-set caps.
- Stop remote haptic output.
- A local click sound.
- A desktop notification.
- A local HapticScape status notice.

#### Live Forge

- Mouse height controls current intensity.
- Releasing the mouse fades output to zero.
- Emergency Off, session end, connection loss, and application shutdown stop live output.

Live Forge gestures are temporary and are not saved into the participant's Pattern Forge library.

#### Subject activity feed

The participant can allow the controller to see a bounded live activity feed.

Includes:

- XP gains.
- Level-ups.
- Level milestones, including level 99.
- Hitpoints, prayer, and special attack value changes.
- Inventory becoming full.
- Poison, venom, and clear status changes.
- Loot value and stack count.
- Player death.
- Trade request receipt.
- Direct message receipt.

Raw direct-message text is not included. Generic chat text is not included. The feed keeps the most recent 50 entries and is cleared when sharing is revoked or the controller session ends.

#### Participant permissions

Only the participant can change Remote Play permissions.

Permissions include:

- Change HapticScape settings.
- Request haptic actions.
- Use continuous Live Forge control.
- Play click sounds.
- Display desktop notifications.
- Display local HapticScape notices.
- Request protected startup and exit controls.
- View shared live activity.
- Maximum remote haptic intensity.
- Maximum remote finite-pattern duration.
- Maximum Live Forge hold duration.

#### Emergency controls

The participant always retains local session controls.

- **Emergency Off** stops output and rejects new remote output.
- **Resume** re-enables allowed remote actions.
- **End session** ends the session.
- Intiface controls remain local.

Connection loss and session shutdown stop accepted remote output and clear pending remote action state.

#### Atomic settings locks

During a Remote Play session, the controller can shift-click supported settings or sections in the participant workspace to prepare a targeted post-session lock proposal.

Lock targets include individual skills, skill profile blocks, alert blocks, click blocks, feedback blocks, phrase rules, level-up and milestone settings, level 99 celebration, and selected application startup or exit controls.

The participant must explicitly accept the proposal. Declined or failed proposals do not create a lock.

If accepted:

- The selected final values remain locked after the session ends.
- The unlock key is generated on the controller side.
- The controller can save the accepted key locally.
- Saved unlock keys use Windows DPAPI for the current Windows account.
- Emergency Off, End session, Intiface controls, Remote Play permissions, Forge, Music, and developer recovery remain available.

#### Protected startup and exit

A participant can separately allow protected startup and exit requests.

A protected lock can include:

- Start HapticScape with Windows.
- Start minimized to the tray.
- Password-protected application exit.

When protected exit is active, closing from the tray or other exit path requires the lock password. After 10 seconds, **Exit without password** becomes available. Using it stops output, records an unauthorized end, and reports the event to the controller when possible.

#### Saved Unlock Keys

Controllers can keep accepted unlock keys in a local vault.

- Windows DPAPI protection for the current Windows account.
- Optional labels and notes.
- Copy a key when it is needed.
- Delete saved entries.

### Updates

The packaged Windows launcher handles stable release checks.

- Check GitHub Releases automatically or manually.
- Install updates automatically or ask before installation.
- Notify without enabling automatic installation.
- Skip one specific version.
- Verify downloaded ZIPs with published SHA-256 checksums.
- Validate staged HapticScape packages before replacement.
- Restore the previous HapticScape bundle if installation fails.
- Start the installed version when GitHub cannot be reached.

Draft and prerelease GitHub releases are not offered through the stable update channel.

With both automatic updates and update notifications disabled, normal startup does not contact GitHub. **Check now** still performs a manual release check.

For HapticScape 3 releases, the updater also looks for the matching LumBridge client. LumBridge setup is best-effort and does not block a valid HapticScape update if the companion download fails.

### LumBridge

LumBridge packages RuneLite with only the Local Event Bridge built in.

It does not contain the HapticScape runtime, haptic device code, Remote Play, music sync, settings UI, clicker, Intiface client, or update logic from the main application.

LumBridge publishes the bridge allowlist to HapticScape over `127.0.0.1:41713`.

The HapticScape repository records the exact upstream bridge commit included in each LumBridge build in:

```text
runelite-bridge-client/BRIDGE-SOURCE.properties
```

## Gameplay bridge and privacy

### HapticScape does not automate gameplay

HapticScape consumes gameplay observations and produces local feedback. It does not move the player, click game objects, choose menu entries, type game chat, or construct gameplay packets.

Users are responsible for following the current Jagex rules for third-party clients:

<https://legal.jagex.com/docs/rules/macro-and-client-features-not-permitted>

### Intiface data

The default `ws://localhost:12345` connection stays on the same computer.

HapticScape sends Intiface the protocol handshake and device commands needed for configured haptic output. It does not send RuneScape passwords, chat messages, Remote Play invitations, or HapticScape remote-session keys to Intiface.

Do not expose an unencrypted `ws://` Intiface server to the public internet.

### Remote Play data

The default relay is a transport service. HapticScape encrypts remote session messages before sending them through the relay.

The relay still receives connection metadata needed to operate, including IP addresses, room identifiers, and roles.

Remote settings synchronization can include custom patterns and phrase-rule definitions. It does not include the chat messages that were tested against those phrase rules.

The optional activity feed uses an explicit allowlist and does not transmit raw direct-message text.

### Local files

Default Windows application data is stored under:

```text
%LOCALAPPDATA%\HapticScape
```

This includes normal settings plus separate files for update preferences, persistent locks, protected-exit state, Discord device credentials, and saved unlock keys.

Named local test profiles are stored under:

```text
%LOCALAPPDATA%\HapticScape\profiles\<name>
```

Secret stores use dedicated protection where supported. Saved unlock keys and Discord device credentials use Windows DPAPI.

The experimental Linux backend uses Secret Service through libsecret, with authenticated encrypted file payloads and keyring-held encryption keys. KDE acceptance remains pending; see [Linux keyring implementation and testing](docs/linux-keyring-testing.md). Output-mode Music Sync has an experimental [PipeWire backend](docs/linux-pipewire-testing.md); application-only capture remains pending.

## FAQ

### Is HapticScape still a RuneLite plugin?

No. HapticScape 3 is a standalone desktop application. RuneLite integration is limited to the Local Event Bridge.

### What is LumBridge?

LumBridge is RuneLite with the Local Event Bridge built in.

### Do I need to download LumBridge manually?

Normally no. HapticScape 3 checks for the matching LumBridge version and installs it beside HapticScape when it is missing.

### Can I use normal RuneLite?

Not yet. TBA?

### Does HapticScape need Intiface if I only want clicks?

No. Click feedback works without Intiface. Intiface is only required for haptic device output.

### Can HapticScape control more than one Intiface device?

HapticScape sends compatible output to discovered devices with supported vibration or scalar actuators. Device behavior still depends on Intiface and the hardware.

### Where are HapticScape 3 settings stored?

On Windows, the default location is `%LOCALAPPDATA%\HapticScape`.

### Will my old 2.x plugin settings automatically become 3.x settings?

Do not assume that they will. HapticScape 3 uses its own standalone settings store. Review the configuration after migration.

### Why does LumBridge need RuneLite or Java installed?

LumBridge is a Java RuneLite client. Its launcher first looks for the official RuneLite JRE, then `JAVA_HOME`, then Java on `PATH`.

### Why does LumBridge not show my Jagex Account session?

Custom RuneLite development clients can require RuneLite's development credential setup. Follow RuneLite's Jagex Account development guide linked in the installation section.

### Does the Remote Play activity feed send my private messages?

It can report that a direct message was received if the participant enables activity sharing. It does not send the raw direct-message text. Generic chat text is not part of the activity feed.

### Can a controller change my permissions?

No. Remote Play permissions are participant-owned. The controller can only use actions the participant allows.

### Can a controller bypass my haptic caps?

No. The participant client enforces maximum intensity and duration locally.

### Can a controller permanently lock settings?

Only after the participant explicitly accepts a lock proposal. The proposal is limited to selected settings. Local safety controls remain available.

### Can protected exit trap the participant in the application?

No. After 10 seconds, the protected exit dialog exposes **Exit without password**. Using it is recorded and reported as an unauthorized end when possible.

### Does Music sync upload audio?

No. Audio analysis is local and in-memory.

### Can I run two HapticScape clients on one PC for testing?

Yes. Use separate profiles and gameplay ports. For example:

```powershell
.\HapticScape.exe --profile subject --gameplay-port 41713
.\HapticScape.exe --profile controller --gameplay-port 41714
```

Leave the subject on `41713` when it should receive events from the standard Local Event Bridge.

### Does HapticScape run on macOS or Linux?

The source is Java-based, but the supported prebuilt release is Windows. Music sync, Windows startup integration, the packaged launchers, system tray behavior, and DPAPI-backed secret storage contain Windows-specific code.

## Troubleshooting

### HapticScape does not start

- Extract the ZIP before running it.
- Keep `HapticScape.exe`, `app`, and `runtime` together.
- Move the installation to a folder your Windows account can write to.
- Check whether antivirus or SmartScreen blocked the launcher or runtime.

### LumBridge does not start

- Install or repair the official RuneLite launcher.
- Confirm Java 11+ is available if you are not using RuneLite's JRE.
- Keep `LumBridge.exe` beside its `app` folder.
- If automatic setup failed, download the matching `LumBridge-Windows-<arch>-<version>.zip` from the same HapticScape release and extract it manually.

### HapticScape shows no gameplay activity

- Start LumBridge.
- Confirm HapticScape is running before testing events.
- Do not run two bridge copies at once.
- Restart the bridge after HapticScape if the connection does not recover.
- Use the default gameplay port `41713` unless both sides were intentionally configured for another port.

### HapticScape stays on Connecting to Intiface

- Confirm Intiface Central is running.
- Confirm its engine or server is started.
- Confirm the configured WebSocket address and port.
- Use `ws://localhost:12345` when both applications run on the same computer.
- Disconnect and reconnect after restarting Intiface.

### A device does not appear

- Scan for the device in Intiface Central first.
- Confirm it responds to Intiface's own test controls.
- Close manufacturer software or other programs that may already own the device connection.
- Check Intiface documentation for Bluetooth, USB, serial, or network requirements.

### A listed device does not respond

- Confirm the device exposes vibration or another compatible scalar actuator through Intiface.
- Raise intensity gradually because some hardware ignores very low values.
- Stop all output, reconnect, and test the built-in Single pattern.

### Music sync shows no output

- Confirm audio is playing through the endpoint selected under **Audio source**.
- Select **Default Windows output** to follow the current Windows default, or press **Refresh** after connecting or enabling a new output device.
- For **Application audio**, start playback in the application before refreshing the list. The saved selection waits quietly if that application later closes.
- Confirm Windows is not muted.
- Raise Music sensitivity and maximum intensity.
- Raise minimum intensity if the device ignores low output values.

### Remote Play does not connect

- Use the same current HapticScape release on both clients.
- Confirm both clients can reach the configured `wss://` relay.
- Generate a new connection code if the old one expired or was used.
- Check **Advanced** if either client uses a self-hosted relay.
- End incomplete sessions before retrying.

### Discord Accept does not open HapticScape

- Run the packaged `HapticScape.exe` once so it can register the `hapticscape://` handler.
- Confirm both users linked the Discord app.
- Allow the browser to open the HapticScape protocol link.
- Keep the HapticScape folder in one location. Running `HapticScape.exe` again updates the handler if the folder moved.
- Use manual connection codes if Windows policy blocks per-user protocol registration.

## Build from source

HapticScape targets Java 11 and includes the Gradle wrapper.

### Requirements

- Git
- Java 11 JDK
- Windows for the packaged launchers and Windows release bundle
- .NET Framework 4.x for compiling the native Windows launchers
- Intiface Central for device testing

Clone the repository:

```powershell
git clone https://github.com/ashy0019/HapticScape.git
cd HapticScape
```

Run tests and standalone boundary checks:

```powershell
.\gradlew.bat clean test
```

Run the standalone desktop application:

```powershell
.\gradlew.bat runStandalone
```

Build the standalone JAR:

```powershell
.\gradlew.bat standaloneJar
```

The JAR is written to:

```text
build\libs\hapticscape-desktop.jar
```

### Run LumBridge from source

Verify the LumBridge artifact:

```powershell
.\gradlew.bat :runelite-bridge-client:verifyBridgeClientArtifact
```

Run the development LumBridge client:

```powershell
.\gradlew.bat :runelite-bridge-client:runBridgeClient
```

The vendored bridge source comes from:

<https://github.com/ashy0019/runelite-local-event-bridge>

When the canonical bridge changes, update the HapticScape snapshot with:

```powershell
.\sync-runelite-bridge.ps1
```

The sync script expects a clean local bridge repository and records the exact source commit in `runelite-bridge-client/BRIDGE-SOURCE.properties`.

## Build Windows release packages

Create the `vX.Y.Z` tag on the release commit before packaging. On Windows, check out that exact tag and build HapticScape and LumBridge together:

```powershell
git fetch origin --tags
git switch --detach vX.Y.Z
Get-Content .\runelite-bridge-client\RUNTIME.properties
.\package-all.ps1 -Version X.Y.Z
Get-Content .\build\bridge-windows-package\LumBridge\app\release.json
```

The packager rejects a missing or different tag, a dirty checkout, and a RuneLite version override for release builds. A push of the version tag also runs the Windows CI job against that tag; its `hapticscape-windows-packages-<commit>` artifact contains all four versioned release files. Confirm `runeLiteVersion` in LumBridge's `app/release.json` inside the ZIP, then upload the ZIPs and their checksums from that CI artifact (or a verified local tag build). Use a new patch version when correcting an already published release so existing installations receive the update.

The output is written under `build\distribution`:

```text
HapticScape-Windows-x64-X.Y.Z.zip
HapticScape-Windows-x64-X.Y.Z.zip.sha256
LumBridge-Windows-x64-X.Y.Z.zip
LumBridge-Windows-x64-X.Y.Z.zip.sha256
```

The desktop packager runs tests, builds the standalone JAR, creates a trimmed Java runtime, compiles `HapticScape.exe` and the updater, collects licenses, and creates the ZIP and checksum.

The LumBridge packager runs bridge tests, verifies the packaged RuneLite client, compiles `LumBridge.exe`, records bridge provenance, collects licenses, and creates a separate ZIP and checksum.

Test the packaged executables before publishing:

```text
build\windows-package\HapticScape\HapticScape.exe
build\bridge-windows-package\LumBridge\LumBridge.exe
```

For an updater-compatible release, keep the Git tag, embedded HapticScape version, HapticScape ZIP version, LumBridge ZIP version, and checksum filenames on the same version.

## Run two local HapticScape clients

Named profiles isolate settings, locks, Discord credentials, and saved unlock keys.

```powershell
.\HapticScape.exe --profile subject --gameplay-port 41713
.\HapticScape.exe --profile controller --gameplay-port 41714
```

Profiles are stored under:

```text
%LOCALAPPDATA%\HapticScape\profiles\<name>
```

The standard gameplay bridge publishes to `41713`, so use that port for the client that should receive gameplay events.

## Self-host the Remote Play relay

The repository includes the Cloudflare Worker and Durable Object relay under `remote-relay`.

Requirements:

- Node.js
- Wrangler
- Cloudflare account

Basic deployment:

```powershell
cd remote-relay
Copy-Item wrangler.toml.example wrangler.toml
npm install
npx wrangler deploy
```

Use the resulting secure WebSocket URL in HapticScape:

```text
wss://your-worker.workers.dev/relay
```

See [`remote-relay/README.md`](remote-relay/README.md) for relay and Discord setup.

## Device safety

- Follow the device manufacturer's operating, charging, cleaning, and safety instructions.
- Start with low intensity and short durations.
- Test local controls before enabling Remote Play.
- Use Remote Play only with someone you trust.
- Keep **Stop all**, **Emergency Off**, and Intiface's own stop control available.
- Stop using the device immediately if output is painful, unexpected, or the hardware behaves incorrectly.

HapticScape cannot determine a safe or comfortable output level for a particular person or device.

## License and support

HapticScape is distributed under the terms in [LICENSE](LICENSE). Windows release bundles include applicable third-party notices and licenses.

RuneLite, Old School RuneScape, Jagex, Intiface, Buttplug, Windows, Discord, and related names and trademarks belong to their respective owners.

Report reproducible problems through [GitHub Issues](https://github.com/ashy0019/HapticScape/issues).
