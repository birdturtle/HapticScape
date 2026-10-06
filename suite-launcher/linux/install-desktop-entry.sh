#!/usr/bin/env sh
set -eu

# Development entry; packaged builds will supply their installed executable path.
launcher_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
launcher_binary=${1:-"$launcher_root/src-tauri/target/debug/hapticscape-launcher"}
launcher_binary=$(realpath "$launcher_binary")
test -x "$launcher_binary"
launcher_data_home=${XDG_DATA_HOME:-"$HOME/.local/share"}
launcher_applications="$launcher_data_home/applications"
launcher_icons="$launcher_data_home/icons/hicolor/256x256/apps"
mkdir -p "$launcher_applications" "$launcher_icons"
cp "$launcher_root/src-tauri/icons/icon.png" "$launcher_icons/com.hapticscape.launcher.png"
# Escape Desktop Entry string values and the quoted Exec executable.
launcher_exec=$(printf '%s' "$launcher_binary" | sed 's/\\/\\\\/g; s/"/\\"/g; s/`/\\`/g; s/\$/\\$/g; s/%/%%/g')
cat > "$launcher_applications/com.hapticscape.launcher.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=HapticScape Launcher
Exec="$launcher_exec"
Icon=com.hapticscape.launcher
StartupWMClass=com.hapticscape.launcher
Terminal=false
Categories=Game;Utility;
EOF
if command -v update-desktop-database >/dev/null 2>&1; then
    update-desktop-database "$launcher_applications"
fi
if command -v kbuildsycoca6 >/dev/null 2>&1; then
    kbuildsycoca6 --noincremental
fi
