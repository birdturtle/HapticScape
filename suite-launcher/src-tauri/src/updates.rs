use crate::processes::Settings;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    fs,
    io::{Read, Write},
    path::{Component, Path, PathBuf},
    process::Command,
    time::{Duration, Instant},
};

const MAX_DOWNLOAD: u64 = 600 * 1024 * 1024;
const MAX_EXPANDED: u64 = 2 * 1024 * 1024 * 1024;
const REPOSITORY: &str = "birdturtle/HapticScape";
#[derive(Clone, Deserialize)]
pub struct Asset {
    pub name: String,
    pub browser_download_url: String,
}
#[derive(Clone, Deserialize)]
pub struct GitHubRelease {
    pub tag_name: String,
    #[serde(default)]
    pub draft: bool,
    #[serde(default)]
    pub prerelease: bool,
    pub name: Option<String>,
    pub body: Option<String>,
    pub published_at: Option<String>,
    #[serde(default)]
    pub assets: Vec<Asset>,
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Release {
    pub tag: String,
    pub name: String,
    pub notes: String,
    pub published_at: String,
    pub installed_version: String,
    pub available: bool,
    pub installable: bool,
    pub message: String,
}
pub fn version(tag: &str) -> Result<semver::Version, String> {
    let text = tag.strip_prefix('v').unwrap_or(tag);
    if !text
        .bytes()
        .all(|c| c.is_ascii_alphanumeric() || b".-".contains(&c))
    {
        return Err("Invalid release version.".into());
    }
    semver::Version::parse(text).map_err(|_| "Invalid release version.".into())
}
pub fn asset_name(tag: &str, os: &str, arch: &str) -> Result<String, String> {
    let version = version(tag)?;
    let arch = match arch {
        "x86_64" => "x64",
        "aarch64" => "arm64",
        _ => return Err("Updates are unavailable for this architecture.".into()),
    };
    match os {
        "linux" => Ok(format!("HapticScape-Linux-{arch}-{version}.tar.gz")),
        "windows" => Ok(format!("HapticScape-Windows-{arch}-{version}.zip")),
        _ => Err("Updates are unavailable on this platform.".into()),
    }
}
fn asset<'a>(release: &'a GitHubRelease, name: &str) -> Result<&'a Asset, String> {
    let mut matches = release.assets.iter().filter(|a| a.name == name);
    let found = matches
        .next()
        .ok_or("The release does not include a compatible suite package.")?;
    if matches.next().is_some() {
        return Err("Duplicate release assets were returned.".into());
    }
    let expected = format!(
        "https://github.com/{REPOSITORY}/releases/download/{}/{name}",
        release.tag_name
    );
    if found.browser_download_url != expected {
        return Err("The update download is outside the expected release location.".into());
    }
    Ok(found)
}
fn select_release(releases: Vec<GitHubRelease>, beta: bool) -> Result<GitHubRelease, String> {
    releases
        .into_iter()
        .filter(|r| !r.draft && (beta || !r.prerelease))
        .filter_map(|r| version(&r.tag_name).ok().map(|v| (v, r)))
        .max_by(|a, b| a.0.cmp(&b.0))
        .map(|(_, r)| r)
        .ok_or_else(|| "No published releases are available for this update channel.".into())
}
pub async fn latest(http: &reqwest::Client, beta: bool) -> Result<GitHubRelease, String> {
    if beta {
        let mut releases = Vec::new();
        for page in 1..=10 {
            let batch: Vec<GitHubRelease> = http
                .get(format!(
                    "https://api.github.com/repos/{REPOSITORY}/releases?per_page=100&page={page}"
                ))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "HapticScape-Launcher")
                .send()
                .await
                .map_err(|_| {
                    "Cannot reach GitHub. Installed apps are still available.".to_string()
                })?
                .error_for_status()
                .map_err(|_| "GitHub could not return releases.".to_string())?
                .json()
                .await
                .map_err(|_| "GitHub returned invalid release information.".to_string())?;
            let done = batch.len() < 100;
            releases.extend(batch);
            if done {
                return select_release(releases, true);
            }
        }
        return Err("Too many releases to check safely.".into());
    }
    http.get(format!(
        "https://api.github.com/repos/{REPOSITORY}/releases/latest"
    ))
    .header("Accept", "application/vnd.github+json")
    .header("User-Agent", "HapticScape-Launcher")
    .send()
    .await
    .map_err(|_| "Cannot reach GitHub. Installed apps are still available.".to_string())?
    .error_for_status()
    .map_err(|_| "GitHub could not return the latest release.".to_string())?
    .json()
    .await
    .map_err(|_| "GitHub returned invalid release information.".into())
}
pub fn describe(
    release: GitHubRelease,
    installed: &str,
    writable: bool,
) -> Result<Release, String> {
    let available = version(&release.tag_name)? > version(installed)?;
    let compatible = asset_name(
        &release.tag_name,
        std::env::consts::OS,
        std::env::consts::ARCH,
    )
    .ok()
    .map(|name| {
        asset(&release, &name).is_ok()
            && asset(&release, &format!("{name}.sha256")).is_ok()
            && asset(
                &release,
                &format!(
                    "HapticScape-Suite-{}.json",
                    version(&release.tag_name).unwrap()
                ),
            )
            .is_ok()
    })
    .unwrap_or(false);
    let message = if !available {
        "You have the latest version."
    } else if !compatible {
        "This release does not include a compatible launcher update."
    } else if !writable {
        "Update this installation through your system package manager."
    } else {
        "Close HapticScape and LumBridge before installing. The launcher will restart."
    };
    Ok(Release {
        tag: release.tag_name,
        name: release.name.unwrap_or_default(),
        notes: release.body.unwrap_or_default(),
        published_at: release.published_at.unwrap_or_default(),
        installed_version: installed.into(),
        available,
        installable: available && compatible && writable,
        message: message.into(),
    })
}
pub fn install_root(executable: &Path) -> Result<PathBuf, String> {
    let root = Settings::installed_root(executable)
        .ok_or("Install the launcher before using automatic updates.")?;
    if !root.join("app/suite.json").is_file() {
        return Err("This installation does not support suite updates.".into());
    }
    #[cfg(target_os = "linux")]
    {
        let releases = root.parent().ok_or("Invalid installation path.")?;
        let install = releases.parent().ok_or("Invalid installation path.")?;
        if releases.file_name().and_then(|s| s.to_str()) != Some("releases")
            || fs::canonicalize(install.join("current")).ok().as_deref() != Some(root.as_path())
        {
            return Err("Update this installation through your system package manager.".into());
        }
        Ok(install.to_path_buf())
    }
    #[cfg(not(target_os = "linux"))]
    {
        Ok(root)
    }
}
fn safe_path(path: &Path) -> Result<(), String> {
    let text = path.to_string_lossy();
    let dangerous = path.components().any(|component| match component {
        Component::Normal(part) => {
            let part = part.to_string_lossy();
            let base = part.split('.').next().unwrap_or("").to_ascii_uppercase();
            part.ends_with(['.', ' '])
                || matches!(base.as_str(), "CON" | "PRN" | "AUX" | "NUL")
                || ["COM", "LPT"].iter().any(|prefix| {
                    base.strip_prefix(prefix)
                        .map(|n| n.len() == 1 && matches!(n.as_bytes()[0], b'1'..=b'9'))
                        .unwrap_or(false)
                })
        }
        _ => true,
    });
    if text.contains('\\')
        || text.contains(':')
        || dangerous
        || path
            .components()
            .next()
            .and_then(|c| c.as_os_str().to_str())
            != Some("HapticScape")
    {
        return Err("The update archive contains an unsafe path.".into());
    }
    Ok(())
}
fn safe_link(path: &Path, target: &Path) -> Result<(), String> {
    let mut depth = path.parent().unwrap().components().count();
    if depth < 1 {
        return Err("The archive root cannot be a link.".into());
    }
    for component in target.components() {
        match component {
            Component::Normal(part) if !part.to_string_lossy().contains(['\\', ':']) => depth += 1,
            Component::CurDir => {}
            Component::ParentDir if depth > 1 => depth -= 1,
            _ => return Err("The update archive contains an unsafe link.".into()),
        }
    }
    Ok(())
}
pub fn extract(archive: &Path, destination: &Path, windows: bool) -> Result<PathBuf, String> {
    fs::create_dir_all(destination).map_err(|_| "Cannot create update staging.".to_string())?;
    let mut expanded = 0u64;
    let mut count = 0;
    if windows {
        let mut zip = zip::ZipArchive::new(
            fs::File::open(archive).map_err(|_| "Cannot open update archive.")?,
        )
        .map_err(|_| "Invalid ZIP update archive.")?;
        for i in 0..zip.len() {
            let mut entry = zip.by_index(i).map_err(|_| "Invalid ZIP entry.")?;
            let path = PathBuf::from(entry.name().trim_end_matches('/'));
            safe_path(&path)?;
            expanded = expanded
                .checked_add(entry.size())
                .ok_or("Update archive is too large.")?;
            count += 1;
            if expanded > MAX_EXPANDED || count > 50000 {
                return Err("Update archive is too large.".into());
            }
            if entry
                .unix_mode()
                .map(|m| m & 0o170000 == 0o120000)
                .unwrap_or(false)
            {
                return Err("ZIP update links are not allowed.".into());
            }
            let target = destination.join(path);
            if entry.is_dir() {
                fs::create_dir_all(target).map_err(|_| "Cannot extract update directory.")?;
            } else {
                fs::create_dir_all(target.parent().unwrap())
                    .map_err(|_| "Cannot extract update directory.")?;
                let mut file = fs::OpenOptions::new()
                    .write(true)
                    .create_new(true)
                    .open(target)
                    .map_err(|_| "Duplicate or inaccessible update file.")?;
                std::io::copy(&mut entry, &mut file).map_err(|_| "Cannot extract update file.")?;
            }
        }
    } else {
        let decoder = flate2::read::GzDecoder::new(
            fs::File::open(archive).map_err(|_| "Cannot open update archive.")?,
        );
        let mut archive = tar::Archive::new(decoder);
        for entry in archive
            .entries()
            .map_err(|_| "Invalid TAR update archive.")?
        {
            let mut entry = entry.map_err(|_| "Invalid TAR update entry.")?;
            let path = entry
                .path()
                .map_err(|_| "Invalid update path.")?
                .into_owned();
            safe_path(&path)?;
            expanded = expanded
                .checked_add(entry.size())
                .ok_or("Update archive is too large.")?;
            count += 1;
            if expanded > MAX_EXPANDED || count > 50000 {
                return Err("Update archive is too large.".into());
            }
            let kind = entry.header().entry_type();
            if kind.is_symlink() {
                safe_link(
                    &path,
                    &entry
                        .link_name()
                        .map_err(|_| "Invalid update link.")?
                        .ok_or("Missing update link target.")?,
                )?;
            } else if !(kind.is_file() || kind.is_dir()) {
                return Err("Unsupported update archive entry.".into());
            }
            for ancestor in path.ancestors().skip(1) {
                if fs::symlink_metadata(destination.join(ancestor))
                    .map(|m| m.file_type().is_symlink())
                    .unwrap_or(false)
                {
                    return Err("Update archive entry passes through a link.".into());
                }
            }
            if fs::symlink_metadata(destination.join(&path))
                .map(|m| !kind.is_dir() || m.file_type().is_symlink())
                .unwrap_or(false)
            {
                return Err("Duplicate update archive file.".into());
            }
            if !entry
                .unpack_in(destination)
                .map_err(|_| "Cannot extract update archive.")?
            {
                return Err("Unsafe update archive path.".into());
            }
        }
    }
    Ok(destination.join("HapticScape"))
}
#[derive(Deserialize)]
struct Manifest {
    schema_version: u32,
    version: String,
}
fn manifest(bytes: &[u8], expected: &str) -> Result<(), String> {
    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct Wire {
        schema_version: u32,
        version: String,
    }
    let wire: Wire = serde_json::from_slice(bytes).map_err(|_| "Invalid suite update manifest.")?;
    let parsed = Manifest {
        schema_version: wire.schema_version,
        version: wire.version,
    };
    if parsed.schema_version != 1 || version(&parsed.version)? != version(expected)? {
        return Err("Suite update version does not match the release.".into());
    }
    Ok(())
}
pub fn validate(root: &Path, expected: &str) -> Result<(), String> {
    manifest(
        &fs::read(root.join("app/suite.json"))
            .map_err(|_| "The update is not a complete suite package.")?,
        expected,
    )?;
    let release: serde_json::Value = serde_json::from_slice(
        &fs::read(root.join("app/release.json")).map_err(|_| "Missing package metadata.")?,
    )
    .map_err(|_| "Invalid package metadata.")?;
    let arch = match std::env::consts::ARCH {
        "x86_64" => "x64",
        "aarch64" => "arm64",
        _ => return Err("Unsupported package architecture.".into()),
    };
    if version(
        release["version"]
            .as_str()
            .ok_or("Missing package version.")?,
    )? != version(expected)?
        || release["architecture"].as_str() != Some(arch)
    {
        return Err("Package version or architecture does not match this update.".into());
    }
    let settings = Settings::for_install(root);
    for path in [
        &settings.java_path,
        &settings.hapticscape_jar,
        &settings.lumbridge_jar,
    ] {
        if !Path::new(path).is_file() {
            return Err("The update is missing a required component.".into());
        }
    }
    let launcher = root.join(if cfg!(windows) {
        "launcher/HapticScapeLauncher.exe"
    } else {
        "launcher/hapticscape-launcher"
    });
    if !launcher.is_file() {
        return Err("The update is missing the launcher.".into());
    }
    #[cfg(windows)]
    for path in [
        "HapticScape.exe",
        "HapticScapeLegacy.exe",
        "app/HapticScapeUpdater.exe",
        "launcher/MicrosoftEdgeWebview2Setup.exe",
    ] {
        if !root.join(path).is_file() {
            return Err("The update is missing a Windows component.".into());
        }
    }
    let mut probe = Command::new(&settings.java_path)
        .arg("-jar")
        .arg(&settings.lumbridge_jar)
        .arg("--verify-runtime")
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn()
        .map_err(|_| "The updated Java runtime could not start.")?;
    let deadline = Instant::now() + Duration::from_secs(20);
    loop {
        if let Some(status) = probe
            .try_wait()
            .map_err(|_| "Cannot check the updated Java runtime.")?
        {
            if !status.success() {
                return Err("The updated Java runtime failed verification.".into());
            }
            break;
        }
        if Instant::now() >= deadline {
            // This is our isolated validation process, never a user's client.
            let _ = probe.kill();
            let _ = probe.wait();
            return Err("The updated Java runtime verification timed out.".into());
        }
        std::thread::sleep(Duration::from_millis(50));
    }
    Ok(())
}
pub fn checksum(bytes: &[u8]) -> Result<String, String> {
    let text = std::str::from_utf8(bytes).map_err(|_| "Invalid update checksum.")?;
    let hash = text
        .split_whitespace()
        .next()
        .ok_or("Missing update checksum.")?;
    if hash.len() != 64 || !hash.bytes().all(|c| c.is_ascii_hexdigit()) {
        return Err("Invalid update checksum.".into());
    }
    Ok(hash.to_ascii_lowercase())
}
async fn download(
    http: &reqwest::Client,
    asset: &Asset,
    target: &Path,
    limit: u64,
) -> Result<(), String> {
    let mut response = http
        .get(&asset.browser_download_url)
        .send()
        .await
        .map_err(|_| "The update download failed. Your installation has not changed.".to_string())?
        .error_for_status()
        .map_err(|_| "The update download is unavailable.".to_string())?;
    if response.content_length().unwrap_or(0) > limit {
        return Err("Update download is too large.".into());
    }
    let mut file = fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(target)
        .map_err(|_| "Cannot create update download.")?;
    let mut total = 0;
    while let Some(chunk) = response
        .chunk()
        .await
        .map_err(|_| "The update download was interrupted.".to_string())?
    {
        total += chunk.len() as u64;
        if total > limit {
            return Err("Update download is too large.".into());
        }
        file.write_all(&chunk)
            .map_err(|_| "Cannot save update download.")?;
    }
    file.sync_all()
        .map_err(|_| "Cannot finish update download.".into())
}
pub async fn stage(release: &GitHubRelease, install: &Path) -> Result<PathBuf, String> {
    let name = asset_name(
        &release.tag_name,
        std::env::consts::OS,
        std::env::consts::ARCH,
    )?;
    let package = asset(release, &name)?;
    let hash_asset = asset(release, &format!("{name}.sha256"))?;
    let manifest_asset = asset(
        release,
        &format!("HapticScape-Suite-{}.json", version(&release.tag_name)?),
    )?;
    #[cfg(windows)]
    let parent = install.parent().ok_or("Invalid installation directory.")?;
    #[cfg(not(windows))]
    let parent = install;
    let temporary = parent.join(format!(
        "HapticScape-update-{:032x}",
        rand::random::<u128>()
    ));
    fs::create_dir(&temporary).map_err(|_| {
        "Cannot stage an update here. Install the newer package manually.".to_string()
    })?;
    let result = async {
        let http = reqwest::Client::builder()
            .user_agent("HapticScape-Launcher")
            .connect_timeout(Duration::from_secs(15))
            .timeout(Duration::from_secs(600))
            .redirect(reqwest::redirect::Policy::custom(|attempt| {
                let url = attempt.url();
                if url.scheme() == "https"
                    && matches!(
                        url.host_str(),
                        Some(
                            "github.com"
                                | "release-assets.githubusercontent.com"
                                | "objects.githubusercontent.com"
                        )
                    )
                    && attempt.previous().len() < 5
                {
                    attempt.follow()
                } else {
                    attempt.stop()
                }
            }))
            .build()
            .map_err(|_| "Cannot prepare update download.")?;
        download(&http, manifest_asset, &temporary.join("suite.json"), 4096).await?;
        manifest(
            &fs::read(temporary.join("suite.json")).map_err(|_| "Cannot read suite manifest.")?,
            &release.tag_name,
        )?;
        download(&http, hash_asset, &temporary.join("checksum"), 4096).await?;
        download(&http, package, &temporary.join("package"), MAX_DOWNLOAD).await?;
        let temporary_copy = temporary.clone();
        let tag = release.tag_name.clone();
        tauri::async_runtime::spawn_blocking(move || {
            let expected = checksum(
                &fs::read(temporary_copy.join("checksum"))
                    .map_err(|_| "Cannot read update checksum.")?,
            )?;
            let mut file = fs::File::open(temporary_copy.join("package"))
                .map_err(|_| "Cannot read update package.")?;
            let mut hash = Sha256::new();
            let mut buffer = [0; 65536];
            loop {
                let len = file
                    .read(&mut buffer)
                    .map_err(|_| "Cannot verify update package.")?;
                if len == 0 {
                    break;
                }
                hash.update(&buffer[..len]);
            }
            if format!("{:x}", hash.finalize()) != expected {
                return Err(
                    "The update checksum does not match. Your installation has not changed.".into(),
                );
            }
            let staged = extract(
                &temporary_copy.join("package"),
                &temporary_copy.join("extracted"),
                cfg!(windows),
            )?;
            validate(&staged, &tag)?;
            Ok::<_, String>(staged)
        })
        .await
        .map_err(|_| "Update verification failed.".to_string())?
    }
    .await;
    if result.is_err() {
        let _ = fs::remove_dir_all(&temporary);
    }
    result
}
pub fn external_apps(cache: &Path) -> Result<bool, String> {
    #[cfg(target_os = "linux")]
    {
        let marker = cache.to_string_lossy();
        for entry in fs::read_dir("/proc")
            .map_err(|_| "Cannot check running applications.")?
            .flatten()
        {
            if !entry
                .file_name()
                .to_string_lossy()
                .bytes()
                .all(|c| c.is_ascii_digit())
            {
                continue;
            }
            if let Ok(bytes) = fs::read(entry.path().join("cmdline")) {
                let args: Vec<_> = bytes
                    .split(|b| *b == 0)
                    .map(|b| String::from_utf8_lossy(b))
                    .collect();
                if args.iter().any(|a| a == "-jar")
                    && args.iter().any(|a| {
                        a.starts_with(marker.as_ref())
                            || a.ends_with("lumbridge.jar")
                            || a.ends_with("hapticscape-desktop.jar")
                    })
                {
                    return Ok(true);
                }
            }
        }
        Ok(false)
    }
    #[cfg(windows)]
    {
        let script = "$p=Get-CimInstance Win32_Process -Filter \"Name = 'java.exe' OR Name = 'javaw.exe'\" -ErrorAction Stop; if ($p | Where-Object {$_.CommandLine -and $_.CommandLine.Contains('-jar') -and ($_.CommandLine.Contains($env:HAPTICSCAPE_UPDATE_CACHE) -or $_.CommandLine.Contains('lumbridge.jar') -or $_.CommandLine.Contains('hapticscape-desktop.jar'))}) {exit 10} else {exit 0}";
        let output = Command::new("powershell.exe")
            .args(["-NoProfile", "-NonInteractive", "-Command", script])
            .env("HAPTICSCAPE_UPDATE_CACHE", cache)
            .output()
            .map_err(|_| "Cannot check running applications.")?;
        match output.status.code() {
            Some(0) => Ok(false),
            Some(10) => Ok(true),
            _ => Err("Cannot check running applications. Close them and try again.".into()),
        }
    }
    #[cfg(not(any(target_os = "linux", windows)))]
    {
        let _ = cache;
        Err("Unsupported update platform.".into())
    }
}
pub fn handoff(staged: &Path, install: &Path) -> Result<(), String> {
    let temporary = staged
        .parent()
        .and_then(Path::parent)
        .ok_or("Invalid update staging.")?;
    #[cfg(windows)]
    {
        let helper = temporary.join("update-helper.exe");
        fs::copy(staged.join("app/HapticScapeUpdater.exe"), &helper)
            .map_err(|_| "Cannot prepare update helper.")?;
        Command::new(helper)
            .arg("--parent-pid")
            .arg(std::process::id().to_string())
            .arg("--install-dir")
            .arg(install)
            .arg("--staged-dir")
            .arg(staged)
            .arg("--temporary-root")
            .arg(temporary)
            .spawn()
            .map_err(|_| "Cannot start update helper.")?;
    }
    #[cfg(target_os = "linux")]
    {
        let helper = temporary.join("update-helper");
        fs::copy(
            std::env::current_exe().map_err(|_| "Cannot locate update helper.")?,
            &helper,
        )
        .map_err(|_| "Cannot prepare update helper.")?;
        Command::new(helper)
            .arg("--apply-update")
            .arg(std::process::id().to_string())
            .arg(install)
            .arg(staged)
            .spawn()
            .map_err(|_| "Cannot start update helper.")?;
    }
    Ok(())
}
#[cfg(target_os = "linux")]
fn switch(install: &Path, target: &Path) -> Result<(), String> {
    let link = install.join(format!(".update-link-{:016x}", rand::random::<u64>()));
    std::os::unix::fs::symlink(target, &link).map_err(|_| "Cannot prepare version switch.")?;
    let result = fs::rename(&link, install.join("current"))
        .map_err(|_| "Cannot activate updated version.".to_string());
    if result.is_err() {
        let _ = fs::remove_file(link);
    }
    result
}
#[cfg(target_os = "linux")]
pub fn apply_linux(install: &Path, staged: &Path, parent: u32) -> Result<(), String> {
    let temporary = staged
        .parent()
        .and_then(Path::parent)
        .ok_or("Invalid update staging.")?;
    if temporary.parent() != Some(install)
        || !temporary
            .file_name()
            .unwrap()
            .to_string_lossy()
            .starts_with("HapticScape-update-")
        || staged != temporary.join("extracted/HapticScape")
        || parent == 0
    {
        return Err("Invalid update helper request.".into());
    }
    let old = fs::canonicalize(install.join("current"))
        .map_err(|_| "Cannot locate current installation.")?;
    if old.parent() != Some(install.join("releases").as_path()) {
        return Err("Invalid current installation.".into());
    }
    let deadline = Instant::now() + Duration::from_secs(30);
    while PathBuf::from(format!("/proc/{parent}")).exists() {
        if Instant::now() >= deadline {
            return Err("The launcher did not exit in time.".into());
        }
        std::thread::sleep(Duration::from_millis(100));
    }
    let result = (|| {
        let metadata: serde_json::Value = serde_json::from_slice(
            &fs::read(staged.join("app/suite.json"))
                .map_err(|_| "Missing staged suite manifest.")?,
        )
        .map_err(|_| "Invalid staged suite manifest.")?;
        let version = version(
            metadata["version"]
                .as_str()
                .ok_or("Missing staged version.")?,
        )?;
        validate(staged, &version.to_string())?;
        let target = install
            .join("releases")
            .join(format!("{version}.update-{:016x}", rand::random::<u64>()));
        fs::rename(staged, &target).map_err(|_| "Cannot activate staged update.")?;
        switch(install, &target)?;
        let ready = temporary.join("launcher-ready");
        let token = format!("{:032x}", rand::random::<u128>());
        let mut child = Command::new(target.join("launcher/hapticscape-launcher"))
            .arg("--update-ready-file")
            .arg(&ready)
            .arg("--update-ready-token")
            .arg(&token)
            .spawn()
            .map_err(|_| "The updated launcher could not start.".to_string())?;
        let deadline = Instant::now() + Duration::from_secs(90);
        loop {
            if child
                .try_wait()
                .map_err(|_| "Cannot check updated launcher.")?
                .is_some()
            {
                return Err("The updated launcher exited before confirming startup.".into());
            }
            if fs::read_to_string(&ready).ok().as_deref() == Some(&token) {
                return Ok(version);
            }
            if Instant::now() >= deadline {
                let _ = Command::new("kill")
                    .arg("-TERM")
                    .arg(child.id().to_string())
                    .status();
                return Err("The updated launcher did not confirm startup.".into());
            }
            std::thread::sleep(Duration::from_millis(100));
        }
    })();
    match result {
        Ok(version) => {
            let _ = fs::write(
                install.join("update-result.json"),
                serde_json::json!({"message": format!("Updated to {version}.")}).to_string(),
            );
            let _ = fs::remove_dir_all(temporary);
            Ok(())
        }
        Err(error) => {
            switch(install, &old)?;
            let _ = fs::write(install.join("update-result.json"), serde_json::json!({"message": format!("Update failed; the previous version was restored. {error}")}).to_string());
            let _ = Command::new(old.join("launcher/hapticscape-launcher")).spawn();
            Err(error)
        }
    }
}
pub fn helper_mode() -> Option<Result<(), String>> {
    let args: Vec<String> = std::env::args().collect();
    if args.get(1).map(String::as_str) != Some("--apply-update") {
        return None;
    }
    #[cfg(target_os = "linux")]
    return Some(if args.len() == 5 {
        args[2]
            .parse::<u32>()
            .map_err(|_| "Invalid parent process.".to_string())
            .and_then(|pid| apply_linux(Path::new(&args[3]), Path::new(&args[4]), pid))
    } else {
        Err("Invalid updater arguments.".into())
    });
    #[cfg(not(target_os = "linux"))]
    Some(Err("Unsupported updater invocation.".into()))
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn channels_exclude_drafts_and_order_versions() {
        let releases = || {
            serde_json::from_str::<Vec<GitHubRelease>>(
                r#"[
          {"tag_name":"v3.2.0-beta.2","prerelease":true,"assets":[]},
          {"tag_name":"v3.1.4","assets":[]},
          {"tag_name":"v3.2.0-beta.10","prerelease":true,"assets":[]},
          {"tag_name":"v9.0.0","draft":true,"assets":[]},
          {"tag_name":"invalid","assets":[]}
        ]"#,
            )
            .unwrap()
        };
        assert_eq!(
            select_release(releases(), false).unwrap().tag_name,
            "v3.1.4"
        );
        assert_eq!(
            select_release(releases(), true).unwrap().tag_name,
            "v3.2.0-beta.10"
        );
        let stable: GitHubRelease =
            serde_json::from_str(r#"{"tag_name":"v3.2.0","assets":[]}"#).unwrap();
        let mut promoted = releases();
        promoted.push(stable);
        assert_eq!(select_release(promoted, true).unwrap().tag_name, "v3.2.0");
        assert!(select_release(Vec::new(), true).is_err());
    }
    #[test]
    fn versions_and_assets_are_platform_specific_and_reject_injection() {
        assert_eq!(
            asset_name("v3.1.2", "linux", "x86_64").unwrap(),
            "HapticScape-Linux-x64-3.1.2.tar.gz"
        );
        assert_eq!(
            asset_name("v3.1.2", "windows", "aarch64").unwrap(),
            "HapticScape-Windows-arm64-3.1.2.zip"
        );
        assert!(version("../../other").is_err());
        assert!(asset_name("v3.1.2", "linux", "armv7").is_err());
        assert!(version("v3.2.0").unwrap() > version("3.1.0").unwrap());
    }
    #[test]
    fn archive_paths_links_and_checksums_fail_closed() {
        for path in [
            "../escape",
            "/absolute",
            "HapticScape/../escape",
            "HapticScape/C:\\evil",
            "Other/app",
            "HapticScape/.. /outside",
            "HapticScape/app/NUL",
        ] {
            assert!(safe_path(Path::new(path)).is_err(), "{path}");
        }
        assert!(safe_path(Path::new("HapticScape/app/suite.json")).is_ok());
        assert!(safe_link(
            Path::new("HapticScape/runtime/legal/java.sql/LICENSE"),
            Path::new("../java.base/LICENSE")
        )
        .is_ok());
        assert!(safe_link(
            Path::new("HapticScape/app/link"),
            Path::new("../../outside")
        )
        .is_err());
        assert!(checksum(b"not-a-hash").is_err());
        assert_eq!(
            checksum(format!("{}  file.zip", "A".repeat(64)).as_bytes()).unwrap(),
            "a".repeat(64)
        );
    }
    #[test]
    fn manifest_requires_the_release_version_and_schema() {
        assert!(manifest(br#"{"schemaVersion":1,"version":"3.1.2"}"#, "v3.1.2").is_ok());
        assert!(manifest(br#"{"schemaVersion":1,"version":"3.1.1"}"#, "v3.1.2").is_err());
        assert!(manifest(br#"{"schemaVersion":2,"version":"3.1.2"}"#, "v3.1.2").is_err());
    }
    #[test]
    fn release_assets_must_be_unique_and_use_the_expected_repository() {
        let name = "HapticScape-Linux-x64-3.1.2.tar.gz";
        let mut release = GitHubRelease {
            tag_name: "v3.1.2".into(),
            draft: false,
            prerelease: false,
            name: None,
            body: None,
            published_at: None,
            assets: vec![Asset {
                name: name.into(),
                browser_download_url: format!(
                    "https://github.com/{REPOSITORY}/releases/download/v3.1.2/{name}"
                ),
            }],
        };
        assert!(asset(&release, name).is_ok());
        release.assets[0].browser_download_url = format!("https://example.com/{name}");
        assert!(asset(&release, name).is_err());
        release.assets[0].browser_download_url =
            format!("https://github.com/{REPOSITORY}/releases/download/v3.1.2/{name}");
        release.assets.push(release.assets[0].clone());
        assert!(asset(&release, name).is_err());
    }
    #[cfg(target_os = "linux")]
    #[test]
    fn linux_activation_confirms_startup_and_restores_old_version_on_failure() {
        use std::os::unix::fs::PermissionsExt;
        for fail in [false, true] {
            let install = std::env::temp_dir().join(format!(
                "hapticscape-activation-{:016x}",
                rand::random::<u64>()
            ));
            let old = install.join("releases/1.0.0.old");
            let temporary = install.join("HapticScape-update-test");
            let staged = temporary.join("extracted/HapticScape");
            for root in [&old, &staged] {
                for directory in ["launcher", "app", "LumBridge/app", "runtime/bin"] {
                    fs::create_dir_all(root.join(directory)).unwrap();
                }
                fs::write(root.join("app/hapticscape-desktop.jar"), b"PKapp").unwrap();
                fs::write(root.join("LumBridge/app/lumbridge.jar"), b"PKbridge").unwrap();
                fs::write(root.join("app/release.json"), serde_json::json!({"version":"1.1.0", "architecture": if std::env::consts::ARCH == "aarch64" {"arm64"} else {"x64"}}).to_string()).unwrap();
                fs::write(
                    root.join("app/suite.json"),
                    br#"{"schemaVersion":1,"version":"1.1.0"}"#,
                )
                .unwrap();
                fs::write(root.join("runtime/bin/java"), "#!/bin/sh\nexit 0\n").unwrap();
                fs::set_permissions(
                    root.join("runtime/bin/java"),
                    fs::Permissions::from_mode(0o755),
                )
                .unwrap();
                fs::write(
                    root.join("launcher/hapticscape-launcher"),
                    "#!/bin/sh\nexit 0\n",
                )
                .unwrap();
                fs::set_permissions(
                    root.join("launcher/hapticscape-launcher"),
                    fs::Permissions::from_mode(0o755),
                )
                .unwrap();
            }
            fs::write(
                staged.join("launcher/hapticscape-launcher"),
                if fail {
                    "#!/bin/sh\nexit 1\n"
                } else {
                    "#!/bin/sh\nprintf '%s' \"$4\" > \"$2\"\nsleep 1\n"
                },
            )
            .unwrap();
            std::os::unix::fs::symlink(&old, install.join("current")).unwrap();
            fs::write(install.join("user-data"), "existing").unwrap();
            let result = apply_linux(&install, &staged, u32::MAX);
            assert_eq!(result.is_err(), fail);
            assert_eq!(
                fs::canonicalize(install.join("current")).unwrap() == old,
                fail
            );
            assert_eq!(
                fs::read_to_string(install.join("user-data")).unwrap(),
                "existing"
            );
            assert!(old.join("launcher/hapticscape-launcher").exists());
            assert_eq!(temporary.exists(), fail);
            assert!(fs::read_to_string(install.join("update-result.json"))
                .unwrap()
                .contains(if fail { "restored" } else { "Updated" }));
            if fail {
                // The rollback launcher starts asynchronously; allow it to read
                // its script before removing the disposable installation.
                std::thread::sleep(Duration::from_millis(100));
            }
            fs::remove_dir_all(install).unwrap();
        }
    }
    #[test]
    fn extraction_rejects_zip_traversal_before_writing_outside_staging() {
        let root = std::env::temp_dir().join(format!(
            "hapticscape-extract-{:016x}",
            rand::random::<u64>()
        ));
        fs::create_dir_all(&root).unwrap();
        let archive = root.join("bad.zip");
        let mut zip = zip::ZipWriter::new(fs::File::create(&archive).unwrap());
        zip.start_file("../escape", zip::write::SimpleFileOptions::default())
            .unwrap();
        zip.write_all(b"bad").unwrap();
        zip.finish().unwrap();
        assert!(extract(&archive, &root.join("stage"), true).is_err());
        assert!(!root.join("escape").exists());
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    #[ignore = "requires a built distribution archive"]
    fn full_distribution_archive_passes_extraction_and_runtime_validation() {
        let archive = std::env::var("HAPTICSCAPE_UPDATE_ARCHIVE").expect("archive path");
        let expected = std::env::var("HAPTICSCAPE_UPDATE_VERSION").expect("release version");
        let root = std::env::temp_dir().join(format!(
            "hapticscape-real-archive-{:016x}",
            rand::random::<u64>()
        ));
        let staged = extract(Path::new(&archive), &root, cfg!(windows)).unwrap();
        validate(&staged, &expected).unwrap();
        fs::remove_dir_all(root).unwrap();
    }
    #[cfg(target_os = "linux")]
    #[test]
    fn unmanaged_processes_using_the_launch_cache_block_updates() {
        let cache = std::env::temp_dir().join(format!(
            "hapticscape-process-test-{:016x}",
            rand::random::<u64>()
        ));
        let jar = cache.join("hash/app.jar");
        let mut child = Command::new("sh")
            .args(["-c", "read -r unused", "-jar"])
            .arg(&jar)
            .stdin(std::process::Stdio::piped())
            .spawn()
            .unwrap();
        assert!(external_apps(&cache).unwrap());
        drop(child.stdin.take());
        child.wait().unwrap();
    }
}
