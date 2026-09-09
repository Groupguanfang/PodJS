//! Host-only authentication handle. No guest can choose its key or permissions.
use crate::sync_auth::{AuthenticatedFrame, AuthenticatedSession, Channel, Delivery, SessionBinding, handshake_proof};
use anyhow::{Result, ensure};
use serde::Deserialize;
use serde_json::json;
use std::ffi::{CString, c_char};

const MAX_COMMAND: usize = 2 * 1024 * 1024;
#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Config {
    key: [u8; 32],
    binding: SessionBinding,
    local_is_initiator: bool,
    allowed_channels: Vec<Channel>,
}
impl Drop for Config { fn drop(&mut self) { self.key.fill(0); } }
#[derive(Deserialize)]
#[serde(tag = "method", deny_unknown_fields)]
enum Command {
    #[serde(rename = "proof")] Proof,
    #[serde(rename = "authenticate")] Authenticate { proof: [u8; 32] },
    #[serde(rename = "send")] Send { channel: Channel, message_id: String, payload: Vec<u8> },
    #[serde(rename = "verify")] Verify { frame: AuthenticatedFrame },
    #[serde(rename = "commit")] Commit { sequence: u64 },
}
pub struct PodSyncSession {
    config: Config,
    session: Option<AuthenticatedSession>,
    sent: u64,
    failed: bool,
    response: CString,
}
impl PodSyncSession {
    fn create(config: Config) -> Result<Self> {
        ensure!(config.key != [0; 32], "invalid pairing key");
        ensure!(!config.allowed_channels.is_empty() && config.allowed_channels.len() <= 4, "invalid channel grants");
        handshake_proof(&config.key, &config.binding, config.local_is_initiator)?;
        Ok(Self { config, session: None, sent: 0, failed: false, response: CString::default() })
    }
    fn dispatch(&mut self, bytes: &[u8]) -> Result<serde_json::Value> {
        ensure!(!self.failed, "session failed; reconnect with fresh challenges");
        let command: Command = serde_json::from_slice(bytes)?;
        match command {
            Command::Proof => Ok(json!({"proof":handshake_proof(&self.config.key, &self.config.binding, self.config.local_is_initiator)?})),
            Command::Authenticate { proof } => {
                ensure!(self.session.is_none(), "already authenticated");
                match AuthenticatedSession::open(self.config.key, self.config.binding.clone(), self.config.local_is_initiator, &proof) {
                    Ok(session) => {
                        let result = json!({"sessionId":session.session_id(),"authenticated":true});
                        self.session = Some(session); Ok(result)
                    }
                    Err(error) => { self.failed = true; Err(error) }
                }
            }
            Command::Send { channel, message_id, payload } => {
                ensure!(self.config.allowed_channels.contains(&channel), "channel not authorized");
                let session = self.session.as_ref().ok_or_else(|| anyhow::anyhow!("handshake required"))?;
                let sequence = self.sent.checked_add(1).ok_or_else(|| anyhow::anyhow!("sequence exhausted"))?;
                let frame = session.sign(sequence, channel, message_id, payload)?;
                self.sent = sequence;
                // Hosts retain this exact frame for retransmission until ACK.
                Ok(json!({"frame":frame}))
            }
            Command::Verify { frame } => {
                let session = self.session.as_mut().ok_or_else(|| anyhow::anyhow!("handshake required"))?;
                match session.verify(&frame, &self.config.allowed_channels) {
                    Ok(delivery) => Ok(json!({"delivery":if delivery == Delivery::Duplicate {"duplicate"} else {"pending"},"acknowledged":session.acknowledged()})),
                    Err(error) => { self.failed = true; Err(error) }
                }
            }
            Command::Commit { sequence } => {
                let session = self.session.as_mut().ok_or_else(|| anyhow::anyhow!("handshake required"))?;
                Ok(json!({"acknowledged":session.commit_received(sequence)?}))
            }
        }
    }
}

/// # Safety
/// Config bytes are readable, host-owned JSON with OS-random challenges and an
/// authenticated pairing key. Grants come from the verified app manifest.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_sync_session_open(bytes: *const u8, length: usize) -> *mut PodSyncSession {
    if bytes.is_null() || length == 0 || length > 8192 { return std::ptr::null_mut(); }
    let result = serde_json::from_slice::<Config>(unsafe { std::slice::from_raw_parts(bytes, length) })
        .map_err(anyhow::Error::from).and_then(PodSyncSession::create);
    match result {
        Ok(handle) => Box::into_raw(Box::new(handle)),
        Err(error) => { crate::set_error(error.to_string()); std::ptr::null_mut() }
    }
}
/// # Safety
/// Handle is live and exclusively borrowed. Bytes are readable for length.
/// The returned UTF-8 JSON is borrowed until the next command or close.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_sync_session_command(handle: *mut PodSyncSession, bytes: *const u8, length: usize) -> *const c_char {
    let Some(handle) = (unsafe { handle.as_mut() }) else { return std::ptr::null(); };
    let result = if bytes.is_null() || length == 0 || length > MAX_COMMAND { Err(anyhow::anyhow!("invalid command size")) }
        else { handle.dispatch(unsafe { std::slice::from_raw_parts(bytes, length) }) };
    let reply = match result {
        Ok(value) => json!({"ok":true,"value":value}),
        Err(error) => json!({"ok":false,"code":"sync_session_error","message":error.to_string()}),
    };
    handle.response = CString::new(reply.to_string()).expect("JSON escapes NUL");
    handle.response.as_ptr()
}
/// # Safety
/// Handle is null or an exclusively owned live handle, closed once.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn pod_sync_session_close(handle: *mut PodSyncSession) {
    if !handle.is_null() { drop(unsafe { Box::from_raw(handle) }); }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn config(initiator: bool) -> Config {
        Config { key:[7;32], binding:SessionBinding { version:1, app_id:"app".into(), initiator:"phone".into(), responder:"watch".into(), initiator_nonce:[1;32], responder_nonce:[2;32] }, local_is_initiator:initiator, allowed_channels:vec![Channel::Message] }
    }
    fn command(handle: &mut PodSyncSession, value: serde_json::Value) -> Result<serde_json::Value> { handle.dispatch(&serde_json::to_vec(&value)?) }
    #[test] fn handshake_gates_delivery_and_commit_controls_ack() {
        let mut a = PodSyncSession::create(config(true)).unwrap();
        let mut b = PodSyncSession::create(config(false)).unwrap();
        let send = json!({"method":"send","channel":"message","message_id":"one","payload":[1,2,3]});
        assert!(command(&mut a, send.clone()).is_err());
        let ap = command(&mut a,json!({"method":"proof"})).unwrap();
        let bp = command(&mut b,json!({"method":"proof"})).unwrap();
        command(&mut a,json!({"method":"authenticate","proof":bp["proof"]})).unwrap();
        command(&mut b,json!({"method":"authenticate","proof":ap["proof"]})).unwrap();
        let frame = command(&mut a,send.clone()).unwrap()["frame"].clone();
        assert_eq!(frame["sequence"],1);
        assert_eq!(command(&mut a,send).unwrap()["frame"]["sequence"],2);
        let verify = json!({"method":"verify","frame":frame});
        let pending = command(&mut b,verify.clone()).unwrap();
        assert_eq!(pending["delivery"],"pending"); assert_eq!(pending["acknowledged"],0);
        assert!(command(&mut b,json!({"method":"commit","sequence":2})).is_err());
        assert_eq!(command(&mut b,json!({"method":"commit","sequence":1})).unwrap()["acknowledged"],1);
        assert_eq!(command(&mut b,verify).unwrap()["delivery"],"duplicate");
        assert!(command(&mut a,json!({"method":"send","channel":"file","message_id":"f","payload":[]})).is_err());
    }
    #[test] fn failed_authentication_is_terminal() {
        let mut a = PodSyncSession::create(config(true)).unwrap();
        assert!(command(&mut a,json!({"method":"authenticate","proof":vec![0;32]})).is_err());
        assert!(command(&mut a,json!({"method":"proof"})).is_err());
    }
}
