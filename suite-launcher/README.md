# HapticScape launcher

Tauri 2 front door for HapticScape and LumBridge. This development prototype
includes a native desktop UI, configurable component paths, separate Java child
processes, immutable launch copies, and an experimental Jagex account flow.
Packaged builds include both apps and Java, with verified suite updates and rollback.

## Develop

Install Node.js 22+, Rust, and the [Tauri platform prerequisites](https://v2.tauri.app/start/prerequisites/).
Linux requires WebKitGTK 4.1, GTK 3, and Secret Service; Windows requires the
Microsoft build tools and WebView2. Java and the two existing application JARs
are also needed for application launches.

From this directory:

```sh
npm ci
npm run check
cargo test --manifest-path src-tauri/Cargo.toml
npm run dev
```

Default JAR locations point into this checkout's `build/libs` and
`runelite-bridge-client/build/libs`. Overrides for those paths and the Java executable are under
Settings → Troubleshooting. The prototype uses gameplay port 41713 and the local profile `launcher`.
Close an existing client using that port before testing launcher app starts.
Closing the window hides the launcher to its tray. Tray Restore reopens it;
Tray Exit quits the launcher and leaves its Java applications running.
Without a usable tray host, closing the window exits normally.

## Account proof

Accounts opens an isolated Jagex webview. Complete login there, select a character,
then use Play. No credentials need to be pasted into chat or exported to a file.
The flow is based on protocol research from RSClient, independently implemented;
the user verified successful Linux sign-in and Play launch. Official third-party
support and Windows live sign-in are not established.

Successful sign-in automatically adds an account to the launcher's persistent
account list. Adding more accounts preserves the others; signing into the same
account updates its session. Account and character selections persist. Remove
account removes only the selected account. Accounts load automatically at startup,
without a Remember/Restore step or a setting to enable persistence.

Accounts are stored in an AES-256-GCM envelope in the platform configuration
directory, with a random key in Secret Service / Windows Credential Manager.
A locked or unavailable wallet reports an error rather than falling back to
plaintext or replacing an unreadable account list. The previous single-account
envelope migrates automatically. Removing an account does not terminate a running
game or claim remote revocation. Existing credential files are untouched.

Current limits: no automatic token refresh or remote revocation and no process
adoption after launcher restart. An expired game session needs reauthentication;
its account entry remains until explicitly removed. Windows launch and account
tests remain required.

See [the full design](../docs/unified-launcher-design.md) for the delivery plan.

Settings provides persistent preferences for hiding to the tray after Play, checking
releases on startup, and including beta updates. All default to off.
Beta updates include published prereleases; stable updates exclude them. Preferences can be saved while Java
apps run. Path overrides remain restricted while managed apps are running.

On Linux/Wayland, register the development launcher and taskbar icon with
`sh linux/install-desktop-entry.sh` from this directory. The launcher uses
`com.hapticscape.launcher` as its GTK application ID, matching the desktop entry.
This entry points to the development binary; packaged installers will provide
their installed path. The existing HapticScape icon is embedded for native windows
and the Windows executable. Characters are selectable avatar rows with scrolling;
the account dropdown remains separate and Play sits at the lower right.
