# Android persistent outgoing transfers

`PodSyncOutgoingFiles` connects verified source snapshots with the authenticated
file request/reply queue. This remains a host-worker API, not a guest service or
phone companion UI. It creates no thread, polling timer, or wireless connection.

The host creates a `PodSyncFileSnapshots` snapshot, calls `start(peer, snapshotId)`,
then calls `advance(peer, pollConsent)` and `PodSyncChannelPump.sendFile()`. After
receiving a file reply through the shared dispatcher, it calls `advance` again.
Pending requests are returned unchanged after reopen/reconnect. While awaiting
approval, `advance(peer, false)` does not enqueue a poll; the host may explicitly
request a status refresh with `true`.

Transfers progress through offer, waiting, missing, chunks, finish and complete.
Missing indexes are bounded by the actual immutable manifest. Each confirmed chunk
removes one index. Finishing requires the receiver's verified complete response.
`list(peer)` rebuilds the host's transfer inventory after reopening.

Transfer rows and RPC rows share one FULL-synchronous SQLite database. Consuming
the previous reply, updating progress, removing its RPC record and creating the
successor request occur in one transaction. Failed successor insertion leaves
the previous observation and progress intact. Do not manually forget RPC records
owned by this coordinator. One active transfer per peer and 128 retained transfer
rows are allowed; terminal journal garbage collection is not implemented yet.

Cancellation before an offer is enqueued is local. Otherwise the intent survives
reopen and waits for the outstanding request's resolution before sending cancel.
If the receiving host cancels while a chunk/missing/finish request is in flight,
the receiver replies `cancelled` without recreating data. A successful remote
cancellation makes the sender terminal, instead of retrying that chunk forever.

The coordinator retains a verified snapshot reader through consecutive chunks,
so native recovery does not rerun for every chunk. The namespace lease prevents
concurrent deletion; close the coordinator to release it. Reopening verifies again.
`releaseSnapshot` explicitly discards a source only after every known peer transfer
for it is terminal. Terminal identities remain in the journal; releasing a source
does not delete the receiver's completed file. Cancelling a completed transfer is
the separate operation that deletes remote data.

Device tests exercise a 64 KiB+tail transfer over HMAC-authenticated loopback TCP,
approval waiting, completion and later cancellation, receiver-side cancellation of
a queued chunk, database reopen and rollback under injected successor write failure.
This is not yet a wireless two-device or process-kill acceptance result. Persistent
connection scheduling, initial pairing/approval UI, guest events/services, target
profile quota tightening and the remaining platform SDKs still need integration.

`PodSyncFileReconnectTest` additionally recreates every connection, queue, incoming
journal and coordinator across three sessions while transferring 131,079 bytes.
It drops a reply after chunk 0 is persisted, then drops the final completion reply
in the next session. Reopened senders retain the exact request ID and bytes; fresh
session IDs and sequence 1 are verified. A second case injects sender reply-storage
failure and confirms that the failed session closes with its chunk still pending.
Both OWW242 tests pass, completing byte-identical files after the final reconnect.
These tests close resources normally between cycles; process-kill recovery is
tested separately by the explicit probe below.

## Explicit process-kill probe

`PodSyncFileReconnectTest#fileCrashProbe` is skipped during ordinary test runs. With
explicit seed arguments it writes and fsyncs a test checkpoint only after chunk 0
is durable and the outgoing RPC is still pending, then SIGKILLs its own test process.
It does not close the coordinator, SQLite stores, native reader lease or sockets.
The resume invocation requires a different PID, verifies the saved request ID/hash
and missing indexes, opens fresh authenticated sessions and completes the file.

On a Linux development host with adb/jq and the current test APK installed, run:

```sh
bash scripts/test-android-file-crash.sh DEVICE_SERIAL
```

The runner selects only the supplied device, refuses an already-running test
process, requires a matching readiness marker and persisted checkpoint, confirms
the seed process is gone before resuming, and checks the final recovery record.
It never kills another process itself. OWW242 manual and scripted probes both
passed: a new PID completed all 131,079 bytes across three distinct sessions after
the SIGKILL checkpoint. This proves that interruption point, not arbitrary in-write
crashes, power loss, or wireless two-device recovery.
