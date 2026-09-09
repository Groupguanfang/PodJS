//! Authenticated session primitive. The host provisions a random 32-byte pairing
//! key through an authenticated pairing flow and supplies fresh OS-random nonces.
//! HMAC authenticates bytes, not confidentiality; use encrypted transport as well.
use anyhow::{Result, ensure};
use hmac::{Hmac, Mac};
use sha2::{Digest, Sha256};
use serde::{Deserialize, Serialize};

type HmacSha256 = Hmac<Sha256>;
pub const PROTOCOL_VERSION: u32 = 1;
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SessionBinding {
    pub version: u32,
    pub app_id: String,
    pub initiator: String,
    pub responder: String,
    pub initiator_nonce: [u8; 32],
    pub responder_nonce: [u8; 32],
}
impl SessionBinding {
    fn validate(&self) -> Result<()> {
        ensure!(self.version == PROTOCOL_VERSION, "unsupported sync protocol");
        for id in [&self.app_id, &self.initiator, &self.responder] {
            ensure!(!id.is_empty() && id.len() <= 128 && id.bytes().all(|b| b.is_ascii_alphanumeric() || b"_.:-".contains(&b)), "invalid session identity");
        }
        ensure!(self.initiator != self.responder, "identical peer identities");
        ensure!(self.initiator_nonce != [0; 32] && self.responder_nonce != [0; 32] && self.initiator_nonce != self.responder_nonce, "invalid challenge");
        Ok(())
    }
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Channel { State, Message, File, Ack }
#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
pub struct AuthenticatedFrame {
    pub protocol_version: u32,
    pub session_id: String,
    pub sequence: u64,
    pub channel: Channel,
    pub message_id: String,
    pub payload: Vec<u8>,
    pub tag: [u8; 32],
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Delivery { Duplicate, Pending }
pub struct AuthenticatedSession {
    binding: SessionBinding,
    session_id: String,
    key: [u8; 32],
    local_is_initiator: bool,
    received: u64,
    pending: Option<(u64, [u8; 32])>,
}
fn mac(key: &[u8; 32], domain: &[u8], binding: &SessionBinding, sender_is_initiator: bool, body: &[u8]) -> Result<HmacSha256> {
    let mut hmac = HmacSha256::new_from_slice(key).expect("fixed key length");
    let encoded = serde_json::to_vec(binding)?;
    hmac.update(domain); hmac.update(&(encoded.len() as u32).to_be_bytes()); hmac.update(&encoded);
    hmac.update(&[sender_is_initiator as u8]); hmac.update(body); Ok(hmac)
}
pub fn handshake_proof(key: &[u8; 32], binding: &SessionBinding, sender_is_initiator: bool) -> Result<[u8; 32]> {
    binding.validate()?;
    Ok(mac(key, b"PodJS-handshake-v1", binding, sender_is_initiator, &[])?.finalize().into_bytes().into())
}
impl AuthenticatedSession {
    /// Open only against the exact fresh challenges held locally by the host.
    /// Both peers must independently verify the other peer's proof.
    pub fn open(key: [u8; 32], binding: SessionBinding, local_is_initiator: bool, remote_proof: &[u8]) -> Result<Self> {
        binding.validate()?;
        mac(&key, b"PodJS-handshake-v1", &binding, !local_is_initiator, &[])?.verify_slice(remote_proof).map_err(|_| anyhow::anyhow!("peer authentication failed"))?;
        let session_id = format!("{:x}", Sha256::digest(serde_json::to_vec(&binding)?));
        Ok(Self { binding, session_id, key, local_is_initiator, received: 0, pending: None })
    }
    fn frame_body(frame: &AuthenticatedFrame) -> Result<Vec<u8>> {
        ensure!(frame.protocol_version == PROTOCOL_VERSION, "unsupported sync protocol");
        ensure!(frame.session_id.len() == 64 && frame.session_id.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)), "invalid session id");
        ensure!(frame.sequence > 0 && frame.sequence <= 9007199254740991, "invalid frame sequence");
        ensure!(!frame.message_id.is_empty() && frame.message_id.len() <= 128 && frame.message_id.bytes().all(|b| b.is_ascii_alphanumeric() || b"_.:-".contains(&b)), "invalid message identity");
        // Application message bytes remain capped at 256 KiB; reserve bounded
        // space for channel metadata (expiry/priority) inside the signed payload.
        ensure!(frame.payload.len() <= 256 * 1024 + 1024, "frame payload too large");
        Ok(serde_json::to_vec(&(frame.protocol_version, &frame.session_id, frame.sequence, frame.channel, &frame.message_id, &frame.payload))?)
    }
    pub fn sign(&self, sequence: u64, channel: Channel, message_id: String, payload: Vec<u8>) -> Result<AuthenticatedFrame> {
        let mut frame = AuthenticatedFrame { protocol_version: PROTOCOL_VERSION, session_id: self.session_id.clone(), sequence, channel, message_id, payload, tag: [0; 32] };
        let body = Self::frame_body(&frame)?;
        frame.tag = mac(&self.key, b"PodJS-frame-v1", &self.binding, self.local_is_initiator, &body)?.finalize().into_bytes().into(); Ok(frame)
    }
    /// Validating does not advance the ACK. Call commit_received only after the
    /// application storage transaction succeeds. Failed writes retry this frame.
    pub fn verify(&mut self, frame: &AuthenticatedFrame, allowed: &[Channel]) -> Result<Delivery> {
        ensure!(frame.session_id == self.session_id, "session mismatch");
        let body = Self::frame_body(frame)?;
        mac(&self.key, b"PodJS-frame-v1", &self.binding, !self.local_is_initiator, &body)?.verify_slice(&frame.tag).map_err(|_| anyhow::anyhow!("frame authentication failed"))?;
        ensure!(allowed.contains(&frame.channel), "channel not authorized");
        if frame.sequence <= self.received { return Ok(Delivery::Duplicate); }
        ensure!(frame.sequence == self.received + 1, "sequence gap");
        if let Some((seq, tag)) = self.pending { ensure!(seq == frame.sequence && tag == frame.tag, "pending sequence payload changed"); }
        self.pending = Some((frame.sequence, frame.tag)); Ok(Delivery::Pending)
    }
    pub fn commit_received(&mut self, sequence: u64) -> Result<u64> {
        ensure!(self.pending.is_some_and(|(seq, _)| seq == sequence), "no verified pending frame");
        self.received = sequence; self.pending = None; Ok(self.received)
    }
    pub fn acknowledged(&self) -> u64 { self.received }
    pub fn session_id(&self) -> &str { &self.session_id }
}
impl Drop for AuthenticatedSession { fn drop(&mut self) { self.key.fill(0); } }

#[cfg(test)]
mod tests {
    use super::*;
    fn binding() -> SessionBinding { SessionBinding { version:1, app_id:"example.app".into(), initiator:"phone".into(), responder:"watch".into(), initiator_nonce:[1;32], responder_nonce:[2;32] } }
    fn peers() -> (AuthenticatedSession, AuthenticatedSession) {
        let b = binding(); let key = [7;32];
        let a = AuthenticatedSession::open(key, b.clone(), true, &handshake_proof(&key,&b,false).unwrap()).unwrap();
        let z = AuthenticatedSession::open(key, b.clone(), false, &handshake_proof(&key,&b,true).unwrap()).unwrap(); (a,z)
    }
    #[test] fn verify_does_not_ack_before_commit() {
        let (a,mut b) = peers(); let frame = a.sign(1,Channel::Message,"one".into(),b"hi".to_vec()).unwrap();
        assert_eq!(b.verify(&frame,&[Channel::Message]).unwrap(),Delivery::Pending); assert_eq!(b.acknowledged(),0);
        assert_eq!(b.verify(&frame,&[Channel::Message]).unwrap(),Delivery::Pending);
        assert_eq!(b.commit_received(1).unwrap(),1); assert_eq!(b.verify(&frame,&[Channel::Message]).unwrap(),Delivery::Duplicate);
    }
    #[test] fn tampering_reflection_and_unauthorized_channels_fail() {
        let (mut a,mut b) = peers(); let frame = a.sign(1,Channel::File,"one".into(),vec![1]).unwrap();
        assert!(a.verify(&frame,&[Channel::File]).is_err()); assert!(b.verify(&frame,&[Channel::Message]).is_err());
        let mut bad = frame.clone(); bad.payload[0] = 2; assert!(b.verify(&bad,&[Channel::File]).is_err()); assert_eq!(b.acknowledged(),0);
    }
    #[test] fn changed_challenge_wrong_key_and_sequence_gap_fail() {
        let mut bound = binding(); let proof = handshake_proof(&[7;32],&bound,true).unwrap();
        bound.responder_nonce = [3;32]; assert!(AuthenticatedSession::open([7;32],bound,false,&proof).is_err());
        assert!(AuthenticatedSession::open([8;32],binding(),false,&proof).is_err());
        let (a,mut b) = peers(); let gap = a.sign(2,Channel::State,"two".into(),vec![]).unwrap();
        assert!(b.verify(&gap,&[Channel::State]).is_err()); assert!(b.commit_received(2).is_err());
    }
    #[test] fn wire_identity_is_explicit_and_old_sessions_are_rejected() {
        let (a, mut b) = peers();
        let frame = a.sign(1, Channel::State, "state-1".into(), vec![]).unwrap();
        let wire = serde_json::to_value(&frame).unwrap();
        for key in ["protocolVersion", "sessionId", "channel", "messageId", "sequence", "payload"] {
            assert!(wire.get(key).is_some(), "missing {key}");
        }
        assert_eq!(wire["sessionId"], b.session_id());
        let mut future = frame.clone(); future.protocol_version = 2;
        assert!(b.verify(&future, &[Channel::State]).is_err());
        let mut bound = binding(); bound.responder_nonce = [4; 32];
        let proof = handshake_proof(&[7; 32], &bound, true).unwrap();
        let mut reconnected = AuthenticatedSession::open([7; 32], bound, false, &proof).unwrap();
        assert_ne!(reconnected.session_id(), a.session_id());
        assert!(reconnected.verify(&frame, &[Channel::State]).is_err());
        assert_eq!(reconnected.acknowledged(), 0);
        assert_eq!(b.verify(&frame, &[Channel::State]).unwrap(), Delivery::Pending);
    }
}
