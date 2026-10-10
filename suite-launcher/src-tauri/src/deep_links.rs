use crate::{processes, AppState};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    fs,
    io::Write,
    path::{Path, PathBuf},
    sync::atomic::Ordering,
    time::{Duration, SystemTime},
};
use tauri::Manager;

const INVALID: &str = "The HapticScape connection link is invalid.";
const MAX_AGE: Duration = Duration::from_secs(300);
#[derive(Serialize, Deserialize)]
struct Pending {
    link: String,
    profile: String,
}

fn validate(link: &str) -> Result<String, String> {
    if link.len() > 1024 {
        return Err(INVALID.into());
    }
    let uri = url::Url::parse(link).map_err(|_| INVALID)?;
    // Inspect the raw path/query as well: URL normalization must not broaden the Java grammar.
    let authority_path = link.split_once("://").ok_or(INVALID)?.1;
    let (authority, path) = authority_path.split_once('/').ok_or(INVALID)?;
    if !uri.scheme().eq_ignore_ascii_case("hapticscape")
        || !authority.eq_ignore_ascii_case("discord")
        || !path.starts_with("accept?")
        || uri.path() != "/accept"
        || uri.fragment().is_some()
    {
        return Err(INVALID.into());
    }
    let mut values = [None; 3];
    for pair in uri.query().ok_or(INVALID)?.split('&') {
        let (key, value) = pair.split_once('=').ok_or(INVALID)?;
        let index = match key {
            "controller" => 0,
            "request" => 1,
            "token" => 2,
            _ => return Err(INVALID.into()),
        };
        if values[index].replace(value).is_some() {
            return Err(INVALID.into());
        }
    }
    let [Some(controller), Some(request), Some(token)] = values else {
        return Err(INVALID.into());
    };
    if !(15..=22).contains(&controller.len())
        || !controller.bytes().all(|b| b.is_ascii_digit())
        || request.len() != 16
        || token.len() != 43
        || ![request, token].iter().all(|s| {
            s.bytes()
                .all(|b| b.is_ascii_alphanumeric() || b"_-".contains(&b))
        })
    {
        return Err(INVALID.into());
    }
    Ok(format!(
        "hapticscape://discord/accept?controller={controller}&request={request}&token={token}"
    ))
}
fn inbox(base: &Path, profile: &str) -> PathBuf {
    if profile.is_empty() {
        base.join("deep-links")
    } else {
        base.join("profiles").join(profile).join("deep-links")
    }
}
fn app_data() -> Result<PathBuf, String> {
    if let Some(local) =
        std::env::var_os("LOCALAPPDATA").filter(|v| !v.to_string_lossy().trim().is_empty())
    {
        return Ok(PathBuf::from(local).join("HapticScape"));
    }
    #[cfg(windows)]
    let home = std::env::var_os("USERPROFILE");
    #[cfg(not(windows))]
    let home = std::env::var_os("HOME");
    home.map(|p| PathBuf::from(p).join(".hapticscape"))
        .ok_or_else(|| "Cannot locate HapticScape data.".into())
}
fn atomic_write(directory: &Path, name: &str, bytes: &[u8]) -> Result<PathBuf, String> {
    fs::create_dir_all(directory).map_err(|_| "Cannot create the connection inbox.")?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(directory, fs::Permissions::from_mode(0o700))
            .map_err(|_| "Cannot protect the connection inbox.")?;
    }
    let target = directory.join(name);
    if target.exists() {
        return Ok(target);
    }
    let temp = directory.join(format!("{:032x}.tmp", rand::random::<u128>()));
    let mut options = fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let result = (|| {
        let mut file = options
            .open(&temp)
            .map_err(|_| "Cannot queue the connection link.")?;
        file.write_all(bytes)
            .and_then(|_| file.sync_all())
            .map_err(|_| "Cannot queue the connection link.")?;
        fs::rename(&temp, &target).map_err(|_| "Cannot queue the connection link.")?;
        Ok(target)
    })();
    let _ = fs::remove_file(temp);
    result
}
fn queue_dir(app: &tauri::AppHandle) -> Result<PathBuf, String> {
    app.path()
        .app_config_dir()
        .map(|p| p.join("pending-links"))
        .map_err(|_| "Cannot locate the connection inbox.".into())
}
pub fn receive(app: &tauri::AppHandle, args: &[String]) {
    if app.try_state::<AppState>().is_none() {
        let app = app.clone();
        let args = args.to_vec();
        std::thread::spawn(move || {
            for _ in 0..100 {
                if app.try_state::<AppState>().is_some() {
                    receive(&app, &args);
                    return;
                }
                std::thread::sleep(Duration::from_millis(50));
            }
        });
        return;
    }
    let links: Vec<_> = args
        .iter()
        .filter(|a| a.to_ascii_lowercase().starts_with("hapticscape:"))
        .collect();
    if links.is_empty() {
        return;
    }
    let result = (|| {
        if links.len() != 1 || args.len() != 2 {
            return Err(INVALID.into());
        }
        let link = validate(links[0])?;
        let state = app.state::<AppState>();
        let settings = state.settings.lock().unwrap().clone();
        settings.validate()?;
        let directory = queue_dir(app)?;
        if directory.exists()
            && fs::read_dir(&directory)
                .map_err(|_| "Cannot read queued links.")?
                .flatten()
                .filter(|e| e.path().extension().and_then(|v| v.to_str()) == Some("pending"))
                .count()
                >= 100
        {
            return Err("Too many queued connection links.".into());
        }
        let name = format!(
            "{:x}.pending",
            Sha256::digest(format!("{}\n{link}", settings.profile))
        );
        let record = serde_json::to_vec(&Pending {
            link,
            profile: settings.profile,
        })
        .map_err(|_| INVALID)?;
        atomic_write(&directory, &name, &record)?;
        Ok(())
    })();
    if let Err(message) = result {
        *app.state::<AppState>().deep_link_message.lock().unwrap() = message;
    }
}
// At-least-once handoff: Java's deletion acknowledges acceptance, including
// when the launcher restarts after an update. Never recreate an acknowledged file.
fn submit(directory: &Path, name: &str, link: &[u8], queued: &Path) -> Result<bool, String> {
    let request = directory.join(name);
    let submitted = queued.with_extension("submitted");
    if submitted.exists() && !request.exists() {
        return Ok(true);
    }
    atomic_write(directory, name, link)?;
    atomic_write(
        queued.parent().ok_or(INVALID)?,
        submitted
            .file_name()
            .and_then(|n| n.to_str())
            .ok_or(INVALID)?,
        b"submitted",
    )?;
    Ok(false)
}
fn deliver(app: &tauri::AppHandle, pending: &Pending, queued: &Path) -> Result<bool, String> {
    let state = app.state::<AppState>();
    let _launch = state.launches.lock().unwrap();
    if state.updating.load(Ordering::SeqCst) || state.components.needed() {
        return Ok(false);
    }
    let settings = state.settings.lock().unwrap().clone();
    settings.validate()?;
    if settings.profile != pending.profile {
        return Err(
            "A queued connection belongs to another profile. Restore that profile to open it."
                .into(),
        );
    }
    let link = validate(&pending.link)?;
    let name = format!("{:x}.request", Sha256::digest(link.as_bytes()));
    let directory = inbox(&app_data()?, &pending.profile);
    if submit(&directory, &name, link.as_bytes(), queued)? {
        return Ok(true);
    }
    let mut children = state.processes.lock().unwrap();
    if !processes::running(&mut children.hapticscape) && !crate::port_busy() {
        let cache = app
            .path()
            .app_cache_dir()
            .map_err(|_| "Cannot locate the launch cache.")?;
        let jar = processes::snapshot(Path::new(&settings.hapticscape_jar), &cache)?;
        let args = if settings.profile.is_empty() {
            Vec::new()
        } else {
            vec!["--profile", settings.profile.as_str()]
        };
        children.hapticscape = Some(processes::spawn(&settings, &jar, &args, &[])?);
    }
    // Removal by Java is the acknowledgement, not merely a busy gameplay port.
    Ok(false)
}
pub fn start(app: tauri::AppHandle) {
    std::thread::spawn(move || loop {
        let state = app.state::<AppState>();
        if !state.updating.load(Ordering::SeqCst) {
            if let Ok(directory) = queue_dir(&app) {
                if let Ok(files) = fs::read_dir(directory) {
                    for entry in files.flatten() {
                        let file = entry.path();
                        if file.extension().and_then(|e| e.to_str()) != Some("pending") {
                            continue;
                        }
                        let result = (|| {
                            let meta = fs::symlink_metadata(&file)
                                .map_err(|_| "Cannot read queued connection.")?;
                            if !meta.is_file() || meta.len() > 2048 {
                                return Err(INVALID.into());
                            }
                            if SystemTime::now()
                                .duration_since(meta.modified().map_err(|_| INVALID)?)
                                .unwrap_or_default()
                                > MAX_AGE
                            {
                                return Err(
                                    "The queued connection link expired. Select Accept again."
                                        .into(),
                                );
                            }
                            let pending: Pending =
                                serde_json::from_slice(&fs::read(&file).map_err(|_| INVALID)?)
                                    .map_err(|_| INVALID)?;
                            deliver(&app, &pending, &file)
                        })();
                        match result {
                            Ok(true) => {
                                let _ = fs::remove_file(&file);
                                let _ = fs::remove_file(file.with_extension("submitted"));
                                *state.deep_link_message.lock().unwrap() = String::new();
                            }
                            Ok(false) => {}
                            Err(error) => {
                                if error == INVALID || error.contains("expired") {
                                    let _ = fs::remove_file(&file);
                                    let _ = fs::remove_file(file.with_extension("submitted"));
                                }
                                *state.deep_link_message.lock().unwrap() = error;
                            }
                        }
                    }
                }
            }
        }
        std::thread::sleep(Duration::from_millis(250));
    });
}
#[cfg(test)]
mod tests {
    use super::*;
    fn link() -> String {
        format!(
            "hapticscape://discord/accept?controller=123456789012345&request={}&token={}",
            "a".repeat(16),
            "b".repeat(43)
        )
    }
    #[test]
    fn validates_exact_java_contract_and_rejects_normalized_paths() {
        assert!(validate(&link()).is_ok());
        for bad in [
            link() + "&extra=1",
            link() + "&token=duplicate",
            link() + "#fragment",
            link().replace("/accept?", "/foo/../accept?"),
            link().replace("discord/", "discord:80/"),
            link().replace("request=aaaaaaaaaaaaaaaa", "request=%61aaaaaaaaaaaaaaa"),
        ] {
            assert!(validate(&bad).is_err());
        }
        assert_eq!(inbox(Path::new("/data"), ""), Path::new("/data/deep-links"));
        assert_eq!(
            inbox(Path::new("/data"), "test"),
            Path::new("/data/profiles/test/deep-links")
        );
    }
    #[test]
    fn handoff_waits_for_java_and_survives_restart_without_replaying_acknowledged_links() {
        let root = std::env::temp_dir().join(format!(
            "hapticscape-handoff-{:032x}",
            rand::random::<u128>()
        ));
        let queue = root.join("launcher");
        let java = inbox(&root.join("java"), "subject");
        let pending = atomic_write(&queue, "one.pending", b"record").unwrap();
        assert!(!submit(&java, "one.request", link().as_bytes(), &pending).unwrap());
        assert!(!submit(&java, "one.request", link().as_bytes(), &pending).unwrap());
        assert_eq!(fs::read_dir(&java).unwrap().count(), 1);
        // A fresh invocation reconstructs everything from disk after an update.
        fs::remove_file(java.join("one.request")).unwrap();
        assert!(submit(&java, "one.request", link().as_bytes(), &pending).unwrap());
        assert!(!java.join("one.request").exists());
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn durable_queue_is_atomic_and_repeated_delivery_does_not_duplicate() {
        let directory = std::env::temp_dir().join(format!(
            "hapticscape-link-test-{:032x}",
            rand::random::<u128>()
        ));
        let path = atomic_write(&directory, "one.request", link().as_bytes()).unwrap();
        assert_eq!(fs::read_to_string(&path).unwrap(), link());
        atomic_write(&directory, "one.request", b"changed").unwrap();
        assert_eq!(fs::read_to_string(&path).unwrap(), link());
        assert_eq!(fs::read_dir(&directory).unwrap().count(), 1);
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(
                fs::metadata(&path).unwrap().permissions().mode() & 0o777,
                0o600
            );
        }
        fs::remove_dir_all(directory).unwrap();
    }
}
