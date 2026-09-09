//! Deterministic PUBLIC fixture key; never use it for real pairing.
use podjs_runtime::sync_auth::{handshake_proof, AuthenticatedSession, SessionBinding, Channel};
fn main() {
    let binding = SessionBinding { version: 1, app_id: "example.app".into(),
        initiator: "phone".into(), responder: "watch".into(),
        initiator_nonce: [1; 32], responder_nonce: [2; 32] };
    let initiator = handshake_proof(&[7; 32], &binding, true).unwrap();
    let responder = handshake_proof(&[7; 32], &binding, false).unwrap();
    let session = AuthenticatedSession::open([7; 32], binding.clone(), true, &responder).unwrap();
    println!("{}", serde_json::json!({ "binding": binding, "initiator": initiator,
        "responder": responder, "sessionId": session.session_id(),
        "frame": session.sign(1, Channel::State, "state-1".into(), vec![0, 127, 128, 255]).unwrap() }));
}
