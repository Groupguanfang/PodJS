# Native replicated state storage

Android `PodSyncStateStore` is a host-only SQLite implementation of the state
merge model in `@podjs/framework/sync-state`. It is not yet a wireless transport
or a guest `sync.state.*` service route.

- The host supplies app and local device identities. Storage is app-private,
  excluded from backup, and bound to the device identity established on first
  open. A different identity cannot reopen and silently reset the logical clock.
- `set` and `delete` increment the persisted logical clock. `delete` retains a
  tombstone. Entries merge by `(counter, deviceId)`, with ASCII identity ordering.
  Conflicting values at the same revision reject the whole batch.
- `receive(peer, from, to, entries)` must receive its peer identity from an
  authenticated connection. Entry authors may differ from that peer for relayed
  state. The cursor and merged entries commit in the same SQLite transaction.
  The caller may acknowledge only after this method returns successfully.
- A duplicate committed range returns the current cursor; a gap rejects without
  mutation. Application replication cursors are separate from transient signed
  session frame sequences. Sender batch/range and ACK bookkeeping are now
  persistent as described below. Android's authenticated state-only route is
  described in [sync-state-wire-v1.md](sync-state-wire-v1.md); state/message
  dispatch is connected, while file routing and initial pairing/recovery
  negotiation are not yet complete.
- Limits: 512 entries per incoming batch, 10,000 retained keys including
  tombstones, 65,536 UTF-16 units per canonical JSON value, nesting depth 32,
  8 MiB UTF-8 serialized snapshot. Logical clocks/cursors use JavaScript-safe
  nonnegative integers. Quota failure never evicts tombstones or advances a cursor.
- Java `null` from `get` means absent/deleted; `JSONObject.NULL` means a stored
  JSON null. Returned JSON values are detached from persisted storage.

Every mutation reloads its snapshot within the database transaction; multiple
handles do not maintain competing in-memory clocks. The 2026-09-08 OWW242 tests
exercise actual SQLite conflict merge, tombstones/reopen, injected transaction
failure/replay, sequence gaps, contradictory revisions, concurrent handles and
device binding. They do not establish cross-device wireless synchronization or
power-loss recovery.

Device test class: `dev.podjs.runtime.PodSyncStateStoreTest` in the Android
runtime instrumentation APK.

## Durable sender

`prepare(peer)` captures an immutable complete set of entries, including
tombstones, in a SQLite transaction. Each call returns the current unacknowledged
batch, or prepares the next batch of at most 512 entries and 256 KiB UTF-8 JSON.
The host must send the returned bytes unchanged on the authenticated state
channel with the returned stable `messageId`. A body has `version: 1`, `from`,
`to` and `entries`; `to` is exactly `from + 1` for this sender.

`acknowledge(peer, messageId, to, sha256)` advances the persistent outgoing
cursor only when all fields match the current batch. The peer must come from
the authenticated connection; SHA-256 alone is not authentication. Wrong or
stale ACKs return false. A failed transaction preserves the original pending
batch and must not be reported as accepted.

The current cycle remains frozen while local changes continue. Once its last
batch is acknowledged, the next `prepare` compares current state against the
last fully acknowledged cycle and captures any new changes. It returns null
when that peer has acknowledged the current complete state. An initial empty
state sends one empty batch. This is full-state replication, not yet an
incremental changed-entry log.

Up to 128 peer cursor rows and 16 MiB of frozen cycle JSON are retained per app;
each peer may additionally retain one bounded pending batch. Unacknowledged
cycles are not evicted. Malformed UTF-16 strings are rejected before Android's
UTF-8 encoder can replace them silently.

`PodSyncStateSenderTest` exercises 600-entry split/reopen/lost-ACK delivery,
changes during an unfinished cycle, recipient/ID/digest/cursor binding, Unicode
byte bounds and injected sender/ACK write failures on OWW242. The state pump now
has separate authenticated loopback tests; physical wireless exchange remains
unverified.
