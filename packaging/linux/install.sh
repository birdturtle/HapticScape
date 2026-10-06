#!/usr/bin/env bash
set -euo pipefail
source_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
data_home=${XDG_DATA_HOME:-"$HOME/.local/share"}
install_root="$data_home/hapticscape"
version=$(cat "$source_root/VERSION")
[[ $version =~ ^[0-9]+(\.[0-9]+){2}(-[0-9A-Za-z.-]+)?$ ]] || { echo 'Invalid package version.' >&2; exit 1; }
for component in launcher/hapticscape-launcher app/hapticscape-desktop.jar LumBridge/app/lumbridge.jar runtime/bin/java; do
    [[ -f "$source_root/$component" ]] || { echo "Missing component: $component" >&2; exit 1; }
done
# Give a useful error before changing the user's installation.
missing=$(ldd "$source_root/launcher/hapticscape-launcher" | awk '/not found/{print $1}')
if [[ -n $missing ]]; then
    printf 'Required desktop libraries are missing:\n%s\nSee README-LINUX.md for your distribution.\n' "$missing" >&2
    exit 1
fi
mkdir -p "$install_root/releases" "$data_home/applications" "$data_home/icons/hicolor/256x256/apps"
release=$(mktemp -d "$install_root/releases/$version.XXXXXXXX")
pending="$install_root/.current-$$"
trap 'rm -f -- "$pending"' EXIT
cp -a "$source_root/." "$release/"
# Versioned directories allow an existing launcher/Java process to finish safely.
ln -s "$release" "$pending"
mv -Tf "$pending" "$install_root/current"
cp "$release/icon.png" "$data_home/icons/hicolor/256x256/apps/com.hapticscape.launcher.suite.png"
launcher_exec=$(printf '%s' "$install_root/current/launcher/hapticscape-launcher" | sed 's/\\/\\\\/g; s/"/\\"/g; s/`/\\`/g; s/\$/\\$/g; s/%/%%/g')
cat > "$data_home/applications/com.hapticscape.launcher.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=HapticScape Launcher
Exec="$launcher_exec" %u
MimeType=x-scheme-handler/hapticscape;
Icon=$install_root/current/icon.png
StartupWMClass=com.hapticscape.launcher
Terminal=false
Categories=Game;Utility;
DESKTOP
command -v update-desktop-database >/dev/null && update-desktop-database "$data_home/applications" || true
command -v kbuildsycoca6 >/dev/null && kbuildsycoca6 --noincremental >/dev/null 2>&1 || true
printf 'Installed HapticScape. Open HapticScape Launcher from your application menu.\n'

if command -v xdg-mime >/dev/null 2>&1; then
    xdg-mime default com.hapticscape.launcher.desktop x-scheme-handler/hapticscape || true
fi
