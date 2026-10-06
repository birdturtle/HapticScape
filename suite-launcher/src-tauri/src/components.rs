use crate::{
    processes::{self, Settings},
    updates, AppState,
};
use std::{
    fs,
    path::{Path, PathBuf},
    sync::{atomic::Ordering, Arc, Mutex},
};
use tauri::Manager;

pub struct State {
    pub root: Option<PathBuf>,
    pub version: String,
    pub message: Mutex<String>,
}
pub fn is_bootstrap(root: &Path) -> bool {
    let read = |name: &str| -> Option<serde_json::Value> {
        let path = root.join("app").join(name);
        if fs::metadata(&path).ok()?.len() > 4096 {
            return None;
        }
        serde_json::from_slice(&fs::read(path).ok()?).ok()
    };
    match (
        read("bootstrap.json"),
        read("suite.json"),
        read("release.json"),
    ) {
        (Some(bootstrap), Some(suite), Some(release)) => {
            bootstrap["schemaVersion"] == 1
                && suite["schemaVersion"] == 1
                && bootstrap["version"]
                    .as_str()
                    .is_some_and(|v| updates::version(v).is_ok())
                && bootstrap["version"] == suite["version"]
                && bootstrap["version"] == release["version"]
        }
        _ => false,
    }
}
impl State {
    pub fn discover(app: &tauri::AppHandle, installed: Option<&Path>) -> Result<Self, String> {
        let version = app.package_info().version.to_string();
        if let Some(installed) = installed {
            if installed.join("app/bootstrap.json").exists()
                && (!is_bootstrap(installed)
                    || serde_json::from_slice::<serde_json::Value>(
                        &fs::read(installed.join("app/bootstrap.json"))
                            .map_err(|_| "Cannot read launcher installation.")?,
                    )
                    .map_err(|_| "Invalid launcher installation.")?["version"]
                        != version)
            {
                return Err(
                    "The launcher installation metadata is invalid. Reinstall the launcher.".into(),
                );
            }
        }
        let root = if installed.is_some_and(is_bootstrap) {
            Some(
                app.path()
                    .app_data_dir()
                    .map_err(|_| "Cannot locate application downloads.")?
                    .join("components")
                    .join(&version),
            )
        } else {
            None
        };
        Ok(Self {
            root,
            version,
            message: Mutex::new(String::new()),
        })
    }
    pub fn needed(&self) -> bool {
        self.root.as_ref().is_some_and(|p| !ready(p, &self.version))
    }
}
fn ready(root: &Path, version: &str) -> bool {
    let settings = Settings::for_install(root);
    !fs::symlink_metadata(root).is_ok_and(|m| m.file_type().is_symlink())
        && fs::read_to_string(root.join(".components-ready"))
            .ok()
            .as_deref()
            == Some(version)
        && Path::new(&settings.hapticscape_jar).is_file()
        && Path::new(&settings.lumbridge_jar).is_file()
        && Path::new(&settings.java_path).is_file()
}
pub fn use_managed_paths(settings: &mut Settings, root: &Path, downloaded: bool) {
    let bundle = Settings::for_install(root);
    settings.hapticscape_jar = bundle.hapticscape_jar;
    settings.lumbridge_jar = bundle.lumbridge_jar;
    settings.java_path = bundle.java_path;
    settings.managed_components = downloaded;
}
pub fn bundled_paths(settings: &Settings) -> bool {
    let jar = Path::new(&settings.hapticscape_jar);
    let Some(root) = jar.parent().and_then(Path::parent) else {
        return false;
    };
    let managed = Settings::for_install(root);
    root.join("app/suite.json").is_file()
        && settings.hapticscape_jar == managed.hapticscape_jar
        && settings.lumbridge_jar == managed.lumbridge_jar
        && settings.java_path == managed.java_path
}
fn activate(staged: &Path, target: &Path, version: &str) -> Result<(), String> {
    let parent = target
        .parent()
        .ok_or("Invalid application download directory.")?;
    if ready(target, version) {
        return Ok(());
    }
    if fs::symlink_metadata(target).is_ok_and(|m| !m.is_dir() || m.file_type().is_symlink()) {
        return Err("The application download directory is invalid.".into());
    }
    fs::write(staged.join(".components-ready"), version)
        .map_err(|_| "Cannot finish application installation.")?;
    let backup = parent.join(format!(
        ".components-repair-{:032x}",
        rand::random::<u128>()
    ));
    let existed = target.exists();
    if existed {
        fs::rename(target, &backup).map_err(|_| "Cannot prepare application repair.")?;
    }
    if fs::rename(staged, target).is_err() {
        if existed {
            let _ = fs::rename(&backup, target);
        }
        return Err("Cannot activate application downloads. Try again.".into());
    }
    if existed {
        let _ = fs::remove_dir_all(backup);
    }
    Ok(())
}
pub async fn install(app: tauri::AppHandle) -> Result<(), String> {
    let state = app.state::<AppState>();
    let root = state
        .components
        .root
        .clone()
        .ok_or("This installation already includes its applications.")?;
    if !state.components.needed() {
        return Ok(());
    }
    if state.updating.swap(true, Ordering::SeqCst) {
        return Err("An installation is already in progress.".into());
    }
    struct Reset<'a>(&'a std::sync::atomic::AtomicBool);
    impl Drop for Reset<'_> {
        fn drop(&mut self) {
            self.0.store(false, Ordering::SeqCst);
        }
    }
    let _reset = Reset(&state.updating);
    *state.components.message.lock().unwrap() = "Preparing downloads…".into();
    let version = state.components.version.clone();
    let result = async {
        check_apps(&app)?;
        let store = root
            .parent()
            .ok_or("Invalid application download directory.")?;
        fs::create_dir_all(store).map_err(|_| "Cannot create application download directory.")?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(store, fs::Permissions::from_mode(0o700))
                .map_err(|_| "Cannot protect application downloads.")?;
        }
        let release = updates::by_tag(&state.http, &format!("v{version}")).await?;
        let handle = app.clone();
        let progress: updates::Progress = Arc::new(move |message| {
            *handle
                .state::<AppState>()
                .components
                .message
                .lock()
                .unwrap() = message.into();
        });
        #[cfg(windows)]
        let stage_base = store.join("installed");
        #[cfg(not(windows))]
        let stage_base = store.to_path_buf();
        let staged = updates::stage_with_progress(&release, &stage_base, progress).await?;
        let temporary = staged
            .parent()
            .and_then(Path::parent)
            .map(Path::to_path_buf);
        let handle = app.clone();
        let version = version.clone();
        let result = tauri::async_runtime::spawn_blocking(move || {
            let state = handle.state::<AppState>();
            let _launch = state.launches.lock().unwrap();
            check_apps(&handle)?;
            *state.components.message.lock().unwrap() = "Installing apps…".into();
            activate(&staged, &root, &version)?;
            let mut settings = state.settings.lock().unwrap();
            if settings.managed_components {
                use_managed_paths(&mut settings, &root, true);
            }
            crate::persist_settings(&handle, &settings)
        })
        .await
        .map_err(|_| "Application installation failed.")?;
        if let Some(temporary) = temporary {
            let _ = fs::remove_dir_all(temporary);
        }
        result
    }
    .await;
    *state.components.message.lock().unwrap() = match &result {
        Ok(()) => String::new(),
        Err(error) => error.clone(),
    };
    result
}
fn check_apps(app: &tauri::AppHandle) -> Result<(), String> {
    let state = app.state::<AppState>();
    let mut children = state.processes.lock().unwrap();
    let cache = app
        .path()
        .app_cache_dir()
        .map_err(|_| "Cannot locate the launch cache.")?;
    if processes::running(&mut children.hapticscape)
        || processes::running(&mut children.lumbridge)
        || crate::port_busy()
        || updates::external_apps(&cache)?
    {
        return Err("Close HapticScape and LumBridge before installing apps.".into());
    }
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    fn fixture(root: &Path, version: &str) {
        for file in [
            "app/hapticscape-desktop.jar",
            "LumBridge/app/lumbridge.jar",
            if cfg!(windows) {
                "runtime/bin/javaw.exe"
            } else {
                "runtime/bin/java"
            },
        ] {
            let file = root.join(file);
            fs::create_dir_all(file.parent().unwrap()).unwrap();
            fs::write(file, b"fixture").unwrap();
        }
        fs::write(
            root.join("app/suite.json"),
            format!("{{\"schemaVersion\":1,\"version\":\"{version}\"}}"),
        )
        .unwrap();
    }
    #[test]
    fn bootstrap_metadata_must_agree_and_use_a_supported_schema() {
        let root = std::env::temp_dir().join(format!(
            "hapticscape-bootstrap-{:032x}",
            rand::random::<u128>()
        ));
        fs::create_dir_all(root.join("app")).unwrap();
        for file in ["bootstrap.json", "suite.json", "release.json"] {
            fs::write(
                root.join("app").join(file),
                r#"{"schemaVersion":1,"version":"3.2.0-rc.2"}"#,
            )
            .unwrap();
        }
        assert!(is_bootstrap(&root));
        fs::write(root.join("app/release.json"), r#"{"version":"3.2.0"}"#).unwrap();
        assert!(!is_bootstrap(&root));
        fs::write(root.join("app/release.json"), r#"{"version":"3.2.0-rc.2"}"#).unwrap();
        fs::write(
            root.join("app/bootstrap.json"),
            r#"{"schemaVersion":2,"version":"3.2.0-rc.2"}"#,
        )
        .unwrap();
        assert!(!is_bootstrap(&root));
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn activation_and_repair_preserve_preferences_and_do_not_reuse_other_versions() {
        let store = std::env::temp_dir().join(format!(
            "hapticscape-components-{:032x}",
            rand::random::<u128>()
        ));
        let staged = store.join("staged");
        fixture(&staged, "3.2.0-rc.2");
        let target = store.join("3.2.0-rc.2");
        activate(&staged, &target, "3.2.0-rc.2").unwrap();
        assert!(ready(&target, "3.2.0-rc.2"));
        assert!(!ready(&target, "3.2.0-rc.3"));
        let receipt = fs::metadata(target.join(".components-ready"))
            .unwrap()
            .modified()
            .unwrap();
        let repeat = store.join("repeat");
        fixture(&repeat, "3.2.0-rc.2");
        activate(&repeat, &target, "3.2.0-rc.2").unwrap();
        assert!(repeat.exists());
        assert_eq!(
            receipt,
            fs::metadata(target.join(".components-ready"))
                .unwrap()
                .modified()
                .unwrap()
        );
        let mut settings = Settings::for_install(&target);
        settings.profile = "subject".into();
        settings.preferences.include_beta_updates = true;
        use_managed_paths(&mut settings, &store.join("3.2.0-rc.3"), true);
        assert_eq!(settings.profile, "subject");
        assert!(settings.preferences.include_beta_updates);
        assert!(settings.managed_components);
        let java = Settings::for_install(&target).java_path;
        fs::remove_file(java).unwrap();
        assert!(!ready(&target, "3.2.0-rc.2"));
        fixture(&staged, "3.2.0-rc.2");
        activate(&staged, &target, "3.2.0-rc.2").unwrap();
        assert!(ready(&target, "3.2.0-rc.2"));
        fs::remove_dir_all(store).unwrap();
    }
}
