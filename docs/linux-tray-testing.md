# Linux desktop tray

Linux exports `org.kde.StatusNotifierItem` at `/StatusNotifierItem` and
`com.canonical.dbusmenu` at `/Menu` on a private session-bus connection, using
GLib/GIO through the existing JNA dependency. KDE renders the menu directly;
there is no AWT XEmbed icon or Swing popup on Linux. Windows retains its AWT tray.

Right-click offers **Open HapticScape** and **Exit**. Activation restores the
window. Menu actions dispatch onto the Swing event thread. Exit passes through
the existing protected-exit dialog and audit flow. The window close button hides
to the tray when a host is registered; otherwise it requests exit. If the host
or watcher disappears, the window is restored. A returning watcher is registered
again. Each profile uses a separate connection and its profile name in the title.

Requirements: Java 11+, GLib/GIO and GObject, a desktop session bus, and a
compatible `org.kde.StatusNotifierWatcher` with a registered host. GTK and
libappindicator are not required. GNOME may require a StatusNotifier extension;
without a host the app keeps the ordinary window available. Startup is bounded
to five seconds, D-Bus calls to 1.5 seconds, and shutdown cancels pending calls.
Native resources are released on the tray worker without joining from Swing.

The icon is sent as ARGB pixmap bytes from the bundled PNG. No temporary icon
files or shell commands are needed. Desktop notifications use the standard
`org.freedesktop.Notifications` service when the tray backend is running.

## Automated checks

```bash
./gradlew test verifyLinuxTray verifyLinuxKeyringBindings --offline
```

`verifyLinuxTray` requires `dbus-run-session` and native GLib/GIO libraries on
Linux. It starts a disposable bus and a simulated StatusNotifier watcher, exports
the real native interfaces, reads the icon/menu, exercises menu protocol methods,
checks Open/Exit callbacks run on Swing, and checks missing hosts, host/watcher
recovery, independent profile registration, repeated close and connection cleanup.
GLib critical errors are fatal in this check. It does not access the user's
desktop bus or wallet and is skipped on other operating systems.

## KDE acceptance

After rebuilding, launch a test profile:

```bash
java -jar build/libs/hapticscape-desktop.jar --profile linux-crypto-controller --gameplay-port 41714
```

Check right-click displays both actions, Open restores a hidden window, X hides
without exiting, and Exit terminates the process and removes its icon. Test two
profiles together to confirm both can be controlled independently. A profile with
protected exit must still show its password/recovery dialog through tray Exit.
These visual/desktop and protected-exit checks remain user acceptance requirements;
the isolated protocol test alone does not establish them.

Acceptance checkpoint (2026-10-06): on the user's CachyOS KDE/Wayland session,
the `linux-crypto-controller` profile displayed the native tray menu and the user
confirmed **Exit** worked; the process terminated cleanly. Open/restore,
close-to-tray, two visible profile icons, and protected Exit still need separate
desktop acceptance. Automated verification passed all 512 main tests (no skips),
the isolated tray protocol check and the keyring bindings check; the unchanged
27-test bridge suite was up to date.

References: [KDE tray compatibility bug](https://bugs.kde.org/show_bug.cgi?id=498824),
[StatusNotifierItem specification](https://specifications.freedesktop.org/status-notifier-item/latest/status-notifier-item.html),
[GIO object registration](https://docs.gtk.org/gio/method.DBusConnection.register_object.html).
The exported KDE flavor matches the installed KDE interface XML under
`/usr/share/dbus-1/interfaces/kf6_org.kde.StatusNotifierItem.xml` and
`kf6_org.kde.StatusNotifierWatcher.xml`.
