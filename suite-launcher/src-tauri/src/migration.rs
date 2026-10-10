use crate::processes::Settings;
use std::{fs, io::Write, path::PathBuf};

fn request(args: &[String]) -> Result<Option<(PathBuf, String)>, String> {
    let value = |key: &str| {
        args.iter()
            .position(|a| a == key)
            .and_then(|i| args.get(i + 1))
            .cloned()
    };
    match (value("--update-ready-file"), value("--update-ready-token")) {
        (None, None) => Ok(None),
        (Some(path), Some(token)) => {
            let path = PathBuf::from(path);
            let valid_parent = path
                .parent()
                .and_then(|p| p.file_name())
                .and_then(|p| p.to_str())
                .map(|p| p.starts_with("HapticScape-update-"))
                .unwrap_or(false);
            if !path.is_absolute()
                || !valid_parent
                || path.file_name().and_then(|s| s.to_str()) != Some("launcher-ready")
                || token.len() != 32
                || !token.bytes().all(|c| c.is_ascii_hexdigit())
            {
                return Err("Invalid updater startup request.".into());
            }
            Ok(Some((path, token)))
        }
        _ => Err("Incomplete updater startup request.".into()),
    }
}
pub fn acknowledge() -> Result<(), String> {
    let Some((path, token)) = request(&std::env::args().skip(1).collect::<Vec<_>>())? else {
        return Ok(());
    };
    let executable =
        std::env::current_exe().map_err(|_| "Cannot locate the installed launcher.".to_string())?;
    let root =
        Settings::installed_root(&executable).ok_or("Cannot locate the updated installation.")?;
    let settings = Settings::for_install(&root);
    if !crate::components::is_bootstrap(&root)
        && (!PathBuf::from(&settings.hapticscape_jar).is_file()
            || !PathBuf::from(&settings.lumbridge_jar).is_file()
            || !PathBuf::from(&settings.java_path).is_file())
    {
        return Err("The updated installation is missing a required component.".into());
    }
    let mut file = fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&path)
        .map_err(|_| "Cannot confirm launcher startup.".to_string())?;
    file.write_all(token.as_bytes())
        .and_then(|_| file.sync_all())
        .map_err(|_| "Cannot confirm launcher startup.".to_string())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn updater_requests_are_complete_and_bound_to_a_staging_marker() {
        assert!(request(&[]).unwrap().is_none());
        let root = std::env::temp_dir().join("HapticScape-update-test");
        let valid = vec![
            "--update-ready-file".into(),
            root.join("launcher-ready").to_string_lossy().into(),
            "--update-ready-token".into(),
            "a".repeat(32),
        ];
        assert!(request(&valid).unwrap().is_some());
        assert!(request(&valid[..2]).is_err());
        let mut bad = valid.clone();
        bad[1] = root.join("other-file").to_string_lossy().into();
        assert!(request(&bad).is_err());
        let mut bad = valid;
        bad[3] = "not-a-token".into();
        assert!(request(&bad).is_err());
    }
}
