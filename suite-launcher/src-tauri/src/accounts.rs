use crate::auth::Session;
use serde::{Deserialize, Serialize};

#[derive(Clone, Serialize, Deserialize)]
pub struct SavedAccount {
    pub id: String,
    pub session: Session,
}

#[derive(Clone, Default, Serialize, Deserialize)]
pub struct AccountBook {
    pub accounts: Vec<SavedAccount>,
    pub selected: Option<String>,
}
impl AccountBook {
    pub fn current(&self) -> Option<&Session> {
        self.accounts
            .iter()
            .find(|a| Some(&a.id) == self.selected.as_ref())
            .map(|a| &a.session)
    }
    pub fn current_mut(&mut self) -> Option<&mut Session> {
        self.accounts
            .iter_mut()
            .find(|a| Some(&a.id) == self.selected.as_ref())
            .map(|a| &mut a.session)
    }
    pub fn add(&mut self, id: String, mut session: Session) {
        // Character identity also connects the old single-account envelope to its
        // verified OIDC subject on the next login, without creating a duplicate.
        if let Some(existing) = self.accounts.iter_mut().find(|a| {
            a.id == id
                || a.session.characters.iter().any(|old| {
                    session
                        .characters
                        .iter()
                        .any(|new| old.account_id == new.account_id)
                })
        }) {
            if session
                .characters
                .iter()
                .any(|c| Some(&c.account_id) == existing.session.selected.as_ref())
            {
                session.selected = existing.session.selected.clone();
            }
            existing.id = id.clone();
            existing.session = session;
        } else {
            self.accounts.push(SavedAccount {
                id: id.clone(),
                session,
            });
        }
        self.selected = Some(id);
    }
    pub fn select(&mut self, id: &str) -> Result<(), String> {
        if !self.accounts.iter().any(|a| a.id == id) {
            return Err("Account is no longer available.".into());
        }
        self.selected = Some(id.into());
        Ok(())
    }
    pub fn remove(&mut self, id: &str) -> Result<(), String> {
        if !self.accounts.iter().any(|a| a.id == id) {
            return Err("Account is no longer available.".into());
        }
        self.accounts.retain(|a| a.id != id);
        if self.selected.as_deref() == Some(id) {
            self.selected = self.accounts.first().map(|a| a.id.clone());
        }
        Ok(())
    }
}
pub fn decode(bytes: &[u8]) -> Result<AccountBook, String> {
    #[derive(Deserialize)]
    #[serde(untagged)]
    enum Stored {
        Book(AccountBook),
        Legacy(Session),
    }
    match serde_json::from_slice(bytes)
        .map_err(|_| "The saved accounts could not be read.".to_string())?
    {
        Stored::Book(mut book) => {
            if !book
                .accounts
                .iter()
                .any(|a| Some(&a.id) == book.selected.as_ref())
            {
                book.selected = book.accounts.first().map(|a| a.id.clone());
            }
            Ok(book)
        }
        Stored::Legacy(session) => {
            let mut book = AccountBook::default();
            book.add("legacy-account".into(), session);
            Ok(book)
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    fn session(character: &str) -> Session {
        Session {
            session_id: "secret".into(),
            refresh_token: "refresh".into(),
            characters: vec![crate::auth::Character {
                account_id: character.into(),
                display_name: character.into(),
            }],
            selected: Some(character.into()),
            oauth_expires_at: 0,
            oauth_id_token: None,
            needs_sign_in: false,
        }
    }
    #[test]
    fn added_accounts_survive_round_trip_switching_and_individual_removal() {
        let mut book = AccountBook::default();
        book.add("first".into(), session("a"));
        book.add("second".into(), session("b"));
        let mut book = decode(&serde_json::to_vec(&book).unwrap()).unwrap();
        assert_eq!(book.accounts.len(), 2);
        assert_eq!(book.current().unwrap().selected.as_deref(), Some("b"));
        book.select("first").unwrap();
        book.remove("second").unwrap();
        assert_eq!(book.accounts.len(), 1);
        assert_eq!(book.selected.as_deref(), Some("first"));
        book.remove("first").unwrap();
        assert!(book.current().is_none());
    }
    #[test]
    fn legacy_and_repeat_logins_update_one_account_and_preserve_other_accounts() {
        let mut book = decode(&serde_json::to_vec(&session("a")).unwrap()).unwrap();
        book.add("other".into(), session("b"));
        book.add("verified-subject".into(), session("a"));
        book.add("verified-subject".into(), session("a"));
        assert_eq!(book.accounts.len(), 2);
        assert_eq!(book.selected.as_deref(), Some("verified-subject"));
        assert!(book.select("unknown").is_err());
        assert!(book.remove("unknown").is_err());
        assert!(decode(b"damaged").is_err());
    }
}
