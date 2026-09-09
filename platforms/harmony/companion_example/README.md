# Harmony phone companion example

Build with `scripts/build-harmony-companion.ps1 -Example` on the DevEco host.
The unsigned HAP is copied to
`dist/harmonyos-companion-example/podjs-companion-example-unsigned.hap`.
This is a separate phone/tablet entry module; the wearable runtime entry is
unchanged. Both currently use the repository's shared AppScope bundle settings.

Implemented entry paths:

- A random local device identity is flushed to preferences before opening the
  durable SDK channels. Invalid saved identity is rejected, not regenerated.
- The state tab saves `demo.note` locally under app `podjs.companion.demo` and
  observes durable changes. Remote updates refresh a clean editor but preserve
  unsaved drafts. Replacing a draft with saved content requires confirmation.
- The outgoing tab invokes the real system document picker and imports into
  the native outgoing store; import does not imply transmission.
- Staging entries can select the original document again to resume the same
  transfer ID; mismatched content is rejected. Cleanup requires a confirmation
  and only removes the app-private copy, retaining its ID tombstone.
- The receive tab embeds the consent/preview panel with the same client receiver.
- Background/destroy closes a connection if one exists, without deleting queues.
- The connection tab selects a stored approved application peer and either waits
  for BLE or discovers and explicitly selects a fresh nearby route. The saved
  signer authenticates the application peer before channels attach. A connected
  session survives tab navigation, ends on background or after two minutes, and
  never reconnects automatically. Connection status is displayed live.
- Complete outgoing sources have a send/resume button. Use the same file after
  the peer grants consent; replies then advance chunks automatically. Refresh
  progress to read the retained terminal receipt. Explicitly confirming that
  receipt releases the peer's queue for another file and deletes only the local
  receipt, not source bytes or peer files. A different file cannot replace a
  pending transfer. Receiver completion is not proof of export to shared storage.
- The messages tab explicitly selects an approved recipient and saves ordinary
  text messages with a one-day expiry, including while disconnected. The example
  payload is UTF-8 `podjs-text-v1\n` followed by 1–4096 UTF-16 code units of text;
  bidi overrides and non-text controls are rejected. Other application payloads
  remain unsupported, are not executed, and cannot be acknowledged by this UI.
  Refresh shows up to 50 outgoing and 100 pending incoming messages. Opening or
  refreshing does not acknowledge messages. Mark-as-read requires confirmation
  and a connection to that exact peer; it durably marks the inbox and writes an
  ACK, without claiming that the peer received the ACK. Lost ACKs recover when
  the sender retransmits after reconnect. Disappearance from the pending list is
  not read proof because messages can also expire.
  An uncertain enqueue retains its original ID/content/expiry for explicit retry
  while preserving newly edited text. Abandoning retry only clears transient UI
  retry state, never deletes or recalls a possibly queued message. Retry state is
  not persisted across process death; inspect the durable queue before resending.
- The invitation tab defaults to independent BLE. Generate the QR on the issuer,
  scan on the other device, and enter the scanner's displayed application identity
  on the issuer. The issuer then starts waiting (up to 30 seconds of advertising).
  On the scanner, explicitly discover nearby PodJS services for five seconds,
  select a candidate, and connect. Candidate routing expires after 30 seconds and
  is rechecked after permission prompts; names/addresses do not establish trust.
- The optional `linkEnhance` mode still takes a manually entered peer address.
  Both devices must choose the same transport. Switching modes clears discovered
  addresses; the UI never reuses a BLE virtual address for a distributed route or
  silently falls back to another transport. Scan/connection permissions are only
  requested after the user's action; unsupported APIs produce a failure.
- After the invitation challenge succeeds, each side must approve the displayed
  application/local/peer identities. Credentials are saved only by the initial
  pairing protocol, never by scanning alone. Cancellation, tab changes, and app
  background dismiss the prompt and cancel the attempt. A failed final receipt
  warns that credentials may exist and should be checked on both devices.
  Each approval button callback is bound to the displayed request, so an old
  callback cannot answer a replacement prompt. Background/expiry also cancel an
  in-progress discovery and discard its late results.

End-to-end wireless operation, permission/background transitions, layout and
screen-reader behavior have not been device-validated. The unsigned HAP
requires appropriate device signing before installation. SDK compilation and
packaging do not prove device UI or accessibility acceptance.
