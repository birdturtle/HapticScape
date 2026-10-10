#!/usr/bin/env bash
set -euo pipefail
payload_line=$(awk '$0 == "__HAPTICSCAPE_ARCHIVE__" { print NR + 1; exit }' "$0")
[[ -n $payload_line ]] || { echo 'Installer payload is missing.' >&2; exit 1; }
installer_tmp=$(mktemp -d)
trap 'rm -rf -- "$installer_tmp"' EXIT
tail -n +"$payload_line" "$0" > "$installer_tmp/payload.tar.gz"
printf '%s  %s\n' '__PAYLOAD_SHA256__' "$installer_tmp/payload.tar.gz" | sha256sum -c - >/dev/null
tar -xzf "$installer_tmp/payload.tar.gz" -C "$installer_tmp"
bash "$installer_tmp/HapticScape/install.sh"
exit 0
__HAPTICSCAPE_ARCHIVE__
