#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
mod accounts;
mod auth;
mod processes;
mod storage;

use auth::{AccountState, Callback, Phase};
use processes::{Preferences, Processes, Settings};
use serde::Serialize;
use std::{
    fs,
    net::TcpListener,
    path::PathBuf,
    sync::Mutex,
    time::{Duration, Instant},
};
use tauri::{Manager, WebviewUrl, WebviewWindow, WebviewWindowBuilder};

struct AppState {
    settings: Mutex<Settings>,
    processes: Mutex<Processes>,
    account: Mutex<AccountState>,
    wallet: Mutex<()>,
    launches: Mutex<()>,
    http: reqwest::Client,
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Status {
    settings: Settings,
    hapticscape_installed: bool,
    lumbridge_installed: bool,
    hapticscape_running: bool,
    lumbridge_running: bool,
    gameplay_port_busy: bool,
    accounts: Vec<AccountSummary>,
    selected_account: Option<String>,
    characters: Vec<auth::Character>,
    selected_character: Option<String>,
    signed_in: bool,
    signing_in: bool,
    account_message: String,
    platform: &'static str,
}

#[derive(Serialize)]
struct AccountSummary {
    id: String,
    name: String,
}

fn local(window: &WebviewWindow) -> Result<(), String> {
    let url = window
        .url()
        .map_err(|_| "Cannot validate the launcher window.".to_string())?;
    if window.label() != "main"
        || !(url.scheme() == "tauri"
            || ((url.scheme() == "http" || url.scheme() == "https")
                && url.host_str() == Some("tauri.localhost")))
    {
        return Err("This command is available only in the local launcher.".into());
    }
    Ok(())
}
fn config(app: &tauri::AppHandle) -> Result<PathBuf, String> {
    app.path()
        .app_config_dir()
        .map(|p| p.join("settings.json"))
        .map_err(|_| "Cannot locate launcher settings.".into())
}
fn port_busy() -> bool {
    TcpListener::bind(("127.0.0.1", 41713)).is_err()
}

#[tauri::command]
async fn launcher_status(
    window: WebviewWindow,
    state: tauri::State<'_, AppState>,
) -> Result<Status, String> {
    local(&window)?;
    let settings = state.settings.lock().unwrap().clone();
    let mut processes = state.processes.lock().unwrap();
    let account = state.account.lock().unwrap();
    Ok(Status {
        hapticscape_installed: PathBuf::from(&settings.hapticscape_jar).is_file(),
        lumbridge_installed: PathBuf::from(&settings.lumbridge_jar).is_file(),
        hapticscape_running: processes::running(&mut processes.hapticscape),
        lumbridge_running: processes::running(&mut processes.lumbridge),
        gameplay_port_busy: port_busy(),
        accounts: account
            .book
            .accounts
            .iter()
            .map(|a| AccountSummary {
                id: a.id.clone(),
                name: a
                    .session
                    .characters
                    .iter()
                    .map(|c| c.display_name.as_str())
                    .collect::<Vec<_>>()
                    .join(", "),
            })
            .collect(),
        selected_account: account.book.selected.clone(),
        characters: account
            .book
            .current()
            .map(|s| s.characters.clone())
            .unwrap_or_default(),
        selected_character: account.book.current().and_then(|s| s.selected.clone()),
        signed_in: account.book.current().is_some(),
        signing_in: account.attempt.is_some(),
        account_message: account.message.clone(),
        settings,
        platform: std::env::consts::OS,
    })
}

#[tauri::command]
fn save_preferences(
    window: WebviewWindow,
    app: tauri::AppHandle,
    state: tauri::State<AppState>,
    preferences: Preferences,
) -> Result<(), String> {
    local(&window)?;
    let mut current = state.settings.lock().unwrap();
    let mut settings = current.clone();
    settings.preferences = preferences;
    persist_settings(&app, &settings)?;
    *current = settings;
    Ok(())
}

fn persist_settings(app: &tauri::AppHandle, settings: &Settings) -> Result<(), String> {
    let path = config(app)?;
    fs::create_dir_all(path.parent().unwrap())
        .map_err(|_| "Cannot create the settings directory.".to_string())?;
    fs::write(path, serde_json::to_vec_pretty(settings).unwrap())
        .map_err(|_| "Cannot save launcher settings.".to_string())
}

#[tauri::command]
fn save_settings(
    window: WebviewWindow,
    app: tauri::AppHandle,
    state: tauri::State<AppState>,
    settings: Settings,
) -> Result<(), String> {
    local(&window)?;
    settings.validate()?;
    let mut processes = state.processes.lock().unwrap();
    if processes::running(&mut processes.hapticscape)
        || processes::running(&mut processes.lumbridge)
    {
        return Err("Close the managed applications before changing launch settings.".into());
    }
    let mut current = state.settings.lock().unwrap();
    let mut settings = settings;
    // Troubleshooting overrides do not change user preferences.
    settings.preferences = current.preferences.clone();
    persist_settings(&app, &settings)?;
    *current = settings;
    Ok(())
}

#[tauri::command]
async fn launch_app(
    window: WebviewWindow,
    app: tauri::AppHandle,
    mode: String,
) -> Result<String, String> {
    local(&window)?;
    if mode != "hapticscape" && mode != "play" {
        return Err("Unknown launch action.".into());
    }
    let minimize = mode == "play"
        && app
            .state::<AppState>()
            .settings
            .lock()
            .unwrap()
            .preferences
            .minimize_on_play;
    let result = tauri::async_runtime::spawn_blocking(move || {
        let state = app.state::<AppState>(); let _launch = state.launches.lock().unwrap();
        let settings = state.settings.lock().unwrap().clone(); settings.validate()?;
        let cache = app.path().app_cache_dir().map_err(|_| "Cannot locate the launch cache.".to_string())?;
        let credentials = if mode == "play" {
            let account = state.account.lock().unwrap();
            let session = account.book.current().ok_or("Sign in and choose a character before playing.")?;
            let character = session.characters.iter().find(|c| Some(&c.account_id) == session.selected.as_ref())
                .ok_or("Choose a character before playing.")?;
            vec![("JX_SESSION_ID".into(), session.session_id.clone()), ("JX_CHARACTER_ID".into(), character.account_id.clone()), ("JX_DISPLAY_NAME".into(), character.display_name.clone())]
        } else { Vec::new() };
        let lumbridge = if mode == "play" { Some(processes::snapshot(&PathBuf::from(&settings.lumbridge_jar), &cache)?) } else { None };
        let mut children = state.processes.lock().unwrap();
        if mode == "play" && processes::running(&mut children.lumbridge) { return Ok("LumBridge is already running.".into()); }
        if !processes::running(&mut children.hapticscape) {
            if port_busy() { return Err("Port 41713 is in use. Close the other HapticScape client before launching this profile.".into()); }
            let jar = processes::snapshot(&PathBuf::from(&settings.hapticscape_jar), &cache)?;
            children.hapticscape = Some(processes::spawn(&settings, &jar, &["--profile", &settings.profile], &[])?);
        }
        let deadline = Instant::now() + Duration::from_secs(15);
        while !port_busy() {
            if !processes::running(&mut children.hapticscape) { return Err("HapticScape exited before its gameplay bridge became ready.".into()); }
            if Instant::now() >= deadline { return Err("HapticScape is still starting. Try again once its window is ready.".into()); }
            std::thread::sleep(Duration::from_millis(100));
        }
        if let Some(jar) = lumbridge { children.lumbridge = Some(processes::spawn(&settings, &jar, &[], &credentials)?); }
        Ok(if mode == "play" { "HapticScape and LumBridge launched." } else { "HapticScape is ready." }.into())
    }).await.map_err(|_| "The launch task failed.".to_string())?;
    if minimize && result.is_ok() {
        let _ = window.minimize();
    }
    result
}

fn login_failure(app: &tauri::AppHandle, attempt_id: &str, message: String) {
    let changed = {
        let state = app.state::<AppState>();
        let mut account = state.account.lock().unwrap();
        if account.attempt.as_ref().map(|a| a.state.as_str()) == Some(attempt_id) {
            account.attempt = None;
            account.message = message;
            true
        } else {
            false
        }
    };
    if changed {
        if let Some(window) = app.get_webview_window("jagex-auth") {
            let _ = window.close();
        }
    }
}

async fn handle_callback(app: tauri::AppHandle, callback: Callback) {
    let state = app.state::<AppState>();
    let work = {
        let mut account = state.account.lock().unwrap();
        let Some(attempt) = account.attempt.as_mut() else {
            return;
        };
        match attempt.accept_callback(&callback) {
            Ok(true) => Ok(Some((
                attempt.state.clone(),
                attempt.verifier.clone(),
                attempt.nonce.clone(),
                attempt.subject.clone(),
                attempt.refresh_token.clone(),
            ))),
            Ok(false) => Ok(None),
            Err(error) => Err((attempt.state.clone(), error)),
        }
    };
    let (id, verifier, nonce, subject, refresh) = match work {
        Ok(Some(work)) => work,
        Ok(None) => return,
        Err((id, error)) => {
            login_failure(&app, &id, error);
            return;
        }
    };
    let result: Result<(), String> = async {
        match callback {
            Callback::Launcher { code, .. } => {
                let tokens = auth::exchange(&state.http, &code, &verifier).await?;
                let claims = auth::launcher_claims(&state.http, &tokens.id_token).await?;
                let next = {
                    let mut account = state.account.lock().unwrap();
                    let Some(attempt) = account.attempt.as_mut().filter(|a| a.state == id) else {
                        return Ok(());
                    };
                    attempt.subject = Some(claims.sub);
                    attempt.refresh_token = Some(tokens.refresh_token);
                    attempt.phase = Phase::Consent;
                    attempt.consent_url(&tokens.id_token)
                };
                if let Some(window) = app.get_webview_window("jagex-auth") {
                    window
                        .navigate(next)
                        .map_err(|_| "Cannot open Jagex consent.".to_string())?;
                }
            }
            Callback::Consent { token, .. } => {
                let claims = auth::consent_claims(&state.http, &token).await?;
                if claims.nonce.as_deref() != Some(&nonce) || Some(&claims.sub) != subject.as_ref()
                {
                    return Err("This identity response does not match your sign-in.".into());
                }
                let session = auth::game_session(
                    &state.http,
                    &token,
                    refresh.ok_or("Missing launcher session.")?,
                )
                .await?;
                let handle = app.clone();
                let attempt_id = id.clone();
                tauri::async_runtime::spawn_blocking(move || {
                    let state = handle.state::<AppState>();
                    let _wallet = state.wallet.lock().unwrap();
                    if state
                        .account
                        .lock()
                        .unwrap()
                        .attempt
                        .as_ref()
                        .map(|a| a.state.as_str())
                        != Some(attempt_id.as_str())
                    {
                        return Ok::<(), String>(());
                    }
                    let mut book = load_book(&handle)?;
                    book.add(claims.sub, session);
                    storage::save(&config(&handle)?.with_file_name("session.enc"), &book)?;
                    let mut account = state.account.lock().unwrap();
                    account.book = book;
                    account.loaded = true;
                    if account.attempt.as_ref().map(|a| a.state.as_str())
                        == Some(attempt_id.as_str())
                    {
                        account.attempt = None;
                        account.message = "Account added.".into();
                    }
                    Ok(())
                })
                .await
                .map_err(|_| "Cannot save the account.".to_string())??;
                if let Some(window) = app.get_webview_window("jagex-auth") {
                    let _ = window.close();
                }
            }
        }
        Ok(())
    }
    .await;
    if let Err(error) = result {
        login_failure(&app, &id, error);
    }
}

#[tauri::command]
async fn begin_login(window: WebviewWindow, app: tauri::AppHandle) -> Result<(), String> {
    local(&window)?;
    if let Some(existing) = app.get_webview_window("jagex-auth") {
        let _ = existing.set_focus();
        return Ok(());
    }
    let attempt = auth::Attempt::new();
    let url = attempt.login_url();
    let id = attempt.state.clone();
    {
        let state = app.state::<AppState>();
        let mut account = state.account.lock().unwrap();
        account.generation += 1;
        account.attempt = Some(attempt);
        account.message = "Complete sign-in in the Jagex window.".into();
    }
    let handle = app.clone();
    let builder = WebviewWindowBuilder::new(&app, "jagex-auth", WebviewUrl::External(url))
        .title("Sign in to Jagex • HapticScape")
        .inner_size(540.0, 760.0)
        .incognito(true)
        .on_navigation(move |url| {
            // No remote window gets launcher IPC. Intercept callbacks before a network request.
            match auth::callback(url) {
                Ok(Some(callback)) => {
                    let app = handle.clone();
                    tauri::async_runtime::spawn(handle_callback(app, callback));
                    false
                }
                Err(error) => {
                    let app = handle.clone();
                    let state = app.state::<AppState>();
                    let id = state
                        .account
                        .lock()
                        .unwrap()
                        .attempt
                        .as_ref()
                        .map(|a| a.state.clone());
                    if let Some(id) = id {
                        tauri::async_runtime::spawn(async move {
                            login_failure(&app, &id, error);
                        });
                    }
                    false
                }
                Ok(None) => {
                    let allowed = auth::allow_browser_navigation(url);
                    // Never print paths, query strings, fragments, or auth responses.
                    eprintln!(
                        "Jagex browser navigation: scheme={} host={} allowed={}",
                        url.scheme(),
                        url.host_str().unwrap_or("(none)"),
                        allowed
                    );
                    allowed
                }
            }
        });
    match builder.build() {
        Ok(auth_window) => {
            let app = app.clone();
            auth_window.on_window_event(move |event| {
                if matches!(event, tauri::WindowEvent::Destroyed) {
                    login_failure(
                        &app,
                        &id,
                        "Sign-in window closed. You can try again.".into(),
                    );
                }
            });
            Ok(())
        }
        Err(_) => {
            let state = app.state::<AppState>();
            state.account.lock().unwrap().attempt = None;
            Err("The Jagex sign-in window could not open.".into())
        }
    }
}

#[tauri::command]
fn cancel_login(window: WebviewWindow, app: tauri::AppHandle) -> Result<(), String> {
    local(&window)?;
    let state = app.state::<AppState>();
    {
        let mut account = state.account.lock().unwrap();
        account.generation += 1;
        account.attempt = None;
        account.message = "Sign-in cancelled.".into();
    }
    if let Some(window) = app.get_webview_window("jagex-auth") {
        let _ = window.close();
    }
    Ok(())
}

// Called only while the wallet mutex serializes all account changes.
fn load_book(app: &tauri::AppHandle) -> Result<accounts::AccountBook, String> {
    let state = app.state::<AppState>();
    let account = state.account.lock().unwrap();
    if account.loaded {
        return Ok(account.book.clone());
    }
    drop(account);
    Ok(storage::restore(&config(app)?.with_file_name("session.enc"))?.unwrap_or_default())
}

#[tauri::command]
async fn load_accounts(window: WebviewWindow, app: tauri::AppHandle) -> Result<(), String> {
    local(&window)?;
    tauri::async_runtime::spawn_blocking(move || {
        let state = app.state::<AppState>();
        let _wallet = state.wallet.lock().unwrap();
        let book = load_book(&app)?;
        let mut account = state.account.lock().unwrap();
        account.book = book;
        account.loaded = true;
        Ok(())
    })
    .await
    .map_err(|_| "Cannot load saved accounts.".to_string())?
}

async fn edit_accounts(
    app: tauri::AppHandle,
    edit: impl FnOnce(&mut accounts::AccountBook) -> Result<(), String> + Send + 'static,
) -> Result<(), String> {
    tauri::async_runtime::spawn_blocking(move || {
        let state = app.state::<AppState>();
        let _wallet = state.wallet.lock().unwrap();
        let mut book = load_book(&app)?;
        edit(&mut book)?;
        storage::save(&config(&app)?.with_file_name("session.enc"), &book)?;
        let mut account = state.account.lock().unwrap();
        account.book = book;
        account.loaded = true;
        account.message.clear();
        Ok(())
    })
    .await
    .map_err(|_| "Cannot save account changes.".to_string())?
}

#[tauri::command]
async fn select_account(
    window: WebviewWindow,
    app: tauri::AppHandle,
    id: String,
) -> Result<(), String> {
    local(&window)?;
    edit_accounts(app, move |book| book.select(&id)).await
}

#[tauri::command]
async fn remove_account(
    window: WebviewWindow,
    app: tauri::AppHandle,
    id: String,
) -> Result<(), String> {
    local(&window)?;
    edit_accounts(app, move |book| book.remove(&id)).await
}

#[tauri::command]
async fn select_character(
    window: WebviewWindow,
    app: tauri::AppHandle,
    id: String,
) -> Result<(), String> {
    local(&window)?;
    edit_accounts(app, move |book| {
        let session = book
            .current_mut()
            .ok_or("Add an account to choose a character.")?;
        if !session.characters.iter().any(|c| c.account_id == id) {
            return Err("Character is not in this account.".into());
        }
        session.selected = Some(id);
        Ok(())
    })
    .await
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct Release {
    tag: String,
    name: String,
    notes: String,
    published_at: String,
}
#[tauri::command]
async fn check_updates(window: WebviewWindow, app: tauri::AppHandle) -> Result<Release, String> {
    local(&window)?;
    #[derive(serde::Deserialize)]
    struct GitHubRelease {
        tag_name: String,
        name: Option<String>,
        body: Option<String>,
        published_at: Option<String>,
    }
    let release: GitHubRelease = app
        .state::<AppState>()
        .http
        .get("https://api.github.com/repos/birdturtle/HapticScape/releases/latest")
        .header("Accept", "application/vnd.github+json")
        .send()
        .await
        .map_err(|_| "Cannot reach GitHub. Installed apps are still available.".to_string())?
        .error_for_status()
        .map_err(|_| "GitHub could not return the latest release.".to_string())?
        .json()
        .await
        .map_err(|_| "GitHub returned invalid release information.".to_string())?;
    Ok(Release {
        tag: release.tag_name,
        name: release.name.unwrap_or_default(),
        notes: release.body.unwrap_or_default(),
        published_at: release.published_at.unwrap_or_default(),
    })
}

fn main() {
    // GTK's Wayland window ID derives from the program name, even when the
    // GtkApplication has its own ID. Match the installed desktop entry.
    #[cfg(target_os = "linux")]
    gtk::glib::set_prgname(Some("com.hapticscape.launcher"));
    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _, _| {
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.show();
                let _ = window.set_focus();
            }
        }))
        .setup(|app| {
            #[cfg(target_os = "linux")]
            {
                use gtk::prelude::*;
                gtk::Window::set_default_icon_name("com.hapticscape.launcher");
                if let Some(window) = app.get_webview_window("main") {
                    window
                        .gtk_window()?
                        .set_icon_name(Some("com.hapticscape.launcher"));
                }
            }
            let settings = config(app.handle())
                .ok()
                .and_then(|path| fs::read(path).ok())
                .and_then(|data| serde_json::from_slice::<Settings>(&data).ok())
                .filter(|s| s.validate().is_ok())
                .unwrap_or_default();
            app.manage(AppState {
                settings: Mutex::new(settings),
                processes: Mutex::new(Processes::default()),
                account: Mutex::new(AccountState::default()),
                wallet: Mutex::new(()),
                launches: Mutex::new(()),
                http: reqwest::Client::builder()
                    .timeout(Duration::from_secs(25))
                    .redirect(reqwest::redirect::Policy::none())
                    .user_agent("HapticScape-Launcher/0.1")
                    .build()?,
            });
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            launcher_status,
            save_settings,
            save_preferences,
            launch_app,
            begin_login,
            cancel_login,
            load_accounts,
            select_account,
            remove_account,
            select_character,
            check_updates
        ])
        .run(tauri::generate_context!())
        .expect("Launcher could not start");
}
