# File requests over authenticated sync

Android `PodSyncFilePump` handles incoming requests on the `file` channel inside
the connection receive/apply lock. The optional incoming-files argument enables
this route in `PodSyncChannelPump`; without it file frames fail closed.

Requests are strict UTF-8 JSON, at most 96 KiB. Every request has integer
`version: 1` and string `method`. Exact fields are required; remote identities,
paths, approval flags and unknown fields are rejected.

| Method | Additional fields | Effect |
| --- | --- | --- |
| offer | manifest | Persist metadata only; never grant consent |
| status | transfer_id | Read journal phase |
| missing | transfer_id | Read missing chunk indexes after local acceptance |
| chunk | transfer_id, index, data_base64 | Persist hash-verified chunk |
| finish | transfer_id | Verify whole file and persist completion |
| cancel | transfer_id | Persist cancellation and release stored data |

The manifest uses the storage contract's five fields. Chunk indexes are integers;
base64 must be canonical padded standard base64 with no whitespace, and decoded
chunks are at most 64 KiB. Peer identity comes only from the authenticated
connection. There is deliberately no remote `accept` operation.

After durable application, the receiver commits the transient session sequence
and sends a `file` reply using the request frame's `messageId`:

```json
{"version":1,"type":"reply","request_sha256":"<SHA-256 of exact request bytes>","value":{"phase":"accepted"}}
```

`missing` additionally returns a `missing` array inside `value`. No native paths,
exception text or local identity inventory are serialized. Invalid requests or
failed persistence close the connection without a success reply. The sender must
retain requests across uncertainty and validate reply ID, digest and authenticated
peer before persisting its own progress. Retries use transfer identity, immutable
manifest and chunk hashes; repeated cancel/finish remain idempotent. Status replies
are observations and can change after local approval or cancellation.

## Durable outgoing requests

`PodSyncFileRequests` stores request bytes and generated UUIDs in application-private
no-backup SQLite with FULL synchronous transactions. One pending request per peer
prevents dependent commands overtaking it. `sendFile()` resends that same pending
request; the connection still generates a fresh authenticated frame sequence.
The optional sixth argument to `PodSyncChannelPump` enables outgoing requests and
reply consumption. Incoming and outgoing file support can be enabled separately.

Replies must match authenticated peer, request ID and exact request SHA-256. The
method-specific value is checked (including sorted bounded missing indexes).
Only the first valid observation is persisted; a repeated reply cannot overwrite
it with newer remote status. The receiver commits its session sequence only after
that transaction succeeds. Unknown/forgotten IDs are stale and do not alter another
request. Replies are not themselves replied to.

Completed observations remain discoverable through `completed(peer)` across reopen;
the host must durably advance its transfer state before `forgetCompleted`. Pending
records cannot be forgotten. Up to 128 records and 8 MiB are retained, with reserved
space for each bounded reply; there is no automatic eviction. The host must consume
observations to release this quota.

Source snapshots and the host persistent transfer state machine are now connected
by `PodSyncOutgoingFiles`; see [sender lifecycle](sync-file-sender.md). Receiver-side
cancellation returns `cancelled` for an in-flight chunk/missing/finish, allowing the
sender to terminate; a cancelled missing reply includes an empty missing array.
Reconnect scheduling, approval UI and phone SDK wiring remain required. These
host primitives do not advertise the file capability as production-ready.
