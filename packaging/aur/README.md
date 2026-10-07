# Arch/CachyOS launcher package

`hapticscape-launcher-beta-bin` packages the published RC launcher only. The
launcher downloads HapticScape, LumBridge and Java on first launch. It uses the
same per-user account and app data as other installs. Pacman/AUR owns launcher
updates; the launcher does not overwrite package-managed files under `/opt`.

Install the launcher, then choose whether to install Intiface:

```sh
bash packaging/aur/install.sh
```

For only the launcher, run `makepkg -si` in
`packaging/aur/hapticscape-launcher-beta-bin`.

Optional Intiface setup, run separately as your normal user:

```sh
bash packaging/aur/setup-intiface.sh
```

The helper asks first, then offers the existing binary or source-build Intiface
AUR package through paru or yay. It retains their normal review and installation
prompts. Declining makes no changes. The AUR source package can trail the binary
release; the helper does not promise a build of the newest upstream tag.
See [Intiface's Arch instructions](https://github.com/intiface/intiface-central#how-to-get-binariesinstallers).

An existing KWallet with Secret Service enabled can supply account storage; GNOME
Keyring is an optional alternative. PipeWire is optional for music capture.

If migrating from a `.run` install, its per-user desktop entry can shadow the
system package entry. Remove only
`~/.local/share/applications/com.hapticscape.launcher.desktop` to use the packaged
launcher from the application menu. Keep your account, app data and profiles.

## AUR publication

These files are prepared for submission, not yet listed in the AUR. Publish
`PKGBUILD`, `.SRCINFO` and `LICENSE` in the `hapticscape-launcher-beta-bin` AUR repository
using an AUR account and registered SSH key. Keep the optional setup helper in
this GitHub repository; PKGBUILD/package hooks must not prompt or invoke an AUR
helper during a package build/install.

The beta package is separate from the future stable `hapticscape-launcher-bin`.
For each published beta, update `_release`, `pkgver` (no hyphen), the pinned
SHA-256, and `.SRCINFO`; reset `pkgrel` to 1. Regenerate with
`makepkg --printsrcinfo > .SRCINFO`. Only x86_64 is included until an ARM64
release artifact is available.
