# Install HapticScape on Linux

On Debian/Ubuntu, open the `.deb` with your software installer, or run
`sudo apt install ./hapticscape-launcher_*_amd64.deb`. The package manager
installs the desktop dependencies too.

On other distributions, download `HapticScape-Linux-*.run` and run it with
`bash HapticScape-Linux-x64-VERSION.run` (replace VERSION with the downloaded
version, and use arm64 on ARM computers). This one file contains the full suite.

Alternatively, extract `HapticScape-Linux-*.tar.gz`, open a terminal
in the extracted `HapticScape` directory, and run `./install.sh`.
Then open **HapticScape Launcher** from your application menu. This installs
only for your user and needs no administrator password. Java, HapticScape,
and LumBridge are included; no JAR paths or Java installation are needed.

The archive needs GTK 3, WebKitGTK 4.1, libsecret, and a running Secret Service
wallet (KWallet with Secret Service enabled or GNOME Keyring). Music capture
needs PipeWire's `pw-cat` and `pw-dump` tools. For Arch/CachyOS install the
`webkit2gtk-4.1`, `gtk3`, `libsecret`, and `pipewire` packages through your
normal package manager. Debian/Ubuntu equivalents are `libwebkit2gtk-4.1-0`,
`libgtk-3-0`, `libsecret-1-0`, and `pipewire-bin`.

First launch downloads and verifies HapticScape, LumBridge and Java into the launcher’s per-user application data directory. Keep an internet connection available; use Retry installation if the download fails.

Adding a Jagex account uses the sign-in button in the launcher. Existing
HapticScape profiles, pairing and settings remain in their normal data location.

To upgrade the per-user archive installation, run the newer archive's
`install.sh` again. The application menu switches to the new version; already
running apps are allowed to finish. Previous installed versions are retained
under `~/.local/share/hapticscape/releases` for recovery. The launcher can install newer compatible suite releases from the Updates page.
Close both apps first; the launcher restarts after verification. The system
package manager handles updates for the `.deb` installation.

To uninstall the archive installation, remove `~/.local/share/hapticscape`,
`~/.local/share/applications/com.hapticscape.launcher.desktop`, and
`~/.local/share/icons/hicolor/256x256/apps/com.hapticscape.launcher.suite.png`.
If you set XDG_DATA_HOME, use that directory instead of `~/.local/share`.
This leaves your account credentials and app data alone. Uninstall the `.deb`
through your package manager.

Release builds target Ubuntu 22.04 or newer / Debian 12 or newer. A binary
built locally on a newer distribution may require newer system libraries;
use the CI-built package for broader compatibility. Only native x86_64 and
ARM64 builds are supported, and each package must match your computer.

The launcher tray requires libayatana-appindicator (or libappindicator) and a
StatusNotifier host. X hides the launcher to the tray; Restore and Exit are in
the tray menu. Exit leaves running Java applications open.
