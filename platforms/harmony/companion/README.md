# Harmony companion file receiving

Call `pickCompanionFile(context, client, transferId, cancelled)` from an explicit
foreground action to select one document and import it. It opens only the chosen
URI read-only, validates a regular file of at most 16 MiB, uses bounded reads,
and closes the handle in `finally`. An empty picker result returns `null`;
permission/read errors are not converted into successful cancellation. The
original URI is not retained. After success, use `createStoredFileSender`.

For application integration, construct `NativeCompanionClient(context, appId,
localId)` once for the foreground owner. It exposes the same durable `state`,
`inbox`, `outbox`, `fileRequests`, and `incomingFiles` used by
`client.attach(authenticatedConnection, durationMs)`. Pass `client.incomingFiles`
to the panel and use `client.createFileSender(peer, manifest, source)` with the
returned transport's `driveFile`. Identity mismatch and simultaneous attachment
are rejected. Call `client.close()` when leaving the foreground; this closes
transport resources without deleting durable work. Message expiry uses Unix
milliseconds (`Date.now()`). Pairing, permissions, connection establishment, and
source-file persistence remain the application's responsibility.

Construct one receiver per app/local identity with
`createCompanionIncomingFiles(context, appId, localId)`. Supply that receiver to
`CompanionFileChannels.receiver` for the authenticated transport and to the
`CompanionIncomingFilesPanel` component for local consent:

```typescript
CompanionIncomingFilesPanel({ receiver: this.incomingFiles })
```

The parent must construct `incomingFiles` before mounting the panel. The panel
does not create a transport, select a peer, or grant pairing permission. It lists
the receiver's durable offers and provides explicit accept/reject, cancellation,
and a read-only hexadecimal preview. Refresh after incoming traffic to see new
offers; there is no background polling. Failed operations leave the journal
available for recovery and retry.

Application consumers can call `listLocal()` to obtain completed manifests and
`readCompleteChunk(peer, transferId, index)` to read each 64 KiB chunk. Empty
files have no chunks. Reads require the complete lifecycle phase and verify the
stored chunk hash. No filesystem path is exposed. Do not execute received bytes
or interpret untrusted content without the application's own validation.

`cancelLocal(peer, transferId)` rejects an offer or deletes an accepted/completed
local file; cancellation is durable and cannot be undone by accepting the same
transfer ID. A new transfer requires a new ID. Preserve completed files until the
application has finished consuming them.

The HAR compiles against Harmony SDK. Linux tests exercise the actual native
storage and N-API bridge. Device UI/accessibility, Harmony filesystem behavior,
and authenticated wireless transfer remain separate acceptance gates.

## Driving outgoing files

For durable sources, call `client.outgoingFiles.prepare(manifest)`, write each
chunk with `writeChunk(transferId, index, bytes)`, then `finish(transferId)`.
`missing(transferId)` resumes an interrupted import. Native storage verifies
every chunk and the whole file before marking it complete. The separate outgoing
namespace reserves twice the source size within a 32 MiB budget for assembly.
The native 32 MiB reservation budget is shared by incoming and outgoing files
for the same app, not granted separately to each direction. Both directions use
one app-wide lock. Reservations are committed before content writes and released
only after content deletion is durable. Files from an earlier implementation
without reservation records block further allocation and content operations;
explicitly remove/reimport those files rather than treating their space as free.
After restart, `createStoredFileSender(peer, transferId)` loads the saved manifest
and reads verified chunks from disk; no original picker handle is needed.
Keep that source until all associated outgoing requests are resolved. Explicit
`outgoingFiles.remove(transferId)` deletes bytes and retains the ID tombstone.
This does not cancel a pending remote request; settle that transfer first.

If the manifest is not already available, use
`client.importFile(transferId, size, mime, source, cancelled)`. It makes two
bounded passes: hashes the source, then stores only missing chunks and verifies
the complete file. The source must support indexed rereads and return exactly
the expected chunk size. Cancellation retains staged chunks; retry the same ID
with unchanged content to resume. Changed content is rejected, not silently
replaced. After a cancellation near publication, inspect `outgoingFiles.list()`
because the completed file may already have committed.

Create `CompanionFileSender(requests, peerId, manifest, source, digest)` with the
same `requests` object assigned to `CompanionFileChannels`. The source implements
`readChunk(index)` and must remain immutable and available after reconnect or
process restart. The app must persist the manifest and source identity; the
driver persists wire requests, not the original source file.

After constructing a shared `CompanionMessageTransport` with state and file
channels, call `await transport.driveFile(sender)`. The transport advances the
sender after each authenticated, durably stored reply. `fileStatus()` reports
`waiting_consent`, `awaiting_reply`, `queued`, `complete`, or `cancelled` (initially
`idle`). It is a snapshot, not a completion promise: inspect `transport.stopped`
for connection failure/deadline. The foreground owner must schedule its own UI
updates without treating queued work as successful delivery.

When status becomes `waiting_consent`, no polling is started. After approval on
the receiving device, explicitly call `driveFile(sender)` again to query its
status and continue. A new authenticated connection can attach a reconstructed
sender backed by the same durable request journal and original source. The
pending request retains its original ID and bytes.

The transport passes its cancellation state into the sender and queue mutations.
Late source reads, digest work, or queued storage reads cannot initiate another
CAS after cancellation. A CAS already issued to the storage port is not rolled
back: reopening must still recover the durable journal, including the existing
crash window between consuming an intermediate observation and enqueueing the
next idempotent request. Cancellation is not a storage transaction rollback.

Use one sender per peer queue; do not concurrently call `step()`, enqueue manual
file commands, or `sendFile()` while the automatic driver owns that queue.
After a terminal result, display `await requests.terminal(peer)` to the user.
It returns an exact retained receipt only for an exclusive terminal peer queue.
After explicit confirmation, call `await transport.consumeFileTerminal(receipt)`
to serialize consumption with the driver. A false result means the queue or
receipt changed; refresh, do not silently retry a different receipt. This removes
the local observation, not file bytes. Direct `requests.consumeTerminal(receipt)`
is only for hosts that already exclusively own an inactive queue; never race it
with a driver. Pending or intermediate observations are not consumable by this
API. After successful consumption, discard the old sender and create a new one
before starting another transfer on that peer. `complete` means the receiver
reported durable verified publication; it does not mean the receiving app has
opened or consumed the file.
# Pairing lifecycle integration status

`CompanionPairings` manages approved credential metadata and identity-bound HMAC
signers. It requires a `CompanionPairingLease` that holds an **exclusive OS lock
for the entire owner lifetime**, including pending HUKS calls. The current
`NativeCompanionPairingStore` provides durable CAS only; it is not that lease and
must not be cast to one. Use `await openCompanionPairings(context, appId, localId)`
to acquire the native lifetime lease and recover interrupted operations. This
factory connects the lifecycle to HUKS; real-device keystore behavior and initial
pairing UI still require validation/integration.

The host must authenticate the initial exchange and obtain user approval before
calling `importApproved`. Interrupted imports are cleaned up by `recover`, never
automatically approved. Pass a connection-closing callback to `openSigner`;
revocation invalidates handles immediately and records intent before deleting
the key. Always await `close()` before opening another owner. A stalled system
keystore operation intentionally retains ownership until it settles.

For connections use `createCompanionPairedAttempt(pairings, approvedConnector,
timeoutMs).start(appId, localId, approvedPeerId, initiator, grantedChannels)`.
Its deadline includes credential lookup; revocation cancels an in-progress
handshake or closes a handed-off connection. Close the returned connection when
done. This releases its signer without deleting the pairing. `pairings.list()`
returns only peer IDs and lifecycle phases, not HUKS aliases. The factory does
not approve transport endpoints or perform initial pairing on behalf of the UI.

`CompanionPairingInvitation` is the bootstrap QR data codec, not a completed
initial-pairing protocol. It encodes exactly `type`, `version`, `appId`,
`deviceId`, `invitationId`, `expiresAt` and `secret`. IDs and secrets are distinct
32-byte OS-random values encoded as lowercase hex; expiry is at most five
minutes. Decoding rejects other app IDs, the local device, expired values and
duplicate/unknown fields. Treat the entire QR as a credential: do not log,
persist, share through analytics, or put it on the clipboard. `takeSecret` consumes
the in-memory object, and its caller must wipe the returned buffer. JS strings
and internal VM/crypto copies cannot be reliably erased.

The issuing host still needs to own a live invitation, remove its displayed QR
on cancellation/expiry, reject invitation reuse at the protocol boundary, prove
possession through the authenticated exchange, and obtain explicit approval on
both devices before importing permanent credentials. Re-parsing the same text
does not itself provide network replay protection. The example now displays and
scans invitations, but does not yet wire the initial exchange to a transport.

`CompanionPairingInvitationLease` now owns an invitation's timer, one-time claim,
QR-dismiss callback and attempt-cancel callback. On expiry/close it clears the
claimed key buffer too. Keep one issuing lease alive rather than rebuilding it
from QR text; call `assertActive()` around asynchronous approval/protocol work.
The callback must be wired to the real displayed QR, and the transport must gate
claims to approved candidates. This lifecycle helper does not implement the
mutual confirmation exchange or durable replay protection across reconstructed
invitations.

`CompanionInitialPairing` connects a live invitation lease to the existing
challenge/HMAC handshake and a host `CompanionPairingApproval` prompt. The scanner
is the initiator, and the QR issuer is the responder. Supply independently
selected peer identities and an approved connector; a scanned QR does not grant
permission to connect to arbitrary endpoints. The bootstrap connection accepts
only signed `pairing-approved` and then `pairing-stored` message frames, each
containing the exact invitation ID. No application queue is attached to it.

After both explicit approvals it derives the permanent 32-byte key as
HMAC-SHA256(QR secret, ASCII(`PodJS-pairing-key-v1:` + session ID + `:` + invitation
ID)), saves it through `CompanionPairings`, and waits for the peer's signed stored
confirmation. All exits close the bootstrap connection and invitation. On failure
after storage begins, `localCredentialMayExist()` is true: inspect the pairing
list and reconnect or explicitly revoke, rather than automatically replacing
credentials. A final acknowledgement lost to disconnect cannot be made into an
atomic transaction across devices. The example's invitation display/scanning
still needs to be connected to this core, a transport, and actual approval dialogs.

`scanCompanionPairingInvitation(context, appId, localId, cancelled)` uses the
system ScanKit UI from a foreground user action, requests QR-only/single-code
camera scanning with the album disabled, and parses the result as an invitation.
It returns `null` on user cancellation, suppresses late results after page
cancellation, and never connects or imports a permanent key. Errors are sanitized
so scanned credentials do not appear in exception text. The system default UI
does not expose a programmatic cancellation API here: invalidating a caller does
not claim to close its camera UI. Do not request extra CAMERA permission for this
default-UI API. The example has a scan button; actual device scanning remains unverified.

`renderCompanionPairingQr(lease)` generates a 512×512 black/white PixelMap using
ScanKit's ArrayBuffer overload, not its 512-character text overload or ArkUI's
truncating `QRCode` component. Input bytes are cleared afterward. It refuses to
return an image if the invitation expired or was claimed while generation was
pending, releases that late image, and closes the lease on generation failure.
The caller must remove/release the displayed PixelMap when the lease dismisses
its QR, render it square without filtering, and leave a white quiet zone around
it. The generated image has not yet been scanned on physical devices; tests
currently validate adapter parameters and lifecycle using a mocked system API.

The example's invitation tab generates/scans temporary invitations, shows a
square unfiltered QR with a white quiet zone, and clears credentials/images on
tab changes, component disappearance, expiry or explicit cancellation. It now
connects/listens through independent BLE by default (or explicitly selected
linkEnhance with a manually entered address) and presents
explicit identity approval. App background also cancels transient pairing. The
status text distinguishes scanned invitation, saved pairing and data connection.

Before a user-initiated linkEnhance connection, call
`requestCompanionDistributedPermission(context, cancelled)` and proceed only if
it returns true. The example declares `DISTRIBUTED_DATASYNC` with an in-use reason;
this helper does not prompt at startup or connect by itself. It checks the exact
permission result and current grant, rejects concurrent prompts, and ignores a
late grant after cancellation. A grant is not proof of transport support: SDK
documentation allows linkEnhance to be trimmed on some OS versions/builds.

`NativeCompanionBleDiscovery.scan(durationMs)` discovers the independent PodJS
GATT service shared with Android (`deef0001-654d-4e33-9a27-1341d8c28fd1`). Hosts
must declare and request `ACCESS_BLUETOOTH` before this explicit foreground action.
It uses an instance-scoped SDK-15 scanner, 100–10,000 ms total deadline (including
startup), at most 32 candidates, bounded OS batches, and ignores callbacks after
cancel/expiry. A cancelled or stalled startup rejects rather than returning an
empty successful scan; late startup requests a second stop. Cleanup requests are
best effort if the OS rejects or stalls stopping. Results contain only a bounded
display name, RSSI and an opaque address, not advertising payloads or credentials.
SDK 26 may supply virtual addresses: do not assume these identify real hardware
or can route through linkEnhance. Discovery is not trust or proof of protocol
support. The example's scan-invitation path invokes this API after a user permission
action, requires explicit candidate selection, and expires that selection after
30 seconds. Physical-device acceptance remains pending.

`NativeCompanionBleConnector(approvedAddress, lifetimeMs)` supplies the independent
GATT client as a `CompanionSyncConnector`. It verifies the PodJS primary service,
write/indicate characteristics and CCC descriptor, requests MTU 517 using the
SDK-10 callback API, waits for the actual negotiated MTU and indication enable,
then exposes `CompanionBlePacketStream`. All writes use responses; disconnect,
wrong characteristics, MTU change, cancellation and the absolute lifetime close
the link. The host must grant `ACCESS_BLUETOOTH`, select the address, and perform
the application handshake; this adapter neither pairs nor guarantees encryption.
Do not route arbitrary guests directly to its packet stream.

The shared BLE framing uses the Android `PodBleStream` P/1 header, u32 sequence,
and `min(MTU - 3, 512) - 6` payload fragments around existing length-prefixed
frames. Tests compile the actual Android Java stream and check both directions
at MTU 23, 185 and 517 (requires Java and Android SDK platform 36); separate native
adapter tests mock only the OS GATT API. These are not physical-radio tests. The
example now selects this connector for BLE invitation scanning and the acceptor
below for invitation issuing; paired data connections remain a separate UI task.

`NativeCompanionBleAcceptor(approvedAddressOrNull, lifetimeMs)` publishes the same
GATT service with an owner-specific advertisement ID and 30-second maximum radio
advertising duration. Explicit `null` allows the first central as a bootstrap
candidate only, never as an approved application. Only one PodJS listener and one
central are allowed: the SDK server MTU event lacks a peer identity. A second
central or malformed selected-peer write closes the attempt. The receive stream
is allocated before accepting the CCC indication-enable write, and the returned
connection waits until advertising has stopped. Reads/prepared writes/notification
subscriptions are rejected; data sends use confirmed indications. Late advertising
IDs after cancellation are stopped separately. Cleanup also requests explicit
disconnect where API 26 provides it, then closes the server; older-system radio
release still requires device verification. OS cleanup failures are best effort.

Before discovery or either GATT role, call
`requestCompanionBluetoothPermission(context, cancelled)` from a user action.
The example now declares `ACCESS_BLUETOOTH` with an in-use explanation. The helper
checks the exact permission result and current grant and ignores late approval
after cancellation, without turning on Bluetooth or requesting real-MAC access.
The example calls it for explicit discovery/connection actions. Both actual GATT adapters are exercised
together through a simulated OS bus; physical advertisements, subscriptions and
wireless transfer have not been accepted on real devices.
