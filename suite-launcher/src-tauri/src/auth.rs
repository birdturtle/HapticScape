use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use jsonwebtoken::{decode, decode_header, jwk::JwkSet, Algorithm, DecodingKey, Validation};
use rand::{rngs::OsRng, RngCore};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use url::Url;

const ORIGIN: &str = "https://account.jagex.com";
const CLIENT: &str = "com_jagex_auth_desktop_launcher";
const CONSENT_CLIENT: &str = "1fddee4e-b100-4f4e-b2b0-097f9088f9d2";
const REDIRECT: &str = "https://secure.runescape.com/m=weblogin/launcher-redirect";

// WebKitGTK reports subframe navigations here too. Login/challenge widgets need
// inert blank frames; they receive no launcher permissions. Callbacks are handled
// before this policy, so ordinary HTTP and executable/local schemes stay blocked.
pub fn allow_browser_navigation(url: &Url) -> bool {
    url.scheme() == "https" || (url.scheme() == "about" && matches!(url.path(), "blank" | "srcdoc"))
}

#[derive(Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Character {
    #[serde(alias = "accountId")]
    pub account_id: String,
    pub display_name: String,
}

#[derive(Clone, Serialize, Deserialize)]
pub struct Session {
    pub session_id: String,
    pub refresh_token: String,
    pub characters: Vec<Character>,
    pub selected: Option<String>,
    #[serde(default)]
    pub oauth_expires_at: u64,
    #[serde(default)]
    pub oauth_id_token: Option<String>,
    #[serde(default)]
    pub needs_sign_in: bool,
}

pub struct Attempt {
    pub verifier: String,
    pub state: String,
    pub consent_state: String,
    pub nonce: String,
    pub subject: Option<String>,
    pub refresh_token: Option<String>,
    pub oauth_expires_at: u64,
    pub phase: Phase,
}
#[derive(PartialEq)]
pub enum Phase {
    Launcher,
    Exchanging,
    Consent,
    Completing,
}

#[derive(Default)]
pub struct AccountState {
    pub book: crate::accounts::AccountBook,
    pub loaded: bool,
    pub attempt: Option<Attempt>,
    pub message: String,
    pub generation: u64,
}

fn random() -> String {
    let mut bytes = [0u8; 32];
    OsRng.fill_bytes(&mut bytes);
    URL_SAFE_NO_PAD.encode(bytes)
}

impl Attempt {
    pub fn new() -> Self {
        Self {
            verifier: random(),
            state: random(),
            consent_state: random(),
            nonce: random(),
            subject: None,
            refresh_token: None,
            oauth_expires_at: 0,
            phase: Phase::Launcher,
        }
    }
    pub fn accept_callback(&mut self, callback: &Callback) -> Result<bool, String> {
        let (received, expected, phase) = match callback {
            Callback::Launcher { state, .. } => (state, &self.state, &Phase::Launcher),
            Callback::Consent { state, .. } => (state, &self.consent_state, &Phase::Consent),
        };
        if received != expected {
            return Err("This response does not match your sign-in attempt.".into());
        }
        if &self.phase != phase {
            return Ok(false);
        }
        self.phase = match callback {
            Callback::Launcher { .. } => Phase::Exchanging,
            Callback::Consent { .. } => Phase::Completing,
        };
        Ok(true)
    }
    pub fn login_url(&self) -> Url {
        let mut url = Url::parse(&format!("{ORIGIN}/oauth2/auth")).unwrap();
        url.query_pairs_mut().extend_pairs([
            ("flow", "launcher"), ("response_type", "code"), ("client_id", CLIENT),
            ("code_challenge_method", "S256"), ("code_challenge", &URL_SAFE_NO_PAD.encode(Sha256::digest(self.verifier.as_bytes()))),
            ("prompt", "login"), ("scope", "openid offline gamesso.token.create user.profile.read user.entitlement.read user.game.read user.sku.read user.voucher.redeem"),
            ("redirect_uri", REDIRECT), ("state", &self.state), ("auth_method", ""), ("login_type", ""),
        ]);
        url
    }
    pub fn consent_url(&self, token: &str) -> Url {
        let mut url = Url::parse(&format!("{ORIGIN}/oauth2/auth")).unwrap();
        url.query_pairs_mut().extend_pairs([
            ("prompt", "consent"),
            ("redirect_uri", "http://localhost"),
            ("response_type", "id_token code"),
            ("client_id", CONSENT_CLIENT),
            ("scope", "openid offline"),
            ("id_token_hint", token),
            ("state", &self.consent_state),
            ("nonce", &self.nonce),
        ]);
        url
    }
}

pub enum Callback {
    Launcher { code: String, state: String },
    Consent { token: String, state: String },
}

/// Exact callback destinations only, parsed once. Never log these URLs.
pub fn callback(url: &Url) -> Result<Option<Callback>, String> {
    let launcher = url.scheme() == "https"
        && url.host_str() == Some("secure.runescape.com")
        && url.port_or_known_default() == Some(443)
        && url.path() == "/m=weblogin/launcher-redirect";
    let consent = url.scheme() == "http"
        && url.host_str() == Some("localhost")
        && url.port_or_known_default() == Some(80)
        && url.path() == "/";
    if !launcher && !consent {
        return Ok(None);
    }
    if !url.username().is_empty() || url.password().is_some() {
        return Err("Invalid login redirect.".into());
    }
    let raw = if launcher {
        url.query().unwrap_or("")
    } else {
        url.fragment().unwrap_or("")
    };
    let pairs: Vec<_> = url::form_urlencoded::parse(raw.as_bytes()).collect();
    let field = |name: &str| -> Result<String, String> {
        let values: Vec<_> = pairs.iter().filter(|(key, _)| key == name).collect();
        if values.len() != 1 || values[0].1.is_empty() {
            return Err("Incomplete or ambiguous login response.".into());
        }
        Ok(values[0].1.to_string())
    };
    if pairs.iter().any(|(key, _)| key == "error") {
        return Err("Jagex sign-in was declined. Try again when ready.".into());
    }
    Ok(Some(if launcher {
        Callback::Launcher {
            code: field("code")?,
            state: field("state")?,
        }
    } else {
        Callback::Consent {
            token: field("id_token")?,
            state: field("state")?,
        }
    }))
}

#[derive(Deserialize)]
pub struct Tokens {
    pub id_token: String,
    pub refresh_token: String,
    #[serde(default)]
    pub expires_in: u64,
}
#[derive(Deserialize)]
pub struct Claims {
    pub sub: String,
    pub nonce: Option<String>,
}

pub async fn exchange(
    http: &reqwest::Client,
    code: &str,
    verifier: &str,
) -> Result<Tokens, String> {
    http.post(format!("{ORIGIN}/oauth2/token"))
        .form(&[
            ("grant_type", "authorization_code"),
            ("client_id", CLIENT),
            ("code", code),
            ("code_verifier", verifier),
            ("redirect_uri", REDIRECT),
        ])
        .send()
        .await
        .map_err(|_| "Cannot reach Jagex's token service.".to_string())?
        .error_for_status()
        .map_err(|_| "Jagex could not complete this sign-in.".to_string())?
        .json()
        .await
        .map_err(|_| "Jagex returned an incomplete token response.".into())
}

pub async fn validate_token(
    http: &reqwest::Client,
    token: &str,
    audience: &str,
) -> Result<Claims, String> {
    validate_token_at(
        http,
        token,
        audience,
        &format!("{ORIGIN}/.well-known/jwks.json"),
    )
    .await
}
async fn validate_token_at(
    http: &reqwest::Client,
    token: &str,
    audience: &str,
    jwks: &str,
) -> Result<Claims, String> {
    let header = decode_header(token).map_err(|_| "Invalid identity token.".to_string())?;
    if header.alg != Algorithm::RS256 {
        return Err("Unsupported identity signature.".into());
    }
    let keys: JwkSet = http
        .get(jwks)
        .send()
        .await
        .map_err(|_| "Cannot verify Jagex's identity signature.".to_string())?
        .error_for_status()
        .map_err(|_| "Cannot load Jagex's signing keys.".to_string())?
        .json()
        .await
        .map_err(|_| "Invalid identity signing keys.".to_string())?;
    let jwk = keys
        .find(
            header
                .kid
                .as_deref()
                .ok_or("Identity token has no signing key.")?,
        )
        .ok_or("Unknown identity signing key.")?;
    let key =
        DecodingKey::from_jwk(jwk).map_err(|_| "Invalid identity signing key.".to_string())?;
    let mut validation = Validation::new(Algorithm::RS256);
    validation.set_audience(&[audience]);
    validation.set_issuer(&["https://account.jagex.com/"]);
    validation.set_required_spec_claims(&["exp", "iss", "aud", "sub"]);
    decode::<Claims>(token, &key, &validation)
        .map(|data| data.claims)
        .map_err(|_| "Jagex identity validation failed. Please sign in again.".into())
}

pub async fn launcher_claims(http: &reqwest::Client, token: &str) -> Result<Claims, String> {
    validate_token(http, token, CLIENT).await
}
pub async fn consent_claims(http: &reqwest::Client, token: &str) -> Result<Claims, String> {
    validate_token(http, token, CONSENT_CLIENT).await
}

pub async fn game_session(
    http: &reqwest::Client,
    token: &str,
    refresh_token: String,
) -> Result<Session, String> {
    game_session_at(
        http,
        token,
        refresh_token,
        "https://auth.jagex.com/game-session/v1",
    )
    .await
    .map_err(|e| e.message())
}
async fn game_session_at(
    http: &reqwest::Client,
    token: &str,
    refresh_token: String,
    game: &str,
) -> Result<Session, RenewalError> {
    #[derive(Deserialize)]
    struct Response {
        #[serde(rename = "sessionId")]
        session_id: String,
    }
    let response = http
        .post(format!("{game}/sessions"))
        .json(&serde_json::json!({"idToken": token}))
        .send()
        .await
        .map_err(|_| {
            RenewalError::Retry("Cannot reach Jagex's game-session service. Try again.".into())
        })?;
    if matches!(response.status().as_u16(), 401 | 403) {
        return Err(RenewalError::SignIn);
    }
    if !response.status().is_success() {
        return Err(RenewalError::Retry(
            "Jagex could not create a game session. Try again.".into(),
        ));
    }
    let response: Response = response
        .json()
        .await
        .map_err(|_| RenewalError::Retry("Invalid game-session response. Try again.".into()))?;
    if response.session_id.is_empty() {
        return Err(RenewalError::Retry(
            "Invalid game-session response. Try again.".into(),
        ));
    }
    let characters =
        check_characters_at(http, &response.session_id, &format!("{game}/accounts")).await?;
    let selected = characters.first().map(|c| c.account_id.clone());
    Ok(Session {
        session_id: response.session_id,
        refresh_token,
        characters,
        selected,
        oauth_expires_at: 0,
        oauth_id_token: None,
        needs_sign_in: false,
    })
}

#[derive(Debug)]
pub enum RenewalError {
    SignIn,
    Retry(String),
}
impl RenewalError {
    pub fn message(&self) -> String {
        match self {
            Self::SignIn => "Sign in again to continue playing.".into(),
            Self::Retry(message) => message.clone(),
        }
    }
}
pub fn now() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}
impl Session {
    pub fn refresh_due(&self, now: u64) -> bool {
        self.oauth_expires_at <= now.saturating_add(60)
    }
    pub fn apply_tokens(&mut self, tokens: &Tokens, now: u64) {
        self.refresh_token = tokens.refresh_token.clone();
        self.oauth_id_token = Some(tokens.id_token.clone());
        self.oauth_expires_at = now.saturating_add(tokens.expires_in);
    }
}
#[derive(Deserialize)]
struct RefreshResponse {
    id_token: String,
    refresh_token: Option<String>,
    #[serde(default)]
    expires_in: u64,
}
async fn refresh_at(
    http: &reqwest::Client,
    endpoint: &str,
    refresh: &str,
) -> Result<Tokens, RenewalError> {
    if refresh.is_empty() {
        return Err(RenewalError::SignIn);
    }
    let response = http
        .post(endpoint)
        .form(&[
            ("grant_type", "refresh_token"),
            ("client_id", CLIENT),
            ("refresh_token", refresh),
        ])
        .send()
        .await
        .map_err(|_| {
            RenewalError::Retry(
                "Cannot reach Jagex. Check your connection and try Play again.".into(),
            )
        })?;
    let status = response.status();
    if !status.is_success() {
        // Rate limits, server errors and malformed replies must never discard an account.
        if status == reqwest::StatusCode::BAD_REQUEST {
            let body: serde_json::Value = response.json().await.unwrap_or_default();
            if body.get("error").and_then(|v| v.as_str()) == Some("invalid_grant") {
                return Err(RenewalError::SignIn);
            }
        }
        return Err(RenewalError::Retry(
            "Jagex could not renew your login. Try Play again.".into(),
        ));
    }
    let response: RefreshResponse = response.json().await.map_err(|_| {
        RenewalError::Retry("Jagex returned an incomplete renewal response. Try again.".into())
    })?;
    if response.id_token.is_empty() || response.refresh_token.as_deref() == Some("") {
        return Err(RenewalError::Retry(
            "Jagex returned an incomplete renewal response. Try again.".into(),
        ));
    }
    Ok(Tokens {
        id_token: response.id_token,
        refresh_token: response.refresh_token.unwrap_or_else(|| refresh.into()),
        expires_in: response.expires_in,
    })
}
async fn check_characters_at(
    http: &reqwest::Client,
    session: &str,
    endpoint: &str,
) -> Result<Vec<Character>, RenewalError> {
    let response = http
        .get(endpoint)
        .bearer_auth(session)
        .send()
        .await
        .map_err(|_| {
            RenewalError::Retry(
                "Cannot reach Jagex. Check your connection and try Play again.".into(),
            )
        })?;
    if matches!(response.status().as_u16(), 401 | 403) {
        return Err(RenewalError::SignIn);
    }
    if !response.status().is_success() {
        return Err(RenewalError::Retry(
            "Jagex could not check your characters. Try Play again.".into(),
        ));
    }
    response.json().await.map_err(|_| {
        RenewalError::Retry("Jagex returned an invalid character list. Try again.".into())
    })
}
// Ask for a consent-audience identity silently. If Jagex requires interaction,
// preserve the account and offer the normal browser flow instead of bypassing it.
async fn renew_game_session_at(
    http: &reqwest::Client,
    identity: &str,
    refresh: String,
    subject: &str,
    origin: &str,
    game: &str,
) -> Result<Session, RenewalError> {
    let attempt = Attempt::new();
    let mut target = attempt.consent_url(identity);
    let service = Url::parse(origin).map_err(|_| RenewalError::SignIn)?;
    target
        .set_scheme(service.scheme())
        .map_err(|_| RenewalError::SignIn)?;
    target
        .set_host(service.host_str())
        .map_err(|_| RenewalError::SignIn)?;
    target
        .set_port(service.port())
        .map_err(|_| RenewalError::SignIn)?;
    let pairs: Vec<(String, String)> = target
        .query_pairs()
        .map(|(k, v)| {
            (
                k.to_string(),
                if k == "prompt" {
                    "none".into()
                } else {
                    v.into_owned()
                },
            )
        })
        .collect();
    target.set_query(None);
    target.query_pairs_mut().extend_pairs(pairs);
    for _ in 0..10 {
        let response = http
            .get(target.clone())
            .send()
            .await
            .map_err(|_| RenewalError::Retry("Cannot reach Jagex. Try Play again.".into()))?;
        if !response.status().is_redirection() {
            return Err(
                if response.status().is_server_error() || response.status().as_u16() == 429 {
                    RenewalError::Retry(
                        "Jagex could not renew your game session. Try again.".into(),
                    )
                } else {
                    RenewalError::SignIn
                },
            );
        }
        let location = response
            .headers()
            .get(reqwest::header::LOCATION)
            .and_then(|v| v.to_str().ok())
            .ok_or(RenewalError::SignIn)?;
        let next = target.join(location).map_err(|_| RenewalError::SignIn)?;
        if let Some(Callback::Consent { token, state }) =
            callback(&next).map_err(|_| RenewalError::SignIn)?
        {
            if state != attempt.consent_state {
                return Err(RenewalError::SignIn);
            }
            let claims = validate_token_at(
                http,
                &token,
                CONSENT_CLIENT,
                &format!("{origin}/.well-known/jwks.json"),
            )
            .await
            .map_err(RenewalError::Retry)?;
            if claims.sub != subject || claims.nonce.as_deref() != Some(attempt.nonce.as_str()) {
                return Err(RenewalError::SignIn);
            }
            return game_session_at(http, &token, refresh, game).await;
        }
        if next.origin() != service.origin()
            || !next.username().is_empty()
            || next.password().is_some()
        {
            return Err(RenewalError::SignIn);
        }
        target = next;
    }
    Err(RenewalError::SignIn)
}

struct Services<'a> {
    origin: &'a str,
    game: &'a str,
}
pub async fn ensure_session(
    http: &reqwest::Client,
    session: &mut Session,
    account_id: &str,
    checkpoint: impl FnMut(&Session) -> Result<(), String>,
) -> Result<(), RenewalError> {
    ensure_session_at(
        http,
        session,
        account_id,
        checkpoint,
        &Services {
            origin: ORIGIN,
            game: "https://auth.jagex.com/game-session/v1",
        },
    )
    .await
}
async fn refresh_session_at(
    http: &reqwest::Client,
    session: &mut Session,
    account_id: &str,
    checkpoint: &mut impl FnMut(&Session) -> Result<(), String>,
    services: &Services<'_>,
) -> Result<String, RenewalError> {
    let tokens = refresh_at(
        http,
        &format!("{}/oauth2/token", services.origin),
        &session.refresh_token,
    )
    .await?;
    session.apply_tokens(&tokens, now());
    let expiry = session.oauth_expires_at;
    // Rotation must survive even if signing-key retrieval or a later request fails.
    // Leave renewal due until the new identity is verified.
    session.oauth_expires_at = 0;
    checkpoint(session).map_err(RenewalError::Retry)?;
    let claims = validate_token_at(
        http,
        &tokens.id_token,
        CLIENT,
        &format!("{}/.well-known/jwks.json", services.origin),
    )
    .await
    .map_err(RenewalError::Retry)?;
    if account_id != "legacy-account" && claims.sub != account_id {
        return Err(RenewalError::SignIn);
    }
    session.oauth_expires_at = expiry;
    checkpoint(session).map_err(RenewalError::Retry)?;
    Ok(claims.sub)
}
async fn ensure_session_at(
    http: &reqwest::Client,
    session: &mut Session,
    account_id: &str,
    mut checkpoint: impl FnMut(&Session) -> Result<(), String>,
    services: &Services<'_>,
) -> Result<(), RenewalError> {
    if session.needs_sign_in {
        return Err(RenewalError::SignIn);
    }
    let result = async {
        let mut refreshed_subject = None;
        if session.refresh_due(now()) {
            refreshed_subject = Some(
                refresh_session_at(http, session, account_id, &mut checkpoint, services).await?,
            );
        }
        let characters = match check_characters_at(
            http,
            &session.session_id,
            &format!("{}/accounts", services.game),
        )
        .await
        {
            Ok(characters) => characters,
            Err(RenewalError::SignIn) => {
                let subject = match refreshed_subject {
                    Some(subject) => subject,
                    None => {
                        refresh_session_at(http, session, account_id, &mut checkpoint, services)
                            .await?
                    }
                };
                let renewed = renew_game_session_at(
                    http,
                    session
                        .oauth_id_token
                        .as_deref()
                        .ok_or(RenewalError::SignIn)?,
                    session.refresh_token.clone(),
                    &subject,
                    services.origin,
                    services.game,
                )
                .await?;
                session.session_id = renewed.session_id;
                renewed.characters
            }
            Err(error) => return Err(error),
        };
        session.characters = characters;
        if !session
            .characters
            .iter()
            .any(|c| Some(&c.account_id) == session.selected.as_ref())
        {
            session.selected = None;
        }
        session.needs_sign_in = false;
        checkpoint(session).map_err(RenewalError::Retry)?;
        Ok(())
    }
    .await;
    if matches!(result, Err(RenewalError::SignIn)) {
        session.needs_sign_in = true;
        checkpoint(session).map_err(RenewalError::Retry)?;
    }
    result
}
#[cfg(test)]
#[path = "auth_tests.rs"]
mod renewal_tests;

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn login_frames_are_allowed_without_opening_local_or_executable_schemes() {
        for value in [
            "https://account.jagex.com/",
            "https://challenges.cloudflare.com/",
            "about:blank",
            "about:blank#frame",
            "about:srcdoc",
        ] {
            assert!(
                allow_browser_navigation(&Url::parse(value).unwrap()),
                "{value}"
            );
        }
        for value in [
            "http://example.com/",
            "http://localhost/",
            "file:///etc/passwd",
            "javascript:alert(1)",
            "data:text/html,hello",
            "tauri://localhost/",
            "about:settings",
        ] {
            assert!(
                !allow_browser_navigation(&Url::parse(value).unwrap()),
                "{value}"
            );
        }
    }
    #[test]
    fn login_uses_pkce_and_unique_state() {
        let first = Attempt::new();
        let second = Attempt::new();
        assert_ne!(first.state, second.state);
        let url = first.login_url();
        let pairs: std::collections::HashMap<_, _> = url.query_pairs().collect();
        assert_eq!(pairs["code_challenge_method"], "S256");
        assert_ne!(pairs["code_challenge"], first.verifier);
    }
    #[test]
    fn state_mismatch_and_replayed_callbacks_do_not_advance_login() {
        let mut attempt = Attempt::new();
        let foreign = Callback::Launcher {
            code: "code".into(),
            state: "other-attempt".into(),
        };
        assert!(attempt.accept_callback(&foreign).is_err());
        assert!(attempt.phase == Phase::Launcher);
        let valid = Callback::Launcher {
            code: "code".into(),
            state: attempt.state.clone(),
        };
        assert!(attempt.accept_callback(&valid).unwrap());
        assert!(!attempt.accept_callback(&valid).unwrap());
        assert!(attempt.phase == Phase::Exchanging);
    }
    #[test]
    fn callback_rejects_duplicates_and_foreign_destinations() {
        assert!(callback(
            &Url::parse("https://evil.test/m=weblogin/launcher-redirect?code=a&state=b").unwrap()
        )
        .unwrap()
        .is_none());
        assert!(
            callback(&Url::parse(&format!("{REDIRECT}?code=a&code=b&state=c")).unwrap()).is_err()
        );
        assert!(
            callback(&Url::parse("http://localhost:8080/#id_token=a&state=b").unwrap())
                .unwrap()
                .is_none()
        );
        assert!(matches!(
            callback(&Url::parse(&format!("{REDIRECT}?code=a&state=b")).unwrap()).unwrap(),
            Some(Callback::Launcher { .. })
        ));
    }
}
