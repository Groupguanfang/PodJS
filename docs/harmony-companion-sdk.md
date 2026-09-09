# Harmony companion SDK — state layer

`createCompanionState(context, appId, deviceId)` in
`@podjs/companion` constructs a
host-only, lazy state SDK backed by the native app-isolated CAS journal. Creation
does not perform IO, request permissions, pair, scan or connect. The host must
provide an approved application identity and stable local device identity.

Implemented operations are `get`, `set`, `delete`, `snapshot`, local `subscribe`
and `receiveAuthenticated`, plus durable sender `prepare` and
`acknowledgeAuthenticated`, and receipt-bound `receiveBatchAuthenticated`.
Missing/deleted keys return `undefined`; stored JSON
null returns `null`. Values and subscriber snapshots are detached. Listener
exceptions cannot reverse a committed transaction. Subscriptions observe this
instance's successful mutations, not changes from other processes.

State entries use the common `(counter, deviceId)` ordering. Deletes retain
tombstones. Equal revisions with different values reject the complete batch.
Ingress commits merged state and its receive cursor together; the caller must
not ACK until the returned promise resolves. A repeated range returns the
committed cursor; a gap rejects. Only an already authenticated, app/channel-bound
session may call ingress. This method does not itself establish authentication.

Every operation reloads disk state. Native CAS rejects a competing write instead
of overwriting it; callers may retry after reloading. Storage failure, including
an uncertain post-rename failure, rejects the operation without a success event
or ACK. There is no automatic transaction callback replay or clock rollback.
Successful writes bind the journal to both app and local device identities.

The persisted envelope is `{schema: 3, appId, localDeviceId, state, outgoing, incoming}`.
Old schema-1 state-only and schema-2 sender journals migrate on a successful write
without resetting state, receive cursors or pending sends. Old schema markers
containing newer bookkeeping fields are rejected rather than silently discarding
those fields.
The nested
state snapshot follows the reference model. This envelope is intentionally not
the bare snapshot used by the portable TypeScript experimental adapter; opening
the other format fails closed instead of migrating or resetting it silently.

Limits are 512 entries per received batch, 10,000 retained keys, depth 32 and
65,536 UTF-16 units per canonical JSON value, 128 receive peers and an 8 MiB
UTF-8 journal. Quota failure does not evict tombstones or advance cursors.
The same journal budget includes frozen outgoing cycles; at most 128 outgoing
peers are retained, independently of the receive-peer limit.

## Durable sender

`prepare(peer)` freezes a complete state cycle and persists one bounded pending
batch before returning it. Its payload is the exact JSON state body from
[state wire v1](sync-state-wire-v1.md), bounded to 512 entries and 256 KiB UTF-8.
Reopening returns the same message ID, payload, from/to cursors and SHA-256.
Local edits during an outstanding cycle are captured in the next cycle, not
silently folded into an already prepared batch. Initially empty state still
emits one empty batch. Once the peer confirms unchanged state, prepare returns
null. The native factory supplies system SHA-256 and random 128-bit IDs; a raw
constructor without a crypto port cannot prepare or acknowledge pending sends.

After authenticating and decoding a state ACK, call
`acknowledgeAuthenticated(peer, messageId, to, digestHex)`. Only an exact match
advances the persisted cursor. A failed/uncertain commit rejects; reload on the
next operation. Sender bookkeeping does not emit state subscription callbacks.
These lower-level batch methods do not authenticate connections or run transport
IO; the foreground synchronizer described below composes them with an authenticated session.

## Wire receipts and ACK payloads

`receiveBatchAuthenticated(peer, messageId, payload)` takes the exact JSON text
strictly decoded from the authenticated frame's UTF-8 bytes. It atomically stores
state, receive cursor and the last `(from, to, messageId, SHA-256)` receipt. Only
exact replay of that last receipt can be ACKed again after a restart; changed
bytes/ID, stale batches, reused IDs and gaps reject without advancing progress.
Never pass unverified peer IDs or lossy-decoded text. Once a peer has wire
receipts, the lower-level `receiveAuthenticated` API rejects that peer to avoid
invalidating the receipt cursor.

`encodeStateAck(cursor, digestHex)` and `decodeStateAck(bytes)` implement the
41-byte kind-3 ACK payload: one kind byte, an eight-byte big-endian safe-integer
cursor and 32 digest bytes. Other kinds, wrong lengths and unsafe counters reject.
These helpers do not provide the authenticated outer envelope or bind its peer
and message ID; the session layer must do that. Commit a verified session frame
only after the storage operation resolves, then send its ACK. A lost ACK is
handled by replaying the sender's durable pending batch.

Verification:

- `bun run test:companion`: reference-model cross-checks and actual C++ N-API
  file backend, including second-process reopen, tombstone and cursor retention.
- `bun test tests/harmony-*.test.ts`: Harmony regression suite.
- Windows DevEco: explicit factory/core import probe compiled and packaged;
  a second successful build removed the probe from the normal unsigned HAP.

The state implementation now lives in `platforms/harmony/companion`, an
independent HAR module. The watch host has a source dependency on that module;
the exported HAR has also passed a consumer build using the archive dependency
with the source module removed from the build profile.

Build on Windows with DevEco:

```powershell
./scripts/build-harmony-companion.ps1
```

Output: `dist/harmonyos-companion/podjs-companion.har`. This state-layer artifact
contains the ArkTS exports, native type declarations, ARM64
`libpodjs_companion.so` and `libc++_shared.so`. The native library depends only on
the system N-API library, libc and the bundled C++ runtime, not the watch renderer
or Rust runtime. Current metadata requires Harmony API 23; only ARM64 is built.
The HAR is an unsigned development build, not a published complete SDK release.

For a consumer, copy it to that module's `libs/podjs-companion.har` and add an
`oh-package.json5` dependency:

```json
"@podjs/companion": "file:./libs/podjs-companion.har"
```

Import `createCompanionState` from `@podjs/companion`. Construction does not
register a connection or start synchronization.

These checks do not prove device power-loss recovery or wireless synchronization.
Device-verified encrypted connections, message/file channels
and the phone companion example are still required. No new target
capability is enabled by this work.
# Authentication primitive

`CompanionSyncHandshake` and `createCompanionSyncHandshake` implement the Rust
v1 handshake MAC: canonical app/peer/challenge binding, role-separated HMAC-SHA256
proofs and SHA256 session ID. The factory uses Harmony CryptoFramework; unavailable
crypto rejects the operation. It does not exchange messages or establish a transport.
Provision the 32-byte nonzero key through authenticated pairing and supply fresh
OS-random, distinct, nonzero 32-byte challenges for every connection. HMAC does not
encrypt traffic. Failed authentication closes the object; explicit close suppresses
late crypto completions and wipes owned key arrays, not OS/VM internal copies.

The regression runs Rust's deterministic public-key fixture generator and compares
both role proofs and the session ID exactly. Native CryptoFramework execution on
a device is still unverified; HAR compilation alone is not runtime validation.
`CompanionSyncSession` / `createCompanionSyncSession` add authenticated frame
send/verify/commit and host-approved channel grants. Calls serialize sequence
changes; inputs are copied before queueing. Keep the exact returned frame for
same-connection retransmission. A new connection needs fresh challenges and new
frame signatures for the durable outgoing batch. `verify` returns pending without
advancing the acknowledged sequence; call `commit(sequence)` only after durable
application storage succeeds. Invalid authenticated frames close the session;
an incorrect commit does not consume the pending frame. Rust-generated frames
match byte-for-byte in regression tests. The state-only foreground driver and
pinned TLS client connector are described below; device validation remains open.

`CompanionStatePump(session, state)` now routes dedicated state/ACK frames through
the authenticated session and durable state store. It checks app/local identities
and derives the remote peer from the session binding. `sendNext()` returns a signed
batch or null; `receive(frame)` returns applied/duplicate/ack/stale_ack and an
optional signed ACK to write. Storage commits precede transient sequence commits.
Any routing/storage failure closes the pump and session. The caller owns transport
IO, must close on failed writes, and must not share this dispatcher with message or
file traffic. Retain exact frames for same-session retransmission; after reconnect,
use fresh challenges and call sendNext to re-sign the retained durable batch.
State payload decoding rejects malformed UTF-8, overlong sequences and surrogate
code points rather than substituting characters before hashing. Tests cover a lost
ACK across fresh-session reopen and failures on both receiver and sender storage.
These tests use injected storage and crypto, not a real wireless connection.

`encodeSyncPacket` and `CompanionSyncFraming` implement the Android stream's
unsigned big-endian four-byte length prefix and 1..2 MiB opaque frame budget.
The incremental decoder handles arbitrary chunk boundaries and multiple packets
per chunk while retaining only one bounded incomplete payload. `feed` invokes a
synchronous callback for each owned complete payload; the transport adapter must
apply backpressure and must not enqueue unlimited async callback work. Malformed
lengths, truncated EOF, reentrant feeding and callback failures close the decoder.
Call `finish()` on EOF to distinguish a clean boundary from a truncated packet.
Framing does not authenticate, decode JSON, negotiate hello messages, or provide
socket IO. Completed bytes still need strict envelope decoding and session checks.

`CompanionSyncExchange` / `createCompanionSyncExchange` now exchange Android's
seven-field hello and two-field proof envelopes over `CompanionSyncPacketStream`.
The native factory generates a fresh 32-byte OS challenge. The caller supplies
previously approved app/local/remote identities, pairing key and channel grants;
there is no discovery, trust-on-first-use or automatic pairing. The responder
validates the remote proof before writing its own proof. Failure closes both its
session and stream, and owned temporary key bytes are wiped. Explicit close
suppresses late random/IO/crypto results. Successful establish hands the session
to the caller, who must then own both session and stream cleanup.

Packet stream implementations must configure a bounded handshake deadline and
interrupt pending reads/writes on close. Packet payloads exclude the length prefix. Handshake packets are capped at
8 KiB and strictly decoded as UTF-8 JSON objects; duplicate root fields, including
escaped aliases, are rejected. `decodeSyncObject` also supports a caller-selected
budget up to 2 MiB, but its result is not authenticated until session verification.
Tests use two injected packet links and Node crypto, not Android/Harmony devices.

`CompanionPacketStream` bridges socket byte events to packet reads/writes, with one
reader, 32 queued receive packets, 8 queued writes and approximately 4 MiB budgets
per direction plus one incomplete frame. Complete writes serialize and copy caller
bytes. Queue overflow closes instead of dropping bytes. Its fixed 1..120,000 ms
lifetime rejects pending reads/writes even when the underlying OS send stalls;
late completions cannot restore the stream. Clean EOF drains already received
packets, whereas truncation and errors reject and discard queued data. This bounds
transport IO, not an OS crypto call stalled outside that IO. The attempt below
bounds connection/handshake crypto; the state-only transfer driver below applies
its own deadline after handoff.

`createCompanionTcpPacketStream` takes exclusive ownership of a connected Harmony
TCPSocket, installs message/close/error listeners and requests OS cleanup on close.
The consumer supplies INTERNET permission and establishes the connection first.
OS cleanup is best-effort and not device-verified. This TCP adapter is unencrypted:
development or separately encrypted links only, never a plaintext production
fallback. Native adapter compilation passed; a real Linux loopback TCP regression
exercises the portable stream, fragmented handshake and authenticated binary frames
with Node crypto. Native socket/crypto execution on Harmony remains unverified.

`CompanionSyncAttempt` / `createCompanionSyncAttempt` apply one 1..120,000 ms
deadline to the supplied connector, OS random generation and complete handshake.
Timeout/cancel settles the public Promise without waiting for stalled OS promises;
late connections and sessions are closed, and cleanup errors cannot strand the
caller. Configuration is validated before calling connect. The connector must
perform only the host-approved connection and request cancellation without blocking.
Success returns a `CompanionSyncConnection` owning session and stream and cancels
the attempt timer. Later attempt cancellation does not close the handed-off
connection; the caller must close it and bound subsequent synchronization work.
Tests cover stalled connect/random/HMAC, late completion, throwing cleanup and
successful ownership handoff. The attempt accepts a connector interface; the TLS
client implementation below does not add discovery or pairing.

`state.synchronize(connection, timer, durationMs)` or the native timer factory
`synchronizeCompanionState(connection, state, durationMs)` starts a foreground
`CompanionStateSynchronizer`, taking exclusive ownership of the authenticated
connection. It auto-requests state on store notifications, permits one outstanding
batch, advances on matching durable ACKs, and keeps signing and complete writes in
one ordered queue. Its 100..120,000 ms lifetime bounds the public run even when
storage/crypto/IO stalls. Close it when leaving the foreground; there is no polling,
automatic reconnect, background service, or message/file dispatcher.

Call `await run.synchronize()` to await locally known outgoing cycles reaching
matching ACKs and a no-change prepare. Concurrent calls coalesce. This does not
mean the remote peer has no undisclosed changes; the run remains open to receive
future edits until `run.close()`, EOF, deadline or failure. `run.stopped` resolves
once with that terminal reason. Timeout/close rejects a waiting local drain and
prevents late sends/ACKs; already-started durable storage may still finish and its
retained batch will be retried on a fresh connection.

Driver tests cover 600-entry bidirectional transfer, edits during the foreground
run, state/ACK wire ordering, failed ACK writes followed by reconnect/replay and
stalled storage deadlines. A real Linux loopback TCP test also runs the full
600-entry state driver with fragmented writes. Storage/crypto in that network test
are injected; separate actual N-API file persistence regressions continue to pass.
`bun run test:companion` now includes every Harmony companion test as well as the
native file checks. Native Harmony wireless execution is still not verified.

## Pinned TLS client and native transport status

`createCompanionTlsConnector(host, port, caPem, leafSha256, lifetimeMs)` creates an
independent IP client for use with `createCompanionSyncAttempt`. It binds a local
socket, retains system CA verification (`skipRemoteValidation: false`), negotiates
only TLS 1.2/1.3 and verifies the exact approved leaf certificate SHA256 DER
fingerprint before exposing the packet stream. DER and single-certificate PEM
responses normalize to the same fingerprint. The CA and pin must come from prior
host approval, never from this connection. No insecure fallback, pin learning or
account trust is provided. Application HMAC pairing remains mandatory on top of TLS.

Both connector and stream are one-shot; use a fresh attempt after failure and
update approved certificate pins only through the host's explicit trust workflow.
The 1..120,000 ms stream lifetime begins before TLS connect. The attempt deadline
also bounds native connect, certificate extraction and fingerprint hashing. This
is a client, not a TLS listener or certificate provisioning UI. Consumers need
INTERNET permission; run native network operations off the UI thread as advised
by the SDK. Native close remains best-effort and unverified on a device.

Tests exercise real Linux TLS with freshly generated temporary certificates:
valid CA/pin permits framed bytes, while wrong CA/pin emits no application bytes.
They also cover cancellation during certificate extraction and strict PEM parsing.
The regression requires OpenSSL (and the existing Rust vectors require Cargo).
Native Harmony TLS compiles against the installed SDK but has not run on hardware.

The installed SDK also declares API-20 `linkEnhance` under
`SystemCapability.DistributedSched.AppCollaboration`, requiring DISTRIBUTED_DATASYNC.
It exposes client/server connections and up to 1024 bytes per `sendData`, and uses
the peer BLE MAC as its device ID. Its availability must be checked on each device;
API declarations do not prove permission, encryption or runtime availability.
`NativeCompanionDistributedConnector(approvedDeviceId, serverName, lifetimeMs)`
now supplies a one-shot client for the existing attempt/handshake. Both the connect
result and the established channel's peer address must match the host-approved
BLE address. Errors remain errors: no auto-discovery, permission prompt, radio
enablement or fallback occurs. `createCompanionDistributedPacketStream` also wraps
an already established/accepted channel with exclusive ownership. The caller must
still run application HMAC authentication; a matching BLE address is not pairing.

`CompanionChunkWriter` splits framed bytes into at most 1024-byte system sends and
yields to the event loop every 8 KiB so cancellation/deadlines can execute. A system
send exception closes the frame stream instead of retrying an uncertain partial
write. The existing packet stream bounds queues and preserves packet order. These
submissions are not application ACKs and do not demonstrate native radio delivery
or OS send-buffer backpressure. The normal durable state ACK remains authoritative.

Tests reconstruct a 2 MiB frame and cover mutation, concurrency, cancellation and
submission failure. Separate tests execute the actual native adapter source with
a mocked OS module to validate connect routing, late events and permission failure.
HAR compilation passes, but native hardware execution, encryption guarantees and
service listening/acceptance remain open. The plan's complete native transport
requirement is therefore not yet achieved.
