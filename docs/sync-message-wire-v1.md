# Message channel wire encoding (implementation draft)

This describes the currently implemented Android message pump, not completed
cross-platform SDK or wireless acceptance. RFCOMM/LAN framing is four-byte
unsigned big-endian length followed by opaque frame JSON (maximum 2 MiB).
Authentication uses the shared Rust session implementation. No encryption is
provided by this framing or HMAC layer.

## Signed frame

Frames contain `protocolVersion`, `sessionId`, `channel`, `messageId`, `sequence`,
`payload` and `tag`. Payload and tag serialize as JSON arrays of unsigned bytes.
The authentication implementation is the canonical source for the HMAC transcript.
Frame payload is bounded to 256 KiB + 1 KiB for channel metadata; application
message bytes remain capped at 256 KiB. A new session has fresh challenges and
sequence numbers, while application message IDs survive reconnection.

## Message payload

The signed `message` channel payload is binary, in this order:

- Eight-byte big-endian expiry timestamp in Unix milliseconds, nonnegative and
  at most JavaScript's maximum safe integer.
- One priority byte: 0 for normal, 1 for high.
- The already encoded application JSON bytes, at most 256 KiB. The storage and
  transport layers treat these bytes as opaque; the app-facing adapter must
  validate its JSON schema before enqueueing or applying.

The envelope is exactly nine bytes longer than the application bytes. Its stable
message ID is the signed frame's `messageId`; its peer is taken from the
authenticated connection, not from the application payload.

## Message ACK payload

The signed `ack` channel frame repeats the original message ID and contains:

- One status byte: 1 means applied, 2 means expired without application.
- SHA-256 (32 bytes) of the exact original message envelope above.

ACKs are not themselves acknowledged. The sender checks both authenticated peer
and content digest before deleting its durable outgoing message. A stale ACK with
the same ID but different message contents fails closed. Do not reuse message IDs:
the receiver retains applied receipts until expiry and rejects changed content.

## Commit and retry ordering

Receive authentication does not advance the session's received sequence. The
message pump persists the inbox entry, invokes idempotent business work, persists
the applied receipt, commits the session sequence, then sends the signed ACK.
A callback failure closes the session without an applied ACK. The durable pending
entry remains available for retry. A crash between business work and the receipt
write can re-invoke the callback, so business writes must be idempotent by peer/ID.

After reconnect, the sender reads the unchanged outbox and sends the same message
ID/content in a freshly signed frame. An applied inbox receipt suppresses the
business callback and generates another ACK. Expired records can be purged; active
receipts are not evicted to admit new traffic. Queue reads never mark messages sent.

`sendNext`/`receiveOne` are bounded host-driven operations, not a background polling
service. The host must provide timeout, cancellation and send backpressure. This
message-only dispatcher must not receive state or file frames. Android
`PodSyncChannelPump` now multiplexes state and message traffic on one connection,
including ACK kinds 1/2 for messages and 3 for state. File dispatch, initial
recovery negotiation and ACK batching remain pending.

Both standalone pumps and the shared dispatcher use a connection-owned receive
critical section covering verification, durable application, sequence commit and
ACK send. Concurrent callers cannot verify a later frame before an earlier one
commits; reentrant receive closes the connection. Application callbacks must not
wait for another receive on the same connection. Low-level `Connection.receive`
is still intended only for hosts that explicitly manage their own commit order.
# Deferred application completion

Android's shared channel pump additionally supports `receiveOneDeferred(now)`.
For a pending message, it persists the inbox and commits only the authenticated
transport sequence; it sends no business ACK. Further state/file frames may still
be consumed. This does not remove the message from the sender's durable outbox.

An explicit application acknowledgement binds peer, message ID and the SHA-256
of expiry/priority/payload to the current inbox record, then persists `applied`
before sending kind 1 ACK. Expired or changed deliveries are rejected. Applied
receipts remain until expiry, so a lost ACK or offline application completion is
answered on sender retry without reapplying business work. The original synchronous
handler path remains available for hosts that persist work before returning.
