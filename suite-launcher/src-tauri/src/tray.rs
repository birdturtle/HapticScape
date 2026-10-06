use tauri::{
    menu::{Menu, MenuItem},
    tray::TrayIconBuilder,
    Manager,
};

pub fn setup(app: &tauri::App) -> tauri::Result<()> {
    let restore = MenuItem::with_id(app, "restore", "Restore", true, None::<&str>)?;
    let exit = MenuItem::with_id(app, "exit", "Exit", true, None::<&str>)?;
    let menu = Menu::with_items(app, &[&restore, &exit])?;
    let mut tray = TrayIconBuilder::with_id("launcher")
        .tooltip("HapticScape Launcher")
        .menu(&menu)
        .show_menu_on_left_click(true)
        .on_menu_event(|app, event| match event.id.as_ref() {
            "restore" => {
                if let Some(window) = app.get_webview_window("main") {
                    let _ = window.unminimize();
                    let _ = window.show();
                    let _ = window.set_focus();
                }
            }
            "exit" => {
                // Installation must finish before the launcher hands off to its helper.
                if !app
                    .state::<crate::AppState>()
                    .updating
                    .load(std::sync::atomic::Ordering::SeqCst)
                {
                    app.exit(0);
                }
            }
            _ => {}
        });
    if let Some(icon) = app.default_window_icon() {
        tray = tray.icon(icon.clone());
    }
    tray.build(app)?;
    Ok(())
}

// A successful AppIndicator allocation does not imply that a desktop hosts it.
// Keep the window accessible when no StatusNotifier watcher is present.
pub fn available(app: &tauri::AppHandle) -> bool {
    if app.tray_by_id("launcher").is_none() {
        return false;
    }
    #[cfg(target_os = "linux")]
    {
        use gtk::glib::variant::ToVariant;
        gtk::gio::bus_get_sync(gtk::gio::BusType::Session, None::<&gtk::gio::Cancellable>)
            .ok()
            .and_then(|bus| {
                bus.call_sync(
                    Some("org.freedesktop.DBus"),
                    "/org/freedesktop/DBus",
                    "org.freedesktop.DBus",
                    "NameHasOwner",
                    Some(&("org.kde.StatusNotifierWatcher",).to_variant()),
                    None,
                    gtk::gio::DBusCallFlags::NONE,
                    1000,
                    None::<&gtk::gio::Cancellable>,
                )
                .ok()
            })
            .and_then(|reply| reply.get::<(bool,)>())
            .map(|(present,)| present)
            .unwrap_or(false)
    }
    #[cfg(not(target_os = "linux"))]
    {
        true
    }
}
