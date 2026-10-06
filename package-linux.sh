#!/usr/bin/env bash
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
version=${1:?Usage: ./package-linux.sh VERSION}
[[ $version =~ ^[0-9]+(\.[0-9]+){2}(-[0-9A-Za-z.-]+)?$ ]] || { echo 'Use a semantic version such as 0.0.0-ci.' >&2; exit 1; }
if [[ $version != 0.0.0-ci ]]; then
    [[ $(git -C "$root" rev-parse HEAD) == $(git -C "$root" rev-parse "v$version^{commit}") ]] || { echo 'Package from the matching release tag.' >&2; exit 1; }
    [[ -z $(git -C "$root" status --porcelain) ]] || { echo 'Release checkout must be clean.' >&2; exit 1; }
fi
case $(uname -m) in x86_64) arch=x64; deb_arch=amd64;; aarch64) arch=arm64; deb_arch=arm64;; *) echo 'Unsupported architecture.' >&2; exit 1;; esac
cd "$root"
bash ./gradlew --no-daemon "-PappVersion=$version" test verifyStandaloneArtifact collectRuntimeLicenses :runelite-bridge-client:test :runelite-bridge-client:verifyBridgeClientJar :runelite-bridge-client:collectRuntimeLicenses
cd suite-launcher
npm ci
npm run check
cargo test -j2 --manifest-path src-tauri/Cargo.toml
config=$(mktemp --suffix=.json)
trap 'rm -f -- "$config"' EXIT
printf '{"version":"%s"}\n' "$version" > "$config"
npx tauri build --no-bundle --config "$config"
cd "$root"
mkdir -p build/distribution
stage=$(mktemp -d "$root/build/linux-package.XXXXXXXX")
trap 'rm -f -- "$config"; rm -rf -- "$stage"' EXIT
suite="$stage/HapticScape"
mkdir -p "$suite/launcher" "$suite/app" "$suite/LumBridge/app" "$suite/licenses/hapticscape" "$suite/licenses/lumbridge"
cp suite-launcher/src-tauri/target/release/hapticscape-launcher "$suite/launcher/"
cp build/libs/hapticscape-desktop.jar "$suite/app/"
cp runelite-bridge-client/build/libs/lumbridge.jar "$suite/LumBridge/app/"
cp -a build/generated/runtime-licenses/. "$suite/licenses/hapticscape/"
cp -a runelite-bridge-client/build/generated/runtime-licenses/. "$suite/licenses/lumbridge/"
cp LICENSE "$suite/licenses/" 
cp suite-launcher/ui/assets/CREDITS.md "$suite/licenses/launcher-assets.md"
cp suite-launcher/src-tauri/icons/icon.png "$suite/icon.png"
cp packaging/linux/install.sh packaging/linux/README-LINUX.md "$suite/"
printf '%s\n' "$version" > "$suite/VERSION"
printf '{"version":"%s","architecture":"%s","repository":"birdturtle/HapticScape"}\n' "$version" "$arch" > "$suite/app/release.json"
printf '{"schemaVersion":1,"version":"%s","repository":"birdturtle/HapticScape"}\n' "$version" > "$suite/app/suite.json"
runtime_modules=$(paste -sd, packaging/java-runtime-modules.txt)
jlink --add-modules "$runtime_modules" --strip-debug --no-header-files --no-man-pages --compress=2 --output "$suite/runtime"
"$suite/runtime/bin/java" -jar "$suite/LumBridge/app/lumbridge.jar" --verify-runtime
chmod +x "$suite/install.sh" "$suite/launcher/hapticscape-launcher"
archive="$root/build/distribution/HapticScape-Linux-$arch-$version.tar.gz"
tar -C "$stage" -czf "$archive" HapticScape
(cd build/distribution && sha256sum "$(basename "$archive")" > "$(basename "$archive").sha256")
installer="$root/build/distribution/HapticScape-Linux-$arch-$version.run"
payload_hash=$(sha256sum "$archive" | cut -d ' ' -f1)
sed "s/__PAYLOAD_SHA256__/$payload_hash/" packaging/linux/self-extract.sh > "$installer"
cat "$archive" >> "$installer"
chmod +x "$installer"
(cd build/distribution && sha256sum "$(basename "$installer")" > "$(basename "$installer").sha256")
if command -v dpkg-deb >/dev/null; then
    deb="$stage/deb"
    mkdir -p "$deb/DEBIAN" "$deb/opt/hapticscape" "$deb/usr/bin" "$deb/usr/share/applications" "$deb/usr/share/icons/hicolor/256x256/apps"
    cp -a "$suite/." "$deb/opt/hapticscape/"
    ln -s /opt/hapticscape/launcher/hapticscape-launcher "$deb/usr/bin/hapticscape-launcher"
    cp "$suite/icon.png" "$deb/usr/share/icons/hicolor/256x256/apps/com.hapticscape.launcher.png"
    cat > "$deb/usr/share/applications/com.hapticscape.launcher.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=HapticScape Launcher
Exec=/opt/hapticscape/launcher/hapticscape-launcher
Icon=com.hapticscape.launcher
StartupWMClass=com.hapticscape.launcher
Terminal=false
Categories=Game;Utility;
DESKTOP
    cat > "$deb/DEBIAN/control" <<CONTROL
Package: hapticscape-launcher
Version: ${version/-/~}
Architecture: $deb_arch
Maintainer: birdturtle <321293670+birdturtle@users.noreply.github.com>
Depends: libwebkit2gtk-4.1-0, libgtk-3-0, libsecret-1-0, libx11-6, libxext6, libxi6, libxrender1, libxtst6, libfontconfig1, libasound2 | libasound2t64
Recommends: pipewire-bin, gnome-keyring | kwalletmanager
Description: HapticScape launcher with bundled HapticScape, LumBridge and Java
CONTROL
    dpkg-deb --root-owner-group --build "$deb" "$root/build/distribution/hapticscape-launcher_${version/-/~}_${deb_arch}.deb"
    (cd build/distribution && sha256sum "hapticscape-launcher_${version/-/~}_${deb_arch}.deb" > "hapticscape-launcher_${version/-/~}_${deb_arch}.deb.sha256")
fi
printf '{"schemaVersion":1,"version":"%s"}\n' "$version" > "$root/build/distribution/HapticScape-Suite-$version.json"
printf 'Packages created in %s/build/distribution\n' "$root"
