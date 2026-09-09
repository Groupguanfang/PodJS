# Accessibility implementation

The public Solid and Vue Vapor primitives accept `accessibilityLabel`,
`accessibilityRole`, `accessibilityValue`, `accessibilityHint`,
`accessibilityHidden`, `accessibilityState`, and `accessibilityActions`.
`onAccessibilityAction` receives actions delivered through the host event pump.
Roles are text, button, image, header, link, checkbox, switch,
adjustable, list, and listitem. Action names are activate, increment, decrement.
State supports disabled, selected, checked (including mixed), expanded, and busy.

The shared native-tree renderer validates the complete metadata snapshot before
changing its property cache or sending it to the optional `ui.setAccessibility`
operation. Absent and explicitly empty labels remain distinct. Text is limited
to 4096 UTF-8 bytes per field. Callback functions remain in the guest; only
declared action bits and onPress presence cross the native boundary. Vue uses
callback-preserving normalization and supports kebab-case accessibility props.

The QuickJS UI surface stores a typed metadata value in the Rust node arena.
Invalid role/state/action masks are rejected atomically. Node generations prevent
stale writes, and freeing/reusing a slot clears its metadata. This operation is
an additive optional HostOps extension, not a capability claim for legacy hosts.

The Rust core now produces a primary-output semantic snapshot at the end of
`draw()`, including any text-provider repaint. The paint traversal captures
clipped logical border boxes using its existing 2D and perspective transforms;
the semantic traversal then follows document order, not z-index or screen
coordinates. Bounds are conservative rectangles, including rounded-clip corners,
not pixel-precise coverage or sibling-occlusion tests.

Text runs merge their absorbed inline descendants. Labelled/pressable groups
merge passive descendants without duplicating their text as focus nodes;
independently operable descendants remain separate. Unlabelled images are
decorative. Hidden subtrees are excluded, disabled state propagates to child
controls, and disabled nodes expose no actions. Native generation IDs and the
nearest exposed parent are retained. A deterministic semantic hash covers node
order, identity, label/value/hint, state/actions and clipped bounds, but not paint
colors or frame time. Mutations remain invisible until the next draw commit.

`pod_runtime_set_accessibility_enabled` opts into collection (off by default).
After `pod_runtime_snapshot`, `pod_runtime_accessibility_snapshot` exports UTF-8
JSON schema 1 and the last committed core frame. It never redraws or publishes
pending mutations. Serialization happens only on a semantic hash change; repeated
queries reuse the same byte buffer and return `changed=0`. See the public C header
for buffer lifetime and state/action masks. Other hosts can consume this export
without sharing Android UI classes.

Native providers can also use `pod_runtime_accessibility_tree` and
`pod_runtime_accessibility_node`: allocation-free typed reads in committed
document order, with independent per-consumer hash comparisons. They do not
consume the JSON export's changed cursor. Node text is borrowed length-delimited
UTF-8 (null means absent, non-null length zero means explicitly empty); callers
must serialize with runtime mutations and copy text before releasing that lock.
The C ABI test checks typed IDs/roles/bounds, Unicode, absent versus empty values,
out-of-range reads, commit isolation and independence from JSON change tracking.

`pod_runtime_accessibility_action` accepts one action against a committed content
hash. It checks the published action bits, current node generation, attachment,
current declared actions, and hidden/disabled ancestors before queueing a guest
event. Success means queued, not callback completion. The PodJS event pump routes
that event to the exact mirror node. Delivery rechecks metadata and the active
modal focus scope; it calls `onAccessibilityAction`, or falls back to `onPress`
for activate when no semantic callback exists. It never bubbles to a parent.

Tests cover renderer encoding/removal/validation, distinct null and false state
values, UTF-8 limits, a real QuickJS-to-core call, invalid-update atomicity, and
generation reuse. Native-tree golden tests cover merged labels, hidden and
decorative nodes, nested actions, document order, clipping, scale/perspective,
disabled inheritance, and committed hash behavior. The C ABI test boots a real
guest and checks JSON content, changed suppression, pending-frame isolation,
and disabling collection. Action tests cover queue payloads, stale hashes,
revocation before the next draw, exact-target callback dispatch and modal scopes.
The mounted SDK integration test runs the registered service pump through the
public frame function. Two queued actions cannot bypass a disabled state set by
the first callback; undeclared activation does not bubble, activation falls back
to the exact control's press callback, and disposed controls receive no callback.
This test is included in `bun run test` with the browser Solid runtime condition.
Android `PodRuntimeView` now enables collection when the system accessibility
manager is enabled, reads committed snapshots after its UI-thread frame call,
and exposes `PodAccessibility`, an `AccessibilityNodeProvider`. JNI transports
UTF-8 bytes and a separate 64-bit hash (no floating-point JSON hash conversion).
Virtual nodes map roles, labels, values, hints, selected/disabled/check states,
document traversal order, and logical bounds into view/screen coordinates.
State descriptions combine the explicit value with checked/unchecked/mixed,
expanded/collapsed and busy facts; a custom value does not suppress those facts.
Host-owned state text has English defaults and Chinese translations, selected
through Android resources. Removing state properties removes the corresponding
description rather than retaining a previous node-info value.
Accessibility focus survives updates for an unchanged ID and is cleared when
that ID disappears. Touch exploration emits virtual hover events; click and
forward/backward adjustment actions return through the checked runtime ABI.
Disabling and re-enabling forces a fresh export even for identical content.

The Android provider instrumentation tests passed on OWW242 (Android 11), covering
Unicode, scaled/nested bounds, selected/disabled states, action/hash forwarding,
focus retention and removal. Both ARM JNI builds link. One test uses a synthetic
semantic snapshot and action sink. A second boots a package-validated native
runtime through the production JNI methods, feeds its committed snapshot to the
provider, and sends the provider click action through JNI/C ABI to a real QuickJS
guest. It checks that queueing alone does not change the snapshot, the next frame
changes the Unicode label and disables the node, and further clicks are rejected.
The fixture manifest derives its capabilities and revision from the checkout;
stale prebuilts fail validation. This test detaches presentation and uses a small
guest event handler, not a mounted SDK application or TalkBack session. Run both
with `:runtime:connectedDebugAndroidTest` and runner class
`dev.podjs.runtime.PodAccessibilityTest`.
Actual TalkBack interaction, watchOS bridge validation, Harmony implementation
and their physical reader acceptance remain pending. No target advertises
`accessibility.basic` until that integration is implemented and verified.

The watchOS bridge now has source implementation: `PodWatchHost` reads semantics
before the paint-hash early return and publishes changed nodes through Combine.
`PodAccessibilityOverlay` creates stable-ID SwiftUI accessibility elements using
the SpriteView aspect-fit coordinate mapping and explicit document-order sort
priorities. Default and adjustable actions return through the checked C ABI;
the raster and hardware-input helper views are hidden from accessibility.
Decoder, aspect-fit and revoked-action XCTest cases are added. This Linux host
has no Swift/Xcode toolchain: these changes are not compiled or executed yet,
and the watchOS runtime artifact must be rebuilt with the new C ABI before
linking. Host state text now uses the package's English and Simplified Chinese
localization resources; value/state composition has an XCTest assertion. Failed
semantic decoding clears old elements instead of retaining outdated controls.
SwiftUI focus behavior, role/state announcements, packaged localization and
VoiceOver device acceptance still need verification.

Harmony NAPI now exposes `accessibilityEnabled`, `accessibilitySnapshot` and
`accessibilityAction` source interfaces. Queries/actions share the XComponent
frame mutex, do not drive rendering, and use exact 16-digit hexadecimal hashes
instead of ArkTS floating-point numbers. Actions reject fractional/out-of-range
IDs, malformed hashes and non-single action codes before calling the core.
The XComponent native provider now has source implementation for current/parent/
sibling/child/subtree and text queries, document-order forward/backward focus,
focus retrieval/clear, activation and adjustment. A typed snapshot is copied
under the runtime mutex; provider queries use a separate owned cache. Action
callbacks release that cache lock before taking the runtime lock. Changed-tree
and focus events are sent after releasing locks, and their temporary native
objects are destroyed. Hidden/destroyed surfaces clear the cache and reject
actions; late frame callbacks cannot repopulate them. Coordinates apply the
surface scale and XComponent offset; full-screen/window offset behavior still
needs device validation.

`scripts/test-harmony-accessibility-provider.sh` compiles the adapter against
native headers and runs callback contract tests with OS/C ABI test doubles.
Set `PODJS_HARMONY_INCLUDE_ROOT` to a directory containing `arkui/` and `hilog/`.
The test passed locally with upstream OpenHarmony headers, including ASan/UBSan,
copied Unicode text, scaled bounds, subtree queries, generational document order,
disabled actions, exact hashes, focus removal, event ownership and reentrant
queries. The provider, full NAPI bridge and GLES renderer subsequently compiled
to ARM64 object files using the installed Windows DevEco native SDK in an
isolated checkout. `scripts/check-harmony-native.ps1` reproduces that compiler
gate. On 2026-09-08, the current Rust sources were also rebuilt for
`aarch64-unknown-linux-ohos` in that isolated directory, followed by successful
native linking, ArkTS compilation and unsigned HAP packaging with
`scripts/build-harmony-runtime.ps1`. DevEco reported deprecated-context and
potential-exception warnings; this is not a system reader run. `hdc list targets`
returned `[Empty]`, so device acceptance remains pending. State descriptions combine
the explicit value with checked/unchecked/mixed, expanded/collapsed and busy
facts. `accessibilityStateLabels` atomically accepts six bounded strings from
ArkUI localized resources (English and Chinese supplied). Updating labels
refreshes the owned descriptions without changing the core hash. Embedded NUL
bytes in guest text become replacement characters at the native C-string
boundary so the rest of the label remains available. Contract tests cover these
paths, including unchanged-label event suppression. Native role/state
announcements and real system-reader acceptance remain open.
No Harmony accessibility capability is advertised.
