# File receiver storage boundary

Android `PodSyncFileReceiver` wraps the host-only Rust file receiver. Call it on
an IO worker only after authenticating/authorizing a peer. It does not implement
the public offer/accept UI, sender, wire dispatcher or guest service route. The
host consent journal described below is now available.

Storage is beneath the application's no-backup directory, partitioned by
host-provided application and peer storage IDs. These IDs currently use the
restricted `[A-Za-z0-9_-]{1,128}` form, not arbitrary paths; production adapters
must bind logical app/device identities to stable valid storage IDs.

The native receiver validates manifests, per-chunk hashes and final SHA-256. A
chunk is 64 KiB; the JSON command carries standard base64 and is capped at 96 KiB.
Received chunks persist independently; reopening rechecks them, discards known
temporary files and reports missing chunks. Assembly is synced and renamed
atomically. The native `finish` path is host-private and must not be sent to a
remote peer as a public result.

## Application-wide quota

The native root is per peer, so its own quota check is insufficient to enforce
the app-wide limit. Android additionally holds `@quota.lock` around native open
recovery and all commands. New offers scan reservations across every peer root
under that same app directory:

- at most 16 MiB per file;
- at most 32 MiB reserved file data and 128 transfers across the application;
- incomplete transfers reserve two copies for chunks plus assembly;
- a completed transfer reserves one copy only after length and SHA-256 verify;
- actual retained data larger than the expected reservation is counted;
- metadata/lock files are excluded from the data quota;
- duplicate offers remain subject to native manifest identity validation;
- cancellation releases the transfer reservation; lock contention or malformed
  storage rejects before admitting new data.

The peer ownership lock remains held for receiver lifetime. The app lock is
short-lived per operation, permitting multiple peer receivers while serializing
their quota decisions and filesystem mutations. A failed open releases any
partially acquired native handle and peer lock.

OWW242 instrumentation verifies multi-peer quota enforcement, cancel/reuse,
rejection of a corrupt completion marker, application-lock contention, reopen,
chunk validation and authenticated file-offer-before-ACK ordering. Full wireless
file transfer, process-kill resume and other host implementations remain open.

## Incoming consent journal

`PodSyncIncomingFiles` stores offers in app-private SQLite, keyed by authenticated
peer and transfer ID. It maps logical app/device identities to stable SHA-256
storage IDs, permitting dotted bundle/device identities without accepting paths.
Manifests are normalized and checked before persistence; changed contents cannot
reuse an existing peer/transfer identity.

An offer creates only an `offered` row. No native receiver or payload storage is
opened until a local host `accept` call. The remote peer must never be allowed to
invoke this acceptance API to approve itself. Acceptance writes `accepting`
before native reservation, then `accepted` after success. Cancellation similarly
persists `cancelling` before native deletion, then `cancelled`. Absent transfers
are idempotently cancelled only after checking absence under the app file lock.
Cancelled offers do not silently become new offers on replay.

The journal's separate app lock spans SQLite intent, native IO and result writes.
`recover` handles cancellations before waiting acceptances, releasing quota before
retrying reservations. Construction validates storage but does not automatically
retry IO: a quota-blocked acceptance must not prevent reopening to cancel it.
Only accepted/complete transfers admit chunk, missing and finish operations. Native
finish paths stay in the host-only `completedFile` API, not transfer status.

The current journal retains at most 128 offer identities, including terminal
records; terminal metadata collection and event delivery are not connected yet.
OWW242 tests cover consent isolation, verified chunk/reopen/final bytes, manifest
mutation, acceptance/cancellation commit failures and quota-release-first recovery.
These tests do not replace a public accept UI or authenticated file wire exchange.
# Local consent discovery

`PodSyncIncomingFiles.pendingConsent()` returns a deterministic, application-scoped
snapshot of offers still awaiting local approval, across authenticated peers. It
survives reopening the journal, does not open a native receiver or recover an
acceptance intent, and excludes accepted/cancelled transfers. The list is immutable
and each manifest is detached from storage. This is a host-only UI input, never a
cross-peer remote command. OWW242 instrumentation covers restart, app isolation,
snapshot mutation, and removal after acceptance/cancellation; the combined incoming
journal/receiver suite passes 11 tests. The approval UI itself remains pending.

## Outgoing source snapshots

`PodSyncFileSnapshots` freezes a host-selected regular file into a verified native
snapshot. It streams two passes through one open descriptor, computes the whole
hash and 64 KiB chunk hashes, and publishes only after native verification. A
changed/truncated source fails verification; successful snapshots remain usable
after modifying or deleting the original. Symlink sources and files over 16 MiB
are rejected. Reads return verified chunk bytes, not a remotely visible host path.

Snapshots use the same hashed application storage root and application quota lock
as incoming transfers, in the reserved `outgoing-snapshots` peer directory. Creation
reserves two copies for assembly, falling to one verified copy after completion.
Sending and receiving therefore share the 32 MiB/128-transfer quota. Failed creates
attempt cancellation; interrupted snapshots remain discoverable through `inventory`
and can be explicitly discarded. `get` requires verified completeness before use.

OWW242 snapshot/consent/receiver instrumentation passes 14 tests, including source
mutation/deletion/reopen, full and tail chunks, empty files, oversized and symlink
source rejection, corruption rejection, and both directions of shared quota denial.
The outgoing coordinator now retains a `PodSyncFileSnapshots.Reader` namespace
lease and file descriptor across chunks, avoiding repeated native recovery scans.
Chunk hashes are still checked on every read; deletion is blocked until the reader
closes. See [the connected sender state machine](sync-file-sender.md).
