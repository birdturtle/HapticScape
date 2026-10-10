use super::*;
use std::{
    io::{Read, Write},
    net::TcpListener,
    thread,
    time::{Duration, Instant},
};

fn session() -> Session {
    Session {
        session_id: "old-session".into(),
        refresh_token: "old-refresh".into(),
        characters: vec![Character {
            account_id: "chosen".into(),
            display_name: "Before".into(),
        }],
        selected: Some("chosen".into()),
        oauth_expires_at: 0,
        oauth_id_token: None,
        needs_sign_in: false,
    }
}
fn signed(audience: &str, subject: &str, nonce: Option<&str>) -> String {
    let mut header = jsonwebtoken::Header::new(Algorithm::RS256);
    header.kid = Some("test".into());
    let claims = serde_json::json!({"iss":"https://account.jagex.com/", "aud":audience, "sub":subject, "exp":now()+3600, "nonce":nonce});
    jsonwebtoken::encode(
        &header,
        &claims,
        &jsonwebtoken::EncodingKey::from_rsa_pem(include_bytes!(
            "test-fixtures/oauth-test-only.pem"
        ))
        .unwrap(),
    )
    .unwrap()
}
fn json(body: serde_json::Value) -> String {
    reply(200, &body.to_string(), "")
}
fn reply(status: u16, body: &str, headers: &str) -> String {
    format!("HTTP/1.1 {status} Test\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n{headers}\r\n{body}", body.len())
}
fn server(
    count: usize,
    mut response: impl FnMut(&str) -> String + Send + 'static,
) -> (String, thread::JoinHandle<()>) {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let origin = format!("http://{}", listener.local_addr().unwrap());
    listener.set_nonblocking(true).unwrap();
    let worker = thread::spawn(move || {
        for _ in 0..count {
            let deadline = Instant::now() + Duration::from_secs(10);
            let mut stream = loop {
                match listener.accept() {
                    Ok((stream, _)) => break stream,
                    Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                        assert!(Instant::now() < deadline, "Expected a provider request");
                        thread::sleep(Duration::from_millis(2));
                    }
                    Err(e) => panic!("{e}"),
                }
            };
            // Windows accepted sockets inherit the listener's nonblocking mode.
            // Read complete requests using the timeout on every platform.
            stream.set_nonblocking(false).unwrap();
            stream
                .set_read_timeout(Some(Duration::from_secs(2)))
                .unwrap();
            let mut bytes = Vec::new();
            let mut chunk = [0; 2048];
            loop {
                let read = stream.read(&mut chunk).unwrap();
                assert!(read > 0);
                bytes.extend_from_slice(&chunk[..read]);
                let text = String::from_utf8_lossy(&bytes);
                if let Some((head, body)) = text.split_once("\r\n\r\n") {
                    let length = head
                        .lines()
                        .find_map(|line| {
                            line.to_ascii_lowercase()
                                .strip_prefix("content-length:")
                                .and_then(|s| s.trim().parse::<usize>().ok())
                        })
                        .unwrap_or(0);
                    if body.len() >= length {
                        break;
                    }
                }
            }
            stream
                .write_all(response(std::str::from_utf8(&bytes).unwrap()).as_bytes())
                .unwrap();
        }
    });
    (origin, worker)
}
fn http() -> reqwest::Client {
    reqwest::Client::builder()
        .no_proxy()
        .timeout(Duration::from_secs(3))
        .redirect(reqwest::redirect::Policy::none())
        .build()
        .unwrap()
}
fn run<T>(future: impl std::future::Future<Output = T>) -> T {
    tokio::runtime::Runtime::new().unwrap().block_on(future)
}

#[test]
fn refresh_rotates_tokens_and_keeps_existing_token_when_provider_omits_replacement() {
    for rotate in [true, false] {
        let (origin, worker) = server(1, move |request| {
            assert!(request.starts_with("POST /oauth2/token "));
            let pairs: Vec<_> =
                url::form_urlencoded::parse(request.split_once("\r\n\r\n").unwrap().1.as_bytes())
                    .collect();
            assert!(pairs
                .iter()
                .any(|(k, v)| k == "grant_type" && v == "refresh_token"));
            assert!(pairs.iter().any(|(k, v)| k == "client_id" && v == CLIENT));
            assert!(pairs
                .iter()
                .any(|(k, v)| k == "refresh_token" && v == "old&refresh"));
            let mut body = serde_json::json!({"id_token":"new-id","expires_in":120});
            if rotate {
                body["refresh_token"] = serde_json::json!("rotated");
            }
            json(body)
        });
        let tokens = run(refresh_at(
            &http(),
            &format!("{origin}/oauth2/token"),
            "old&refresh",
        ))
        .unwrap();
        let mut saved = session();
        saved.apply_tokens(&tokens, 100);
        assert_eq!(
            saved.refresh_token,
            if rotate { "rotated" } else { "old&refresh" }
        );
        assert_eq!(saved.oauth_expires_at, 220);
        assert!(!saved.refresh_due(159));
        assert!(saved.refresh_due(160));
        worker.join().unwrap();
    }
}
#[test]
fn rejection_is_distinct_from_connection_rate_limit_server_and_parse_failures() {
    for (status, body, reauth) in [
        (400, r#"{"error":"invalid_grant"}"#, true),
        (400, r#"{"error":"temporarily_unavailable"}"#, false),
        (429, "{}", false),
        (503, "{}", false),
        (200, "malformed", false),
        (200, r#"{"id_token":"","refresh_token":"secret"}"#, false),
    ] {
        let (origin, worker) = server(1, move |_| reply(status, body, ""));
        let error = run(refresh_at(&http(), &origin, "refresh")).err().unwrap();
        assert_eq!(matches!(error, RenewalError::SignIn), reauth);
        assert!(!error.message().contains("secret"));
        worker.join().unwrap();
    }
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let origin = format!("http://{}", listener.local_addr().unwrap());
    drop(listener);
    assert!(matches!(
        run(refresh_at(&http(), &origin, "refresh")),
        Err(RenewalError::Retry(_))
    ));
}
fn provider_request(request: &str, bad_nonce: bool) -> String {
    let path = request.split_whitespace().nth(1).unwrap();
    if path == "/oauth2/token" {
        return json(
            serde_json::json!({"id_token":signed(CLIENT,"account",None),"refresh_token":"rotated","expires_in":3600}),
        );
    }
    if path == "/.well-known/jwks.json" {
        return reply(200, include_str!("test-fixtures/oauth-test-jwks.json"), "");
    }
    if path == "/game/accounts" {
        if request.to_ascii_lowercase().contains("bearer old-session") {
            return reply(401, "{}", "");
        }
        assert!(request
            .to_ascii_lowercase()
            .contains("bearer renewed-session"));
        return json(
            serde_json::json!([{"accountId":"other","displayName":"Other"},{"accountId":"chosen","displayName":"After"}]),
        );
    }
    if path.starts_with("/oauth2/auth?") {
        let target = Url::parse(&format!("http://local{path}")).unwrap();
        let pairs: std::collections::HashMap<_, _> = target.query_pairs().collect();
        assert_eq!(pairs.get("prompt").unwrap(), "none");
        assert_eq!(pairs.get("client_id").unwrap(), CONSENT_CLIENT);
        let nonce = if bad_nonce {
            "wrong"
        } else {
            pairs.get("nonce").unwrap()
        };
        let token = signed(CONSENT_CLIENT, "account", Some(nonce));
        return reply(
            302,
            "",
            &format!(
                "Location: http://localhost/#id_token={token}&state={}\r\n",
                pairs.get("state").unwrap()
            ),
        );
    }
    if path == "/game/sessions" {
        let body: serde_json::Value =
            serde_json::from_str(request.split_once("\r\n\r\n").unwrap().1).unwrap();
        let header = decode_header(body["idToken"].as_str().unwrap()).unwrap();
        assert_eq!(header.alg, Algorithm::RS256);
        return json(serde_json::json!({"sessionId":"renewed-session"}));
    }
    panic!("Unexpected provider request");
}
#[test]
fn expired_oauth_and_game_session_renew_silently_preserving_character_and_checkpointing_rotation() {
    let (origin, worker) = server(7, |request| provider_request(request, false));
    let services = Services {
        origin: &origin,
        game: &format!("{origin}/game"),
    };
    let mut saved = session();
    let mut checkpoints = Vec::new();
    run(ensure_session_at(
        &http(),
        &mut saved,
        "account",
        |session| {
            checkpoints.push(session.clone());
            Ok(())
        },
        &services,
    ))
    .unwrap();
    assert_eq!(saved.session_id, "renewed-session");
    assert_eq!(saved.refresh_token, "rotated");
    assert_eq!(saved.selected.as_deref(), Some("chosen"));
    assert_eq!(saved.characters[1].display_name, "After");
    assert!(!saved.needs_sign_in);
    assert_eq!(checkpoints[0].refresh_token, "rotated");
    assert_eq!(checkpoints[0].session_id, "old-session");
    assert_eq!(checkpoints[0].oauth_expires_at, 0);
    assert!(saved.oauth_expires_at > now());
    worker.join().unwrap();
}
#[test]
fn failed_later_request_preserves_rotated_token_and_account_for_retry() {
    let (origin, worker) = server(3, |request| {
        if request.starts_with("GET /game/accounts ") {
            reply(503, "{}", "")
        } else {
            provider_request(request, false)
        }
    });
    let mut saved = session();
    let original = session();
    let mut disk = session();
    let services = Services {
        origin: &origin,
        game: &format!("{origin}/game"),
    };
    assert!(matches!(
        run(ensure_session_at(
            &http(),
            &mut saved,
            "account",
            |s| {
                disk = s.clone();
                Ok(())
            },
            &services
        )),
        Err(RenewalError::Retry(_))
    ));
    assert_eq!(disk.refresh_token, "rotated");
    assert_eq!(disk.session_id, original.session_id);
    assert_eq!(disk.selected, original.selected);
    assert!(!disk.needs_sign_in);
    worker.join().unwrap();
}
#[test]
fn invalid_grant_keeps_account_and_requests_sign_in_without_launching() {
    let (origin, worker) = server(1, |_| reply(400, r#"{"error":"invalid_grant"}"#, ""));
    let mut saved = session();
    let mut disk = session();
    let services = Services {
        origin: &origin,
        game: &format!("{origin}/game"),
    };
    assert!(matches!(
        run(ensure_session_at(
            &http(),
            &mut saved,
            "account",
            |s| {
                disk = s.clone();
                Ok(())
            },
            &services
        )),
        Err(RenewalError::SignIn)
    ));
    assert!(disk.needs_sign_in);
    assert_eq!(disk.selected.as_deref(), Some("chosen"));
    assert_eq!(disk.characters.len(), 1);
    assert_eq!(disk.refresh_token, "old-refresh");
    worker.join().unwrap();
}
#[test]
fn wrong_nonce_cannot_replace_game_session() {
    let (origin, worker) = server(5, |request| provider_request(request, true));
    let mut saved = session();
    let services = Services {
        origin: &origin,
        game: &format!("{origin}/game"),
    };
    assert!(matches!(
        run(ensure_session_at(
            &http(),
            &mut saved,
            "account",
            |_| Ok(()),
            &services
        )),
        Err(RenewalError::SignIn)
    ));
    assert_eq!(saved.session_id, "old-session");
    assert!(saved.needs_sign_in);
    worker.join().unwrap();
}
#[test]
fn legacy_accounts_migrate_without_losing_character_selection() {
    let legacy = serde_json::json!({"session_id":"old-session","refresh_token":"refresh","characters":[{"accountId":"chosen","displayName":"Before"}],"selected":"chosen"});
    let book = crate::accounts::decode(&serde_json::to_vec(&legacy).unwrap()).unwrap();
    let saved = book.current().unwrap();
    assert!(saved.refresh_due(now()));
    assert!(!saved.needs_sign_in);
    assert_eq!(saved.selected.as_deref(), Some("chosen"));
}

#[test]
fn valid_session_skips_refresh_and_keeps_selected_character() {
    let (origin, worker) = server(1, |request| {
        assert!(request.starts_with("GET /game/accounts "));
        json(serde_json::json!([{"accountId":"chosen","displayName":"Current"}]))
    });
    let mut saved = session();
    saved.oauth_expires_at = now() + 3600;
    let services = Services {
        origin: &origin,
        game: &format!("{origin}/game"),
    };
    run(ensure_session_at(
        &http(),
        &mut saved,
        "account",
        |_| Ok(()),
        &services,
    ))
    .unwrap();
    assert_eq!(saved.refresh_token, "old-refresh");
    assert_eq!(saved.selected.as_deref(), Some("chosen"));
    worker.join().unwrap();
}
#[test]
fn rotated_token_is_retained_when_checkpoint_fails_and_no_launch_session_is_created() {
    let (origin, worker) = server(1, |request| provider_request(request, false));
    let mut saved = session();
    let services = Services {
        origin: &origin,
        game: &format!("{origin}/game"),
    };
    assert!(matches!(
        run(ensure_session_at(
            &http(),
            &mut saved,
            "account",
            |_| Err("Cannot save account.".into()),
            &services
        )),
        Err(RenewalError::Retry(_))
    ));
    assert_eq!(saved.refresh_token, "rotated");
    assert_eq!(saved.session_id, "old-session");
    assert_eq!(saved.oauth_expires_at, 0);
    worker.join().unwrap();
}
#[test]
fn changed_identity_cannot_replace_saved_account() {
    let (origin, worker) = server(2, |request| provider_request(request, false));
    let mut saved = session();
    let services = Services {
        origin: &origin,
        game: &format!("{origin}/game"),
    };
    assert!(matches!(
        run(ensure_session_at(
            &http(),
            &mut saved,
            "different-account",
            |_| Ok(()),
            &services
        )),
        Err(RenewalError::SignIn)
    ));
    assert_eq!(saved.session_id, "old-session");
    assert!(saved.needs_sign_in);
    worker.join().unwrap();
}
#[test]
fn silent_renewal_rejects_foreign_redirects_and_callback_state_without_following_them() {
    for location in [
        "https://example.com/collect",
        "http://localhost/#id_token=bad&state=wrong",
    ] {
        let (origin, worker) = server(1, move |_| {
            reply(302, "", &format!("Location: {location}\r\n"))
        });
        assert!(matches!(
            run(renew_game_session_at(
                &http(),
                "identity",
                "refresh".into(),
                "account",
                &origin,
                &format!("{origin}/game")
            )),
            Err(RenewalError::SignIn)
        ));
        worker.join().unwrap();
    }
}
