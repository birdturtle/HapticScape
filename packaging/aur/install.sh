#!/usr/bin/env bash
set -euo pipefail
if (( EUID == 0 )); then
    echo 'Run this helper as your normal user.' >&2
    exit 1
fi
if ! [[ -t 0 ]]; then
    echo 'Run this helper in a terminal.' >&2
    exit 1
fi
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
(cd "$root/hapticscape-launcher-beta-bin" && makepkg -si)
bash "$root/setup-intiface.sh"
