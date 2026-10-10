#!/usr/bin/env bash
set -euo pipefail
if (( EUID == 0 )); then
    echo 'Run this helper as your normal user.' >&2
    exit 1
fi
if ! [[ -t 0 ]]; then
    echo 'Run this helper in a terminal to choose whether to install Intiface.' >&2
    exit 1
fi
if pacman -Q intiface-central-bin >/dev/null 2>&1 || pacman -Q intiface-central >/dev/null 2>&1; then
    echo 'Intiface Central is already installed.'
    exit 0
fi
read -r -p 'Install Intiface Central for haptic devices? [y/N] ' answer || exit 0
case $answer in y|Y|yes|YES) ;; *) exit 0;; esac
printf '1) Release binary (recommended)\n2) Build the AUR source package\n'
read -r -p 'Choose [1/2, default 1]: ' choice || exit 0
case $choice in
    ''|1) package=intiface-central-bin ;;
    2) package=intiface-central; echo 'The source package may lag the latest release.' ;;
    *) echo 'Invalid selection; nothing installed.' >&2; exit 1 ;;
esac
if command -v paru >/dev/null 2>&1; then
    helper=paru
elif command -v yay >/dev/null 2>&1; then
    helper=yay
else
    echo "Install $package with your preferred AUR tool; paru or yay is needed by this helper." >&2
    exit 1
fi
# Keep the AUR helper's review, build and pacman confirmation prompts.
exec "$helper" -S --needed "$package"
