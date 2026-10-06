# Launcher assets

`hapticscape.png` and `lumbridge.png` are lossless PNG exports of the existing
repository icons `hapticscape.ico` and `lumbridge.ico` (256 px frames).

`lumbridge-background.png` comes from a Lumbridge courtyard gameplay screenshot
supplied by the project owner. The built-in image generation tool removed the
floating ground-item names and prices while preserving the scene, including the
character on the right and her ":3" speech text. CSS applies right-aligned cropping,
blur, and dark shading for display.

Edit prompt: remove only the upper-left ground-item names and GE/HA prices;
restore the underlying castle textures; preserve all characters, original game
style and framing, especially the right-side character and yellow ":3" text.
Do not add global shading or blur; the launcher supplies those effects.

RuneScape imagery belongs to Jagex Ltd. This screenshot is not represented as
Creative Commons or covered by the application's code license. The previous
third-party background image and its attribution have been removed.

`launcher.png` combines those two existing icons. Its editable SVG composition
is in `src-tauri/icons/launcher.svg`, with PNG and ICO exports for native icons.
