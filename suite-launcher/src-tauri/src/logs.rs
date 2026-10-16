use serde::{Deserialize, Serialize};
use std::{
    fs,
    io::{Read, Seek, SeekFrom},
    path::{Path, PathBuf},
};

#[derive(Clone, Copy, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum Kind { Hapticscape, Lumbridge, Launcher }
impl Kind {
    pub fn path(self, root: &Path) -> PathBuf {
        root.join(match self { Self::Hapticscape => "hapticscape.log", Self::Lumbridge => "lumbridge.log", Self::Launcher => "launcher.log" })
    }
}
/// Keep the output of the current and previous application launches.
pub fn output(path: &Path) -> Result<fs::File, String> {
    fs::create_dir_all(path.parent().ok_or("Invalid log folder.")?).map_err(|_| "Cannot create log folder.")?;
    if path.exists() { fs::rename(path, path.with_extension("previous.log")).map_err(|_| "Cannot rotate application log.")?; }
    let mut options = fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)] { use std::os::unix::fs::OpenOptionsExt; options.mode(0o600); }
    options.open(path).map_err(|_| "Cannot create application log.".into())
}
pub fn record(path: &Path, message: &str) {
    use std::io::Write;
    if let Some(parent) = path.parent() { let _ = fs::create_dir_all(parent); }
    if fs::metadata(path).is_ok_and(|m| m.len() > 128 * 1024) { let _ = fs::rename(path, path.with_extension("previous.log")); }
    let mut options = fs::OpenOptions::new();
    options.create(true).append(true);
    #[cfg(unix)] { use std::os::unix::fs::OpenOptionsExt; options.mode(0o600); }
    if let Ok(mut file) = options.open(path) { let _ = writeln!(file, "{}", filter(message)); }
}
#[derive(Serialize)]
pub struct View {
    pub text: String,
    pub truncated: bool,
}
fn filter(text: &str) -> String {
    text.lines()
        .map(|line| {
            let normalized: String = line
                .to_ascii_lowercase()
                .chars()
                .filter(|c| !matches!(c, '_' | '-' | ' '))
                .collect();
            if [
                "jx",
                "accesstoken",
                "refreshtoken",
                "idtoken",
                "sessionid",
                "password",
                "authorization",
                "bearer",
                "code=",
                "token=",
            ]
            .iter()
            .any(|marker| normalized.contains(marker))
            {
                "[Credential-bearing line omitted]".to_string()
            } else {
                line.chars()
                    .filter(|c| !c.is_control() || *c == '\t')
                    .collect()
            }
        })
        .collect::<Vec<_>>()
        .join("\n")
}
pub fn read(path: &Path) -> Result<View, String> {
    let metadata = match fs::symlink_metadata(path) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            return Ok(View {
                text: "No log has been created yet.".into(),
                truncated: false,
            })
        }
        Err(_) => return Err("Cannot read this log.".into()),
    };
    if !metadata.is_file() || metadata.file_type().is_symlink() {
        return Err("This log is not a regular file.".into());
    }
    let mut file = fs::File::open(path).map_err(|_| "Cannot open this log.")?;
    let limit = 128 * 1024;
    let start = metadata.len().saturating_sub(limit);
    file.seek(SeekFrom::Start(start))
        .map_err(|_| "Cannot read this log.")?;
    let mut bytes = Vec::new();
    file.take(limit)
        .read_to_end(&mut bytes)
        .map_err(|_| "Cannot read this log.")?;
    // Discard a partial first line so its missing prefix cannot hide a credential marker.
    if start > 0 {
        bytes = bytes
            .iter()
            .position(|b| *b == b'\n')
            .map(|i| bytes[i + 1..].to_vec())
            .unwrap_or_default();
    }
    let text = filter(&String::from_utf8_lossy(&bytes));
    Ok(View {
        text: if text.trim().is_empty() {
            "This log is empty.".into()
        } else {
            text
        },
        truncated: start > 0,
    })
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn credentials_and_control_characters_are_not_presented() {
        let text = filter("ready\nJX_SESSION_ID=secret\nAuthorization: Bearer secret\nrefresh_token=secret\nhttps://example/callback?code=secret\nhello\x1bworld");
        assert!(!text.contains("secret"));
        assert!(text.contains("ready"));
        assert!(!text.contains('\x1b'));
    }
    #[test]
    fn unknown_sources_cannot_supply_file_paths() {
        assert!(serde_json::from_str::<Kind>("\"../../settings.json\"").is_err());
        assert!(serde_json::from_str::<Kind>("\"/etc/passwd\"").is_err());
        assert!(serde_json::from_str::<Kind>("\"lumbridge\"").is_ok());
    }
    #[test]
    fn large_logs_are_bounded_and_partial_lines_are_discarded() {
        let root = std::env::temp_dir().join(format!("cabbage-log-test-{}", rand::random::<u64>()));
        fs::create_dir(&root).unwrap();
        let path = root.join("launch.log");
        fs::write(
            &path,
            format!("access_token={}\nready\n", "s".repeat(140 * 1024)),
        )
        .unwrap();
        let view = read(&path).unwrap();
        assert!(view.truncated);
        assert_eq!(view.text, "ready");
        assert_eq!(
            read(&root.join("missing.log")).unwrap().text,
            "No log has been created yet."
        );
        fs::remove_dir_all(root).unwrap();
    }
}
