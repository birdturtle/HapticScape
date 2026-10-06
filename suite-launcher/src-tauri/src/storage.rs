use aes_gcm::{
    aead::{Aead, KeyInit, Payload},
    Aes256Gcm, Nonce,
};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use rand::{rngs::OsRng, RngCore};
use std::{fs, path::Path};
use zeroize::Zeroizing;

const PURPOSE: &[u8] = b"com.hapticscape.launcher.jagex.v1";
const SERVICE: &str = "com.hapticscape.launcher.jagex.v1";
fn entry() -> Result<keyring::Entry, String> {
    keyring::Entry::new(SERVICE, "wrapping-key")
        .map_err(|_| "Secure account storage is unavailable.".into())
}

fn key(existing_file: bool, create: bool) -> Result<Zeroizing<Vec<u8>>, String> {
    match entry()?.get_password() {
        Ok(value) => {
            let value = Zeroizing::new(value);
            let bytes = URL_SAFE_NO_PAD
                .decode(value.as_bytes())
                .map_err(|_| "The account wrapping key is damaged.".to_string())?;
            if bytes.len() != 32 {
                return Err("The account wrapping key is damaged.".into());
            }
            Ok(Zeroizing::new(bytes))
        }
        Err(keyring::Error::NoEntry) if create && !existing_file => {
            let mut bytes = Zeroizing::new(vec![0u8; 32]);
            OsRng.fill_bytes(&mut bytes);
            let encoded = Zeroizing::new(URL_SAFE_NO_PAD.encode(&*bytes));
            entry()?.set_password(&encoded).map_err(|_| {
                "The desktop wallet could not save your account key. Unlock it and retry."
                    .to_string()
            })?;
            Ok(bytes)
        }
        Err(keyring::Error::NoEntry) => Err(
            "The saved accounts' wallet key is missing. Restore your desktop wallet to access them."
                .into(),
        ),
        Err(_) => Err("The desktop wallet is locked or unavailable. Unlock it and retry.".into()),
    }
}

fn seal(plaintext: &[u8], key: &[u8]) -> Result<Vec<u8>, String> {
    let cipher = Aes256Gcm::new_from_slice(key).map_err(|_| "Invalid account key.".to_string())?;
    let mut nonce = [0u8; 12];
    OsRng.fill_bytes(&mut nonce);
    let encrypted = cipher
        .encrypt(
            Nonce::from_slice(&nonce),
            Payload {
                msg: plaintext,
                aad: PURPOSE,
            },
        )
        .map_err(|_| "Cannot protect the account session.".to_string())?;
    let mut result = b"HSL1".to_vec();
    result.extend_from_slice(&nonce);
    result.extend(encrypted);
    Ok(result)
}

fn open(envelope: &[u8], key: &[u8]) -> Result<Zeroizing<Vec<u8>>, String> {
    if envelope.len() < 32 || !envelope.starts_with(b"HSL1") {
        return Err("The saved account envelope is invalid.".into());
    }
    let cipher = Aes256Gcm::new_from_slice(key).map_err(|_| "Invalid account key.".to_string())?;
    cipher
        .decrypt(
            Nonce::from_slice(&envelope[4..16]),
            Payload {
                msg: &envelope[16..],
                aad: PURPOSE,
            },
        )
        .map(Zeroizing::new)
        .map_err(|_| {
            "The saved account could not be authenticated. Its data has been preserved.".into()
        })
}

pub fn save(path: &Path, session: &crate::accounts::AccountBook) -> Result<(), String> {
    // Verify any existing envelope before replacing it; no implicit reset on damage.
    let key = key(path.exists(), true)?;
    if path.exists() {
        open(&read(path)?, &key)?;
    }
    let plaintext = Zeroizing::new(
        serde_json::to_vec(session).map_err(|_| "Cannot encode account details.".to_string())?,
    );
    let envelope = seal(&plaintext, &key)?;
    let folder = path.parent().ok_or("Invalid account storage directory.")?;
    fs::create_dir_all(folder).map_err(|_| "Cannot create the account directory.".to_string())?;
    let temporary = folder.join(format!("session-{}.tmp", rand::random::<u64>()));
    fs::write(&temporary, envelope)
        .map_err(|_| "Cannot save the protected account.".to_string())?;
    if fs::rename(&temporary, path).is_err() {
        let _ = fs::remove_file(temporary);
        return Err("Cannot activate the protected account.".into());
    }
    Ok(())
}

fn read(path: &Path) -> Result<Vec<u8>, String> {
    let meta = fs::metadata(path).map_err(|_| "Cannot read the protected account.".to_string())?;
    if meta.len() > 1_048_576 {
        return Err("The protected account file is unexpectedly large.".into());
    }
    fs::read(path).map_err(|_| "Cannot read the protected account.".into())
}

pub fn restore(path: &Path) -> Result<Option<crate::accounts::AccountBook>, String> {
    if !path.exists() {
        return Ok(None);
    }
    let envelope = read(path)?;
    let key = key(true, false)?;
    let plaintext = open(&envelope, &key)?;
    crate::accounts::decode(&plaintext).map(Some)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn protected_tokens_round_trip_and_are_not_stored_in_plaintext() {
        let key = [7u8; 32];
        let token = b"private-refresh-token";
        let envelope = seal(token, &key).unwrap();
        assert!(!envelope.windows(token.len()).any(|window| window == token));
        assert_eq!(&*open(&envelope, &key).unwrap(), token);
        assert_ne!(seal(token, &key).unwrap(), envelope);
    }
    #[test]
    fn tampering_and_wrong_wallet_keys_fail_closed() {
        let mut envelope = seal(b"session", &[1u8; 32]).unwrap();
        assert!(open(&envelope, &[2u8; 32]).is_err());
        envelope[16] ^= 1;
        assert!(open(&envelope, &[1u8; 32]).is_err());
        assert!(open(b"bad", &[1u8; 32]).is_err());
    }
}
