# Native notification implementation

Android `PodNotifications` provides local scheduled/immediate notifications
and a persistent open/action-event inbox. It checks application/runtime/channel
permission state, uses a stable notification tag per ID, and persists the
descriptor before publishing. Notification tokens are unpredictable and replaced
with each publication. Intent extras never supply the event payload: `captureOpen`
resolves the stored token, then transactionally writes an event and marks the
token consumed. Replays cannot create a second event. The host reads up to 32
pending events and acknowledges them explicitly after delivery.

The click PendingIntent directly targets the installed app's launch Activity and
is immutable, following the [Android notification navigation guidance](https://developer.android.com/develop/ui/views/notifications/navigation).
There is no notification-to-service-to-Activity trampoline.

Limits: 128 stored notification IDs, 256 unacknowledged events, 32 KiB descriptor,
256 title characters and 4096 body characters. Up to three uniquely named action
buttons are supported, each with an independent immutable PendingIntent and
persisted random token. Their labels are limited to 64 characters. Replacing or
cancelling a notification invalidates its old action tokens. A publication accepts
one open/action interaction; the resulting action ID comes from storage, never
from caller extras. Android and Wear advertise `notification.local`; remote
registration and non-Android adapters remain unfinished. Capability availability
does not imply that the user has granted notification permission.

OWW242 instrumentation `LocalNotificationTest` verifies a real published native
notification, Unicode content, forged-token rejection, persisted payload lookup,
duplicate capture rejection, inbox reopen and acknowledgement. It injects the
stored token at the inbox boundary; it is not a physical tap or cold-start test.
The action regression verifies native buttons, replacement-token invalidation,
stored action identity despite forged extras, and duplicate capture rejection.
Run it with the background APK fixture and `tests/android-background` test source
directory, selecting `dev.podjs.runtime.LocalNotificationTest`.

Android/Wear Activities now capture both launch and `onNewIntent` notification
intents on the service IO worker. The View polls the durable inbox while frames
are available and posts events for the next frame; lack of a subscriber does not
delete them. SDK open/action handlers may return promises. The SDK acknowledges
only after all registered handlers succeed, suppresses concurrent duplicate
delivery, and retries failed delivery via the persistent host inbox. Consumers
must still use eventId for idempotency across process death or partial callback
success. Acknowledged IDs are cached only within the current guest realm.

OWW242 now also verifies the actual PendingIntent→Activity→persistent inbox path
and retention while the guest has no subscriber. This invokes PendingIntent.send
in instrumentation, not a physical touch. Full public guest subscription delivery
is covered by the SDK callback instrumentation below; TypeScript tests cover
late subscribers, pending async callbacks, retries and duplicate ACKs.

Timed delivery uses a persisted descriptor snapshot and a one-shot WorkManager
request per generation of a notification ID. `schedule` replaces the prior record;
`listPending` survives reopening; cancellation removes the authoritative record
before stopping system work. A Worker must match the stored run ID to publish,
so stale/cancelled runs cannot resurrect a notification. Timing is best effort,
not an exact alarm; a backwards wall-clock change triggers retry. Permission or
publication failure removes the pending record and is logged by the Worker.
Up to 128 notifications may be scheduled. Category is forwarded to Android.
Capture removes the native notification; acknowledgement frees consumed records
without deleting a newer live replacement. OWW242 verifies delayed delivery,
snapshot isolation, reopen, replacement and stale/cancelled run rejection.
The prepare/commit sequence persists the system request before publishing its
generation in SQLite. The old request is retained until the new record commits,
and delivery shares the process lock with this transition. Thus process death
before commit leaves the previous generation valid and the new orphan harmless;
after commit, the authoritative generation already has durable system work.
Record replacement and old click-token invalidation are one SQLite transaction.
Cancellation removes authority before cancelling tagged requests (plus legacy
unique work). The fault-injection regression interrupts after durable enqueue,
proves the old request is still runnable, lets the real orphan Worker finish
without posting, and verifies a later committed replacement has system work.
All seven notification tests pass on OWW242. This prevents the missing-enqueue
window for new schedules; it does not repair orphan records from older builds.
Publication followed by process death before record removal may still repeat
the same stable notification ID; this is not exactly-once presentation.
OWW242 cold-wakeup is now verified separately: after the scheduling test exited,
`pidof dev.podjs.backgroundtest` returned no process. SystemJobService then
started a new process, PodNotificationWorker returned SUCCESS, and `dumpsys
notification --noredact` contained the expected ID/body before any verification
test was started. A second instrumentation invocation confirmed native content,
absence from listPending, and cleaned up the notification. Reboot delivery,
force-stop behavior and physical notification interaction are not covered.

`NotificationColdWakeTest#schedule` and `#verify` accept the instrumentation
argument `notificationColdWakeId`. Invoke them separately, prove process absence
after schedule, and observe system publication before verify. Schedule uses a
30-second earliest time. Do not use `am force-stop` as a substitute for ordinary
process death; that changes the application's stopped state.

The host service routes for permission/status, requestPermission, schedule,
cancel and listPending are now connected. Android 13+ permission requests are
coordinated on the Activity main thread and completed from its result callback;
cancelled callers do not receive a later result, while the outstanding system
prompt still prevents another prompt from being started. Below API 33, or when
runtime permission already exists, the current application/channel state is
returned directly. Remote provider methods remain unsupported.
OWW242 verifies the pre-33 permission path and asynchronous service schedule/list/
cancel flow. API 36 Pixel emulator tests now exercise the real system permission
controller with a debug-only attached Activity and the production coordinator:
allow after caller cancellation (no cancelled callback), concurrent requests
remain busy until system result, first denial, dismissal after denial, fixed
denial/no further dialog, and dismissal without any choice.
The latter exposed and fixed an incorrect `denied` status. Opening a prompt no
longer sets the persisted decision. Android can return a denied result when Back
dismisses the dialog, so we use a grant or the first-denial rationale state as
decision evidence, retaining previously saved decisions for later fixed denial.
An untouched dismissed prompt remains `notDetermined`, consistent with the
[platform behavior](https://developer.android.com/develop/ui/views/notifications/notification-permission#user-swipe-away).
Both Activities forward the real grant results. These tests verify the shared
coordinator on API 36, not every API 33+ version or an OEM Wear permission UI.

Build the background APK fixture with `-PpodAppId=dev.podjs.permissiontest` and
the `tests/android-background` source directory, then invoke each method in
`NotificationPermissionTest` separately. Use this dedicated package on a test
device: clear its data before the denial case and before the initially-undecided
cases. The dismissal case may be followed directly by the allow case to verify
the persistent state stayed undecided. Tests refuse to run against other app IDs.
The observed controller uses a different button ID for a repeated/fixed denial;
the regression explicitly exercises that button rather than assuming first-use UI.
The SDK also exposes `notifications.permission()` as a status alias.

OWW242 now passes all six `LocalNotificationTest` cases, including real
PendingIntent → Activity → View → public SDK `onOpen`/`onAction` callbacks.
Each async callback calls the permission service, persists the original payload
and action identity through guest KV, and only then acknowledges the inbox.
The test waits for native publication visibility and checks inbox removal after
callback success. This is an actual SDK/runtime integration test, not physical
touch, process-death, reboot, or Android 13+ permission-dialog acceptance.
