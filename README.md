# HapticScape

HapticScape is a standalone Windows and Linux application that turns Old School RuneScape gameplay events, system or application audio, and approved Remote Play actions into configurable haptic feedback and optional clicker sounds.

Haptics are sent through [Intiface Central](https://intiface.com/). Gameplay events are received through a small local bridge client.

Prebuilt releases support Windows 10 and newer and x86_64 Linux.

> [!IMPORTANT]
> HapticScape, LumBridge, and the Local Event Bridge are unofficial software. They are not endorsed by Jagex, RuneLite, Intiface, or any device manufacturer.

 <img width="1920" height="1032" alt="image" src="https://github.com/user-attachments/assets/580678a7-47d7-4c2c-8fd1-048a0f7a1a6c" />

## New Features
- Unified launcher for HapticScape, LumBridge and updates.
- Windows and Linux launcher installers with automatic app downloads.
- Jagex sign-in with saved accounts and automatic session renewal.
- Linux system and application audio capture through PipeWire.
- Encrypted remote pairing and saved unlock keys on Linux.
- Persistent exit-without-unlocking history in saved subject profiles.
- Connection links on Windows and Linux.

## HapticScape 3 components

| Component | Purpose |
| --- | --- |
| **HapticScape** | Standalone desktop application. Owns haptics, clicks, music sync, settings, Remote Play, safety controls, and the UI. |
| **Local Event Bridge** | Small RuneLite-side plugin that observes an allowlisted set of gameplay facts and publishes neutral events to HapticScape over localhost. |
| **LumBridge** | RuneLite with the Local Event Bridge built in. Installed and started by the launcher. |
| **Intiface Central** | Connects supported haptic devices and exposes them to HapticScape over the Buttplug protocol. |
| **Remote Play relay** | Optional WebSocket relay used to connect two HapticScape clients for encrypted remote sessions. |

The default gameplay bridge endpoint is `127.0.0.1:41713`. It binds only to IPv4 loopback and is not exposed to the LAN or internet.

## Install on Windows

### Requirements

- Windows 10 or newer.
- [Intiface Central](https://intiface.com/) if you want haptic device output.
- A device supported by Intiface if you want haptic output.

The launcher downloads HapticScape, LumBridge and their Java runtime automatically. An internet connection is needed for the first download.

### New installation

1. Open [HapticScape Releases](https://github.com/birdturtle/HapticScape/releases).
2. Download `HapticScape-Launcher-Windows-x64-X.Y.Z.exe` from the latest release.
3. Run it. The launcher installs for your Windows account and adds a Start menu shortcut.
4. Wait for the launcher to download and verify HapticScape, LumBridge and Java.
5. Add your Jagex account, choose a character and select **Play**.
6. Connect Intiface Central if you use haptics.

Use **HapticScape Launcher** in the Start menu thereafter. No separate LumBridge download or Java installation is needed. **Open** starts HapticScape on its own.

### SmartScreen and checksums

Windows may show a SmartScreen warning because the installer is not code-signed.
Each installer has a matching `.sha256` file on the release page.

## Install on Linux

Use an x86_64 desktop with WebKitGTK 4.1, GTK 3 and a Secret Service wallet such as GNOME Keyring or KWallet. Music sync requires PipeWire. Install [Intiface Central](https://intiface.com/) for haptic device output.

### Installer

Download `HapticScape-Linux-x64-3.2.0.run` from [the latest release](https://github.com/birdturtle/HapticScape/releases/latest), then run:

```sh
bash HapticScape-Linux-x64-3.2.0.run
```

Open **HapticScape Launcher** from the application menu. Wait for the app downloads, add your Jagex account and select **Play**.

### Debian / Ubuntu

Download the `.deb` from the release page and install it:

```sh
sudo apt install ./hapticscape-launcher_3.2.0_amd64.deb
```

## Using the launcher

- **Add account** opens Jagex sign-in. Added accounts stay saved until removed.
- Select an account and character, then **Play** to start LumBridge and HapticScape.
- **Open** starts HapticScape on its own.
- Closing the launcher hides it to the tray. Use **Restore** or **Exit** from its tray menu.
- **Hide the launcher to the tray after Play** is available in Settings.
- Exiting the launcher leaves HapticScape and LumBridge running.

No separate LumBridge download, Java installation or `credentials.properties` export is needed.

## Connect Intiface

1. Open Intiface Central and start its engine or server.
2. Scan for and connect your device.
3. Confirm the device responds to Intiface's test controls.
4. In HapticScape, use `ws://localhost:12345` and select **Connect**.
5. Test a pattern at low intensity.

Some devices ignore very low vibration values.

## Upgrading

Use **Updates** in the launcher to check for updates and install them. Close HapticScape and LumBridge before installing. Linux package-manager installations use apt, yay or paru for launcher updates.

Older Windows installations can migrate through their existing updater. Existing shortcuts open the new launcher. Accounts, profiles and pairing data are retained.

Users coming from the old 2.x RuneLite plugin should review their feedback settings after upgrading. The standalone app uses a separate settings store.

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

Music sync turns audio into haptic feedback on Windows and Linux. Choose **Entire output** for an audio output or **Application audio** for one playback application. **Default system output** follows your current default device.

Controls include:

- Smooth, Rhythmic, and Punchy response modes.
- Sensitivity.
- Minimum haptic output.
- Maximum haptic output.
- Live output meter.
- System volume and mute handling.
- Output stops when playback pauses.
- Local capture-mode, output-source, and mixer-application selection.
- Manual refresh after an output or application starts, stops, or moves.

Finite XP, alert, preview, remote pattern, and Live Forge output can temporarily take the haptic channel. Music sync resumes afterward.

Music sync does not record or upload audio. If the selected output disappears, capture stops until an available output is selected.

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
- Saved unlock keys are encrypted for your local account.
- Emergency Off, End session, Intiface controls, Remote Play permissions, Forge, Music, and developer recovery remain available.

#### Protected startup and exit

A participant can separately allow protected startup and exit requests.

A protected lock can include:

- Start HapticScape when you sign in.
- Start minimized to the tray.
- Password-protected application exit.

Protected exit requires the lock password. After 10 seconds, **Exit without password** becomes available and stops output. The controller's saved subject profile shows the count and timestamped history under **Saved unlock keys > Manage**. History persists across restarts for the lifetime of that lock.

#### Saved Unlock Keys

Controllers can keep accepted unlock keys in a local vault.

- Encrypted storage for your local account.
- Optional labels and notes.
- Copy a key when it is needed.
- Delete saved entries.

### Updates

The launcher updates HapticScape, LumBridge and Java together.

- **Check for updates** and **Install update and restart** are on the Updates page.
- Settings includes automatic checks at startup and **Include beta updates**.
- Failed updates restore the previous installation.
- Linux package-manager installations use their package manager for launcher updates.

### LumBridge

LumBridge is RuneLite with the Local Event Bridge built in. It supplies gameplay events to HapticScape while you play.

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

HapticScape settings, profiles and remote data are stored in:

| Platform | Location |
| --- | --- |
| Windows | `%LOCALAPPDATA%\HapticScape` |
| Linux | `~/.hapticscape` |

Named profiles are under `profiles/<name>` within that directory. Saved unlock keys and Discord credentials use Windows DPAPI or the Linux Secret Service wallet.

## FAQ

### Is HapticScape still a RuneLite plugin?

No. HapticScape 3 is a standalone desktop application. RuneLite integration is limited to the Local Event Bridge.

### What is LumBridge?

LumBridge is RuneLite with the Local Event Bridge built in.

### Do I need to download LumBridge manually?

No. The launcher downloads the matching LumBridge version automatically.

### Can I use normal RuneLite?

Use LumBridge for gameplay feedback. Normal RuneLite does not include the Local Event Bridge.

### Does HapticScape need Intiface if I only want clicks?

No. Click feedback works without Intiface. Intiface is only required for haptic device output.

### Can HapticScape control more than one Intiface device?

HapticScape sends compatible output to discovered devices with supported vibration or scalar actuators. Device behavior still depends on Intiface and the hardware.

### Where are HapticScape 3 settings stored?

Windows: `%LOCALAPPDATA%\HapticScape`. Linux: `~/.hapticscape`.

### Will my old 2.x plugin settings automatically become 3.x settings?

Do not assume that they will. HapticScape 3 uses its own standalone settings store. Review the configuration after migration.

### Do I need Java or RuneLite installed separately?

No. The launcher installs the required runtime and LumBridge.

### How do I sign in with a Jagex account?

Select **Add account** in the launcher. Saved sessions renew automatically. If Jagex requires another sign-in, the launcher asks you to sign in again.

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

### Does HapticScape run on Linux or macOS?

Windows and x86_64 Linux are supported. There is no macOS package.

## Troubleshooting

### An application does not start

- Open HapticScape Launcher and wait for installation to finish.
- Use **Retry installation** if a download failed.
- Check whether antivirus or SmartScreen blocked the launcher or runtime on Windows.
- On Linux, confirm the required desktop libraries and Secret Service wallet are available.
- Check **Settings > Troubleshooting** for application paths.

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
- Select **Default system output** to follow the current default, or press **Refresh** after connecting a new output device.
- For **Application audio**, start playback in the application before refreshing the list. The saved selection waits quietly if that application later closes.
- Confirm the system is not muted. On Linux, confirm PipeWire is running.
- Raise Music sensitivity and maximum intensity.
- Raise minimum intensity if the device ignores low output values.

### Remote Play does not connect

- Use the same current HapticScape release on both clients.
- Confirm both clients can reach the configured `wss://` relay.
- Generate a new connection code if the old one expired or was used.
- Check **Advanced** if either client uses a self-hosted relay.
- End incomplete sessions before retrying.

### Discord Accept does not open HapticScape

- Confirm both users linked the Discord app.
- Allow the browser to open the `hapticscape://` link.
- Run the launcher installer again to restore connection-link registration.
- Use a manual connection code if the browser blocks the link.

## Build from source

HapticScape targets Java 11 and includes the Gradle wrapper.

### Requirements

- Git
- Java 11 JDK
- Node.js 22+ and Rust for the unified launcher
- Windows and .NET Framework 4.x for Windows packaging
- Linux desktop development libraries for Linux packaging
- Intiface Central for device testing

Clone the repository:

```powershell
git clone https://github.com/birdturtle/HapticScape.git
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
.\package-all.ps1 -Version X.Y.Z -UnifiedLauncher
Get-Content .\build\bridge-windows-package\LumBridge\app\release.json
```

The packager rejects a missing or different tag, a dirty checkout, and a RuneLite version override for release builds. A push of the version tag also runs the Windows CI job against that tag; its `hapticscape-windows-packages-<commit>` artifact contains the launcher installer, internal suite payload, checksums and suite descriptor. Confirm `runeLiteVersion` in LumBridge's `app/release.json` inside the ZIP, then upload the installer, internal payload, descriptor and checksums from that CI artifact (or a verified local tag build). Use a new patch version when correcting an already published release so existing installations receive the update.

The output is written under `build\distribution`:

```text
HapticScape-Launcher-Windows-x64-X.Y.Z.exe
HapticScape-Launcher-Windows-x64-X.Y.Z.exe.sha256
HapticScape-Windows-x64-X.Y.Z.zip
HapticScape-Windows-x64-X.Y.Z.zip.sha256
HapticScape-Suite-X.Y.Z.json
```

The installer is the user download. The ZIP, suite descriptor and checksums are used for automatic downloads and older updater migration.

For Linux packaging and launcher development, see [the launcher README](suite-launcher/README.md) and [Linux packaging](packaging/linux/README-LINUX.md).

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

HapticScape is distributed under the terms in [LICENSE](LICENSE). Release packages include applicable third-party notices and licenses.

RuneLite, Old School RuneScape, Jagex, Intiface, Buttplug, Windows, Discord, and related names and trademarks belong to their respective owners.

Report reproducible problems through [GitHub Issues](https://github.com/birdturtle/HapticScape/issues).
