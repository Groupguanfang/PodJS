# Authenticated state channel v1

Use the existing authenticated session frame with outer `channel: state` and
the durable batch `messageId`. Its payload is the exact UTF-8 JSON from the
native sender:

```json
{"version":1,"from":0,"to":1,"entries":[{"key":"title","value":"hello","counter":1,"deviceId":"phone","deleted":false}]}
```

Each batch advances the application replication cursor by one, independently of
entry count or transient session frame sequence. It contains at most 512 entries
and 256 KiB UTF-8 JSON. Sender persistence precedes send. Pending ID/bytes survive
reopening unchanged; fresh sessions sign them using fresh frame sequences.

The receiver takes peer identity from the authenticated connection, strictly
decodes UTF-8 and validates body version/range and entries. State, receive cursor
and last applied batch ID/SHA-256 commit in one SQLite transaction. Only then may
it commit the session frame sequence and send an ACK.

## ACK

The ACK is an authenticated `ack` frame with the same batch `messageId` and a
41-byte payload:

- byte 0: `3`, disjoint from message ACK kinds 1 and 2;
- bytes 1–8: acknowledged `to` cursor, big-endian, restricted to the nonnegative
  JavaScript-safe integer range;
- bytes 9–40: SHA-256 of the exact state batch payload bytes.

Sender progress changes only when authenticated peer, batch ID, cursor and
digest all match the pending batch. ACKs are not themselves acknowledged.
Unmatched ACKs leave outgoing progress unchanged.

One outstanding batch per peer allows the receiver to retain only the last
applied receipt. Exact replay can receive another ACK after restart; changed
bytes/ID at the same cursor, an older range, or a gap fail closed. Do not mix the
lower-level `receive` merge API with `receiveBatch` for the same replication peer:
only the latter stores wire receipts. Storage failure closes the connection
without acknowledging; reconnect and retry the durable pending batch.

## Host integration

Android `PodSyncStatePump` connects this format to `PodSyncConnections` and
`PodSyncStateStore`. It owns no thread or timer. The caller drives `sendNext` and
serial `receiveOne` with backpressure. For mixed traffic, use
`PodSyncChannelPump`, which routes state, messages and their disjoint ACK kinds
inside one receive/apply critical section. Unknown ACK kinds or file frames
are rejected without committing. The standalone state pump remains state-only.

OWW242 instrumentation verifies 600-entry authenticated loopback TCP transfer,
bidirectional state, lost ACK followed by store/session reopening and receipt/
state transaction faults. Further shared-dispatch tests verify same-ID ACK
isolation, concurrent consumers and failure barriers. Initial pairing UI,
physical wireless links, file routing, guest services and other host SDKs remain
required work.
