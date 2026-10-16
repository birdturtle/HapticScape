use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    fs,
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
};

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Settings {
    pub hapticscape_jar: String,
    pub lumbridge_jar: String,
    pub java_path: String,
    pub profile: String,
    #[serde(default)]
    pub preferences: Preferences,
    #[serde(default)]
    pub managed_components: bool,
}

#[derive(Clone, Copy, Default, Serialize, Deserialize, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub enum AfterPlay {
    #[default]
    KeepOpen,
    HideToTray,
    CloseLauncher,
}
#[derive(Clone, Default, Serialize, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Preferences {
    pub after_play: AfterPlay,
    pub check_updates_on_startup: bool,
    pub include_beta_updates: bool,
}
impl<'de> Deserialize<'de> for Preferences {
    fn deserialize<D: serde::Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        #[derive(Default, Deserialize)]
        #[serde(rename_all = "camelCase", default)]
        struct Saved { after_play: Option<AfterPlay>, minimize_on_play: bool, check_updates_on_startup: bool, include_beta_updates: bool }
        let saved = Saved::deserialize(deserializer)?;
        Ok(Self { after_play: saved.after_play.unwrap_or(if saved.minimize_on_play { AfterPlay::HideToTray } else { AfterPlay::KeepOpen }), check_updates_on_startup: saved.check_updates_on_startup, include_beta_updates: saved.include_beta_updates })
    }
}

impl Default for Settings {
    fn default() -> Self {
        let root = Path::new(env!("CARGO_MANIFEST_DIR"))
            .parent()
            .unwrap()
            .parent()
            .unwrap();
        Self {
            hapticscape_jar: root
                .join("build/libs/hapticscape-desktop.jar")
                .to_string_lossy()
                .into(),
            lumbridge_jar: root
                .join("runelite-bridge-client/build/libs/lumbridge.jar")
                .to_string_lossy()
                .into(),
            java_path: std::env::var_os("JAVA_HOME")
                .map(|p| {
                    PathBuf::from(p)
                        .join("bin")
                        .join(if cfg!(windows) { "java.exe" } else { "java" })
                        .to_string_lossy()
                        .into()
                })
                .unwrap_or_else(|| "java".into()),
            profile: "launcher".into(),
            preferences: Preferences::default(),
            managed_components: false,
        }
    }
}

impl Settings {
    pub fn for_install(root: &Path) -> Self {
        Self {
            hapticscape_jar: root
                .join("app/hapticscape-desktop.jar")
                .to_string_lossy()
                .into(),
            lumbridge_jar: root
                .join("LumBridge/app/lumbridge.jar")
                .to_string_lossy()
                .into(),
            java_path: root
                .join(if cfg!(windows) {
                    "runtime/bin/javaw.exe"
                } else {
                    "runtime/bin/java"
                })
                .to_string_lossy()
                .into(),
            // No named profile means the existing application's normal data directory.
            profile: String::new(),
            preferences: Preferences::default(),
            managed_components: false,
        }
    }
    pub fn rebase_managed_paths(&mut self, root: &Path) {
        // Per-user Linux updates change the release directory. Saved preferences
        // also contain paths; those managed paths must follow the active version.
        if root
            .parent()
            .and_then(|p| p.file_name())
            .and_then(|p| p.to_str())
            != Some("releases")
        {
            return;
        }
        let bundled = Self::for_install(root);
        for (value, replacement, suffix, levels) in [
            (
                &mut self.hapticscape_jar,
                bundled.hapticscape_jar,
                "app/hapticscape-desktop.jar",
                2,
            ),
            (
                &mut self.lumbridge_jar,
                bundled.lumbridge_jar,
                "LumBridge/app/lumbridge.jar",
                3,
            ),
            (
                &mut self.java_path,
                bundled.java_path,
                if cfg!(windows) {
                    "runtime/bin/javaw.exe"
                } else {
                    "runtime/bin/java"
                },
                3,
            ),
        ] {
            let old_path = Path::new(value);
            if let Some(old_root) = old_path.ancestors().nth(levels) {
                if old_root.parent() == root.parent() && old_root.join(suffix) == old_path {
                    *value = replacement;
                }
            }
        }
    }
    pub fn installed_root(executable: &Path) -> Option<PathBuf> {
        let parent = executable.parent()?;
        [Some(parent), parent.parent()]
            .into_iter()
            .flatten()
            .find(|p| p.join("app/release.json").is_file())
            .map(Path::to_path_buf)
    }
    pub fn validate(&self) -> Result<(), String> {
        if !self.profile.is_empty()
            && (self.profile.len() > 32
                || !self.profile.as_bytes()[0].is_ascii_alphanumeric()
                || !self
                    .profile
                    .bytes()
                    .all(|c| c.is_ascii_alphanumeric() || b"._-".contains(&c)))
        {
            return Err(
                "Profile must contain 1–32 letters, numbers, dots, underscores, or hyphens.".into(),
            );
        }
        if self.java_path.trim().is_empty() {
            return Err("Choose a Java executable.".into());
        }
        Ok(())
    }
}

#[derive(Default)]
pub struct Processes {
    pub hapticscape: Option<Child>,
    pub lumbridge: Option<Child>,
    pub lumbridge_started: Option<std::time::Instant>,
    pub launch_error: Option<String>,
}

impl Processes {
    pub fn check_startup(&mut self) -> bool {
        let Some(started) = self.lumbridge_started else { return false; };
        if started.elapsed() > std::time::Duration::from_secs(20) { self.lumbridge_started = None; return false; }
        let exit = self.lumbridge.as_mut().and_then(|child| child.try_wait().ok()).flatten();
        if let Some(exit) = exit {
            self.lumbridge_started = None; self.lumbridge = None;
            if !exit.success() { self.launch_error = Some("LumBridge exited during startup. Check its launch log.".into()); return true; }
        }
        false
    }
}

pub fn running(child: &mut Option<Child>) -> bool {
    match child.as_mut().map(|c| c.try_wait()) {
        Some(Ok(None)) => true,
        _ => {
            *child = None;
            false
        }
    }
}

/// A running JVM must never read a mutable build artifact.
pub fn snapshot(source: &Path, cache: &Path) -> Result<PathBuf, String> {
    let bytes = fs::read(source)
        .map_err(|_| "The selected application JAR could not be read.".to_string())?;
    if bytes.len() < 4 || !bytes.starts_with(b"PK") {
        return Err("The selected file is not a JAR archive.".into());
    }
    let hash = format!("{:x}", Sha256::digest(&bytes));
    let folder = cache.join(hash);
    fs::create_dir_all(&folder).map_err(|_| "Cannot create the launch cache.".to_string())?;
    let jar = folder.join("app.jar");
    if !jar.exists() {
        fs::write(&jar, bytes)
            .map_err(|_| "Cannot prepare the application for launch.".to_string())?;
    }
    Ok(jar)
}

pub fn spawn(
    settings: &Settings,
    jar: &Path,
    args: &[&str],
    credentials: &[(String, String)],
    log: &Path,
) -> Result<Child, String> {
    let mut command = Command::new(&settings.java_path);
    command.arg("-ea").arg("-jar").arg(jar).args(args);
    for name in [
        "JX_SESSION_ID",
        "JX_CHARACTER_ID",
        "JX_DISPLAY_NAME",
        "JX_ACCESS_TOKEN",
        "JX_REFRESH_TOKEN",
    ] {
        command.env_remove(name);
    }
    for (name, value) in credentials {
        command.env(name, value);
    }
    let output = crate::logs::output(log)?;
    let errors = output.try_clone().map_err(|_| "Cannot open application log.")?;
    // Child applications own their exit lifecycle. No kill-on-drop or launcher close hook.
    command
        .stdin(Stdio::null())
        .stdout(Stdio::from(output))
        .stderr(Stdio::from(errors));
    command
        .spawn()
        .map_err(|_| "Java could not start. Check the executable in Settings.".into())
}

pub fn wait_for_startup(child: &mut Option<Child>, duration: std::time::Duration) -> Result<(), String> {
    let deadline = std::time::Instant::now() + duration;
    loop {
        if !running(child) { return Err("LumBridge closed during startup. Check its launch log.".into()); }
        if std::time::Instant::now() >= deadline { return Ok(()); }
        std::thread::sleep(std::time::Duration::from_millis(50));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn existing_settings_keep_component_paths_and_default_to_manual_startup() {
        let old = r#"{"hapticscapeJar":"custom/haptic.jar","lumbridgeJar":"custom/lumbridge.jar","javaPath":"java","profile":"custom"}"#;
        let mut settings: Settings = serde_json::from_str(old).unwrap();
        assert_eq!(settings.hapticscape_jar, "custom/haptic.jar");
        assert_eq!(settings.preferences, Preferences::default());
        settings.preferences.after_play = AfterPlay::HideToTray;
        settings.preferences.include_beta_updates = true;
        let restored: Settings =
            serde_json::from_slice(&serde_json::to_vec(&settings).unwrap()).unwrap();
        assert_eq!(restored.preferences, settings.preferences);
        assert_eq!(restored.profile, "custom");
    }
    #[test]
    fn after_play_migrates_old_checkboxes_and_keeps_explicit_choices() {
        let old: Preferences = serde_json::from_str(r#"{"minimizeOnPlay":true,"includeBetaUpdates":true}"#).unwrap();
        assert_eq!(old.after_play, AfterPlay::HideToTray);
        assert!(old.include_beta_updates);
        for choice in [AfterPlay::KeepOpen, AfterPlay::HideToTray, AfterPlay::CloseLauncher] {
            let value = Preferences { after_play: choice, ..Preferences::default() };
            let mut json = serde_json::to_value(&value).unwrap();
            json["minimizeOnPlay"] = serde_json::Value::Bool(true);
            assert_eq!(serde_json::from_value::<Preferences>(json).unwrap().after_play, choice);
        }
        assert!(serde_json::from_str::<Preferences>(r#"{"afterPlay":"killGames"}"#).is_err());
    }
    #[cfg(unix)]
    #[test]
    fn failed_child_keeps_its_output_and_does_not_pass_startup() {
        use std::os::unix::fs::PermissionsExt;
        let root = std::env::temp_dir().join(format!("hapticscape-child-{}", rand::random::<u64>()));
        fs::create_dir(&root).unwrap();
        let executable = root.join("fake-java");
        fs::write(&executable, "#!/bin/sh\necho startup-output\necho startup-error >&2\nexit 7\n").unwrap();
        fs::set_permissions(&executable, fs::Permissions::from_mode(0o700)).unwrap();
        let mut settings = Settings::default(); settings.java_path = executable.to_string_lossy().into();
        let log = root.join("logs/lumbridge.log");
        let mut child = Some(spawn(&settings, &root.join("fake.jar"), &[], &[], &log).unwrap());
        assert!(wait_for_startup(&mut child, std::time::Duration::from_secs(2)).is_err());
        let text = fs::read_to_string(&log).unwrap();
        assert!(text.contains("startup-output")); assert!(text.contains("startup-error"));
        fs::remove_dir_all(root).unwrap();
    }
    #[cfg(unix)]
    #[test]
    fn startup_monitor_reports_failure_but_accepts_normal_exit() {
        for code in [0, 7] {
            let mut child = Command::new("sh").args(["-c", &format!("exit {code}")]).spawn().unwrap();
            child.wait().unwrap();
            let mut processes = Processes { lumbridge: Some(child), lumbridge_started: Some(std::time::Instant::now()), ..Processes::default() };
            assert_eq!(processes.check_startup(), code != 0);
            assert_eq!(processes.launch_error.is_some(), code != 0);
            assert!(processes.lumbridge.is_none());
            assert!(!processes.check_startup());
        }
    }
    #[test]
    fn profile_validation_rejects_paths_and_option_injection() {
        for profile in ["../wallet", "--profile", "a b", "x/y"] {
            let mut settings = Settings::default();
            settings.profile = profile.into();
            assert!(settings.validate().is_err());
        }
        let mut settings = Settings::default();
        settings.profile = "linux-test.1".into();
        assert!(settings.validate().is_ok());
        settings.profile.clear();
        assert!(settings.validate().is_ok());
    }
    #[test]
    fn installed_layout_uses_bundled_components_and_the_existing_default_profile() {
        let root =
            std::env::temp_dir().join(format!("hapticscape-install-{}", rand::random::<u64>()));
        fs::create_dir_all(root.join("app")).unwrap();
        fs::write(root.join("app/release.json"), b"{}").unwrap();
        let found =
            Settings::installed_root(&root.join("launcher/HapticScapeLauncher.exe")).unwrap();
        assert_eq!(found, root);
        let settings = Settings::for_install(&found);
        assert!(settings.profile.is_empty());
        assert_eq!(
            PathBuf::from(settings.hapticscape_jar),
            root.join("app/hapticscape-desktop.jar")
        );
        assert_eq!(
            PathBuf::from(settings.lumbridge_jar),
            root.join("LumBridge/app/lumbridge.jar")
        );
        assert!(Settings::installed_root(&root.join("unrelated/deep/launcher.exe")).is_none());
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn snapshot_is_immutable_when_source_changes() {
        let dir =
            std::env::temp_dir().join(format!("hapticscape-snapshot-{}", rand::random::<u64>()));
        fs::create_dir_all(&dir).unwrap();
        let source = dir.join("source.jar");
        fs::write(&source, b"PKfirst").unwrap();
        let first = snapshot(&source, &dir.join("cache")).unwrap();
        fs::write(&source, b"PKsecond").unwrap();
        let second = snapshot(&source, &dir.join("cache")).unwrap();
        assert_ne!(first, second);
        assert_eq!(fs::read(first).unwrap(), b"PKfirst");
        fs::remove_dir_all(dir).unwrap();
    }
    #[test]
    fn saved_preferences_follow_the_active_release_and_keep_external_overrides() {
        let parent = std::env::temp_dir().join("hapticscape-rebase/releases");
        let old = parent.join("1.0.0.old");
        let new = parent.join("1.1.0.new");
        let mut settings = Settings::for_install(&old);
        settings.profile = "custom".into();
        settings.preferences.after_play = AfterPlay::HideToTray;
        settings.rebase_managed_paths(&new);
        assert_eq!(
            settings.hapticscape_jar,
            Settings::for_install(&new).hapticscape_jar
        );
        assert_eq!(
            settings.lumbridge_jar,
            Settings::for_install(&new).lumbridge_jar
        );
        assert_eq!(settings.java_path, Settings::for_install(&new).java_path);
        assert_eq!(settings.profile, "custom");
        assert_eq!(settings.preferences.after_play, AfterPlay::HideToTray);
        settings.hapticscape_jar = "/custom/client.jar".into();
        settings.rebase_managed_paths(&old);
        assert_eq!(settings.hapticscape_jar, "/custom/client.jar");
    }
}
