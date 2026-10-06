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
}

#[derive(Clone, Default, Serialize, Deserialize, Debug, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct Preferences {
    pub minimize_on_play: bool,
    pub check_updates_on_startup: bool,
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
        }
    }
}

impl Settings {
    pub fn validate(&self) -> Result<(), String> {
        if self.profile.is_empty()
            || self.profile.len() > 32
            || !self.profile.as_bytes()[0].is_ascii_alphanumeric()
            || !self
                .profile
                .bytes()
                .all(|c| c.is_ascii_alphanumeric() || b"._-".contains(&c))
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
    // Child applications own their exit lifecycle. No kill-on-drop or launcher close hook.
    command
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    command
        .spawn()
        .map_err(|_| "Java could not start. Check the executable in Settings.".into())
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
        settings.preferences.minimize_on_play = true;
        let restored: Settings =
            serde_json::from_slice(&serde_json::to_vec(&settings).unwrap()).unwrap();
        assert_eq!(restored.preferences, settings.preferences);
        assert_eq!(restored.profile, "custom");
    }
    #[test]
    fn profile_validation_rejects_paths_and_option_injection() {
        for profile in ["", "../wallet", "--profile", "a b", "x/y"] {
            let mut settings = Settings::default();
            settings.profile = profile.into();
            assert!(settings.validate().is_err());
        }
        let mut settings = Settings::default();
        settings.profile = "linux-test.1".into();
        assert!(settings.validate().is_ok());
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
}
