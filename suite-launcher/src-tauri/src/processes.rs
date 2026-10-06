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

#[derive(Clone, Default, Serialize, Deserialize, Debug, PartialEq)]
#[serde(rename_all = "camelCase", default)]
pub struct Preferences {
    pub minimize_on_play: bool,
    pub check_updates_on_startup: bool,
    pub include_beta_updates: bool,
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
        settings.preferences.include_beta_updates = true;
        let restored: Settings =
            serde_json::from_slice(&serde_json::to_vec(&settings).unwrap()).unwrap();
        assert_eq!(restored.preferences, settings.preferences);
        assert_eq!(restored.profile, "custom");
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
        settings.preferences.minimize_on_play = true;
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
        assert!(settings.preferences.minimize_on_play);
        settings.hapticscape_jar = "/custom/client.jar".into();
        settings.rebase_managed_paths(&old);
        assert_eq!(settings.hapticscape_jar, "/custom/client.jar");
    }
}
