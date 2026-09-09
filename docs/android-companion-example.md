# Android companion example

`:companionExample` is the native Android sample application, package
`dev.podjs.companion.example`. Its current screen implements offline notes,
messages and file transfer controls through `PodCompanion`, with a persisted local device identity
and user-selected peer ID. Explicit out-of-band pairing approval and foreground
LAN connect/listen and explicit BLE controls are available. Offline enqueue is never labeled as delivered.

Build from `platforms/android`:

```sh
./gradlew :companionExample:assembleDebug :companionExample:assembleDebugAndroidTest
adb -s DEVICE_SERIAL install -r companion-example/build/outputs/apk/debug/companionExample-debug.apk
adb -s DEVICE_SERIAL install -r companion-example/build/outputs/apk/androidTest/debug/companionExample-debug-androidTest.apk
adb -s DEVICE_SERIAL shell am instrument -w -e class dev.podjs.companion.example.MainActivityTest,dev.podjs.companion.example.MainActivityNetworkTest,dev.podjs.companion.example.SelectedDocumentTest,dev.podjs.companion.example.MainActivityBleTest dev.podjs.companion.example.test/androidx.test.runner.AndroidJUnitRunner
```

On a phone with the English Android DocumentsUI (verified with Pixel 11 API 36),
also run the actual picker/URI-grant test. Do not include this class in the
picker-less watch suite:

```sh
adb -s PHONE_SERIAL shell am instrument -w -e class dev.podjs.companion.example.MainActivityPickerTest dev.podjs.companion.example.test/androidx.test.runner.AndroidJUnitRunner
```

On an English API 31+ phone with fresh denied Nearby permissions, separately run
the real PermissionController test (not on the API 30 watch):

```sh
adb -s PHONE_SERIAL shell am instrument -w -e class dev.podjs.companion.example.MainActivityBlePermissionTest dev.podjs.companion.example.test/androidx.test.runner.AndroidJUnitRunner
```

This test rejects pre-granted permissions, chooses **Don't allow**, verifies the
three permissions remain denied, then chooses **Allow** and verifies all three
are granted without automatic scan/connect. It uses the actual system dialog,
not a permission-decision override, `pm grant` or adopted shell identity. Run on a
controlled test profile: it changes the example's Nearby permission decisions.

The app uses a left-aligned scrollable native form with system/cutout insets,
48 dp minimum action targets, explicit labels and a polite status live region.
Primary actions use blue, the queue lookup is secondary, and disabled actions use
a distinct neutral state. Storage work runs on one IO executor; buttons remain
disabled while work is pending. Activity destruction closes the SDK after queued
storage work. Networking starts only on an explicit connect/listen action;
backgrounding stops it and foregrounding never automatically reconnects.

Notes are replicated-state values under `note`. Messages are opaque JSON objects
with a `text` property and a 24-hour expiration. The queue view shows up to 100
live pending entries as a count, and local identity/peer choice survive recreation.
Application backup is disabled. Runtime dependencies are still inherited from
the companion library, not yet split into a minimal distribution.

## Foreground LAN demonstration

After both devices approve pairing, enter the peer ID on each side. On one side,
enter its local numeric IP (or `0.0.0.0` to bind all IPv4 interfaces) and port, then
choose **等待此设备连接**. Port zero selects an available local port shown in the
status. On the other side enter the listening device's actual IP and port and
choose **连接此设备**. There is no DNS resolution or discovery. Connect/accept and
authentication share a 30-second deadline; each authenticated foreground run lasts
at most 120 seconds. The peer and address fields are locked while an attempt/run
or its asynchronous cleanup remains active. **断开连接** cancels pending IO.

The existing HMAC protocol authenticates the peer and frames but does not encrypt
their contents: use non-sensitive demonstration data only. Closing/backgrounding
invalidates late handshake results before asynchronously closing sockets/stores;
generation-checked callbacks cannot reactivate an old run. Activity destruction
also shuts down the connection and cleanup workers. No foreground service or
background execution permission is requested by these controls.

Saving a note requests state synchronization. Remote changes refresh the saved
value, not the editable draft. Sending a message queues it durably and requests
transmission; the queue shrinks only after its peer's business ACK. Incoming
messages display up to 20 pending deliveries with 1 KiB previews. Each has a
separate **确认此消息已处理** action. Connected confirmation queues an authenticated
ACK; offline confirmation records the local receipt for the peer's later retry.
State and message notifications cause coalesced snapshot reads with a trailing
refresh if another change arrives during a read, without a polling timer.

## Explicit BLE controls

The BLE section deliberately separates permissions, radio address selection and
application authentication. **检查并请求蓝牙权限** requests the OS permissions
needed by this complete example panel; granting them does not queue or start an
operation. Android 11 uses fine-location permission for scanning and may require
system location enabled. Android 12+ requests nearby-device scan/connect/advertise
permissions; the scan declaration uses `neverForLocation`. There is no background
location request, adapter toggle, system pairing or automatic retry.

**扫描 PodJS 广播（5 秒）** runs bounded discovery off the UI thread. Selecting a
result only fills the radio address, never changes the application peer ID or
approves pairing. Failed/empty scans have separate messages. Cancel or foreground
exit stops scanning asynchronously, clears results/address and invalidates late
callbacks. Returning or granting permission requires another explicit action.

After approving the same application pairing on both devices, choose **通过 BLE
连接并认证** to connect to the selected advertising peripheral. **广播并验证目标
设备** needs no radio address: it binds only the first connected radio as a
candidate, then authenticates the already-approved application peer ID above.
It does not approve the first device or learn a pairing key from the connection.
Other radios cannot replace that candidate; failed authentication or disconnect
closes this attempt, with no automatic rearm. A nearby untrusted central can thus
occupy/fail an attempt, requiring an explicit retry. The SDK's strict known-address
overload remains available when a host has a stable route. No hidden local address
API, adapter privacy change or system pairing bypass is used.
After this waiting-mode change, OWW242 UI/document/LAN regression passed 14 tests
(43.49 seconds). The live wireless gate remains open; tests did not start a new
host Bluetooth scan or connection.

Both actions use `PodBleAttempt` and the same 120-second foreground driver as LAN,
with a 30-second setup/authentication deadline. **断开连接** also cancels BLE.
Notes/messages/file intents retain the same ACK/consent semantics; radio selection
does not imply delivery. The example protocol does not encrypt payloads, so use
only non-sensitive demonstration data. BLE controls remain experimental: the
host BlueZ crash described in the SDK document still blocks a successful live
two-radio run; no host wireless retry was performed for this UI work.

The new native UI tests inject scan results and permission decisions: they verify
that permission results do not auto-start, invalid addresses do not connect,
selecting a result only fills the address, and background cancellation suppresses
late results. These are not real OS permission-dialog, remote BLE connection or
updated phone/TalkBack/visual acceptance. The secure pairing window remains
`FLAG_SECURE`; it was not disabled to obtain screenshots.
OWW242 BLE UI/document/network/core UI regression: 14 tests passed (43.711 seconds).
Separately, Pixel 11 API 36's real Nearby deny/allow flow passed (1 test, 2.083
seconds); the five remaining UI/document/network/picker classes passed 15 tests
(29.63 seconds). After testing, the example's SCAN/CONNECT/ADVERTISE grants were
explicitly revoked back to their observed initial denied state (user-decision
flags remain), then the owned emulator was stopped without saving a snapshot.
This validates the system permission flow, not BLE radio interoperability or
physical touch/TalkBack acceptance. Host Bluetooth was not exercised again.

## File transfer controls

**选择文件发送给目标设备** opens the system document picker. Only `content://` results
for the target captured before opening the picker are imported; that target is
restored across Activity recreation. The picker normally backgrounds this Activity,
so reconnect manually after selection. Devices without a picker report that it is
unavailable; they can still receive files selected on the other device.

`SelectedDocument` accepts only regular-file descriptors, copies at most 16 MiB
into private cache in 64 KiB reads, and then creates the SDK's immutable verified
snapshot. Pipes/stream-only providers require downloading locally before selection.
No persistent URI grant or broad storage permission is requested. Cancel/background
and a 30-second deadline send a `CancellationSignal` off the UI thread and prevent
late publication. Provider opening/cancellation is cooperative: a provider that
ignores Android cancellation or stalls metadata calls can delay its IO worker;
this is not a hard kill of arbitrary provider code. The regular-file copy also
checks cancellation/deadline between reads.

Temporary spools are deleted after snapshot creation or failure. After acquiring
exclusive SDK storage ownership on startup, the app reclaims stranded spools in its
dedicated staging directory. Unknown entries and symlinks fail closed without being
deleted. A failed offer releases its newly created, unreferenced snapshot. The spool
can use up to an additional 16 MiB of temporary cache alongside the SDK's shared
32 MiB logical transfer quota; it does not raise the SDK quota.

Incoming metadata is not consent. Each offer shows peer, ID, size, MIME and phase,
with a separate **批准接收此文件** action. After approval, the sender chooses
**查询批准并继续**, or starts a new connection which checks pending consent once.
There is no consent polling timer. Phase labels distinguish approval, chunks, final
verification, cancellation and completion; they are not an estimated percentage.
Completed incoming files can be verified again. Cancel/delete requires a confirmation
dialog and explains that receiver data is removed irreversibly; source files on the
sender are not deleted. Incoming/outgoing journals paginate ten entries per page.

**查看与清理本机发送快照** explicitly queries and releases local snapshots. Release
is refused while any peer still has an active transfer. Never-offered snapshots and
terminal sources can be released, and repeating a completed release is idempotent.
The original selected document and remote files are not affected. This inventory
query intentionally pauses the outgoing snapshot read lease; it is not performed
on every progress notification. Accepted files currently stay in SDK-private storage;
export to a document provider is not implemented.

## Pairing approval

Enter the other device's complete ID in the target field on each device. Generate
a random 32-byte key on one device and transfer its 64-character hexadecimal text
through a trusted out-of-band channel. Both devices must enter the same key and
separately approve the confirmation dialog after checking identities. Do not send
the key over the unauthenticated LAN transport. This manual trusted-key ceremony
is not discovery, QR scanning, a short-code PAKE, or proof of a live remote peer.
There is no automatic trust on first connection. Existing credentials are never
implicitly replaced; revoke explicitly on each device before pairing again.

The approved key is stored by the SDK's no-backup Keystore-wrapped credential
store. Input/generated text is excluded from view saved state and autofill, cleared
on cancellation, approval or leaving foreground, and not logged. Both windows use
FLAG_SECURE to block screenshots/task-switcher capture. Java strings and third-party
keyboards cannot be guaranteed erased from memory; do not use an untrusted keyboard
or clipboard for secret transfer. Revocation retains offline business data and does
not delete the other device's credential. Network authentication is still required.

The pairing baseline passed three OWW242 Activity tests: offline controls, approval/cancellation and
revoke/reapprove (including credential persistence across recreation), and secret
clearing on background/recreation. The updated pairing layout has not yet had
phone-size visual or physical two-device acceptance; secure windows intentionally
prevent ordinary screenshot capture.

The original offline Activity test passed on OWW242 and the Pixel 11 API 36 emulator, exercising
save, enqueue, Activity recreation and empty-message rejection. Phone-size layout
was inspected on the emulator. This is not physical-phone touch/TalkBack or paired
wireless acceptance. Two additional OWW242 tests exercise UI pairing, real loopback
authentication, bidirectional state, explicit message ACK, background disconnect,
manual reconnect with a pending message, and listener-port release without automatic
restart. A file UI test additionally covers 65,539-byte incoming approval/reverification,
131,079-byte ContentResolver import and outgoing transfer after offline approval,
byte-for-byte comparison, receiver deletion and local snapshot release. Independent
document tests cover regular/oversized/pipe sources, cooperative cancellation/deadline
and staging recovery. The test APK exposes only synthetic fixture content. Debug
visibility is restricted to that fixture authority, following Android's
[provider query declaration](https://developer.android.com/training/package-visibility/declaring).
OWW242 also blocks cold provider-start of the test APK (`WearFrw SelfStartController`);
document tests establish a foreground host round trip before accessing the fixture,
without changing the device's self-start policy.

The real DocumentsUI/URI-grant path is additionally verified on the Pixel 11 API 36
emulator: the test provider runs under a different UID and creates one synthetic
131,079-byte Downloads entry. The example is first unable to open its MediaStore
URI. Accessibility actions then navigate the real system picker, select that file,
and return through `onActivityResult`; the sample imports and sends it over an
authenticated session, and the receiver's bytes match exactly. The owning provider
removes only its recorded Downloads fixture afterward, and transfer copies/pairing
are cleaned up. No broad read permission is used to bypass the initial denial.
The standalone picker case passed in 6.515 seconds; the combined four-class phone
suite passed all 12 tests in 26.22 seconds.

This does not prove physical-phone touch/TalkBack, updated phone-size visual acceptance,
arbitrary cloud-provider compatibility, file export, or wireless two-device acceptance.

The current combined example suite passes 11 tests on OWW242 (38.474 seconds),
including target selection across recreation. The companion SDK/LAN regression
suite separately passes 15 tests, including unused-source cleanup, idempotent
release and active-transfer protection. These counts are device-local evidence,
not a claim of full platform-plan completion.
