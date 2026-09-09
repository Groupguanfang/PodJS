# Companion SDK core (in progress)

`@podjs/companion/state` provides asynchronous `get`, `set`, `delete`,
`snapshot`, local `subscribe`, and authenticated state ingress using the existing
deterministic state merge engine. It does not yet implement `synchronize`, peer
authentication, transport, messages, or files. It is not the completed HarmonyOS
or iOS SDK, and has not been compiled as ArkTS.

The host supplies `CompanionStateDatabase.transaction`. A transaction must lock
the application's namespace across all handles, load the current snapshot,
invoke the synchronous update callback exactly once, and atomically persist its
result before resolving. Reads use this same transaction boundary. Returning
success before durable commit is forbidden. A rejection after an uncertain
commit is allowed; the SDK reloads on its next operation and never invents a
rollback. An in-memory implementation proves ordering only, not disk durability.

Only call `receiveAuthenticated` after verifying the session's application,
peer, channel, frame sequence and integrity. Send the returned receive cursor
as an ACK only after resolution. This API cannot establish authentication itself.
It does not advance the sender's confirmed outbound cursor. Subscribers receive
isolated snapshots after locally successful transactions; they are not database
watchers, and a throwing subscriber does not undo a durable write.

Remaining integration gates: native platform packaging/build and device storage
validation, authenticated sender and receiver wiring, messages/files,
companion UI, and two-device recovery acceptance.

Harmony now exposes `companionStateRead` / `companionStateCompareExchange` and
`NativeCompanionStateCas`. `CasStateDatabase` connects such a native port to
`CompanionState` with optimistic transactions: a concurrent writer is reported
as a conflict, with no automatic callback replay. Its C++ storage uses a separate
private app directory, exclusive process lock, atomic rename and file/directory
fsync. A Linux Node N-API test runs the SDK through this actual file backend and
reopens it in a second process. This proves the portable native storage path,
not phone-device acceptance. A subsequent Windows DevEco build compiled and
linked the native bridge and explicitly imported `NativeCompanionStateCas` in
an ArkTS compile probe; a second successful unsigned HAP build removed the
probe. The portable TypeScript core itself still needs ArkTS compatibility and
native platform packaging integration; this build only verified its native port.

A separate class-based Harmony state implementation is now available in
`platforms/harmony/companion/src/main/ets/CompanionState.ts`, with the native factory
`createCompanionState`. Its full factory/core import has passed DevEco ArkTS
compilation, and reference-model plus actual native file tests cover its state
behavior. See [Harmony state SDK](../../docs/harmony-companion-sdk.md) for its
identity-bound storage format and remaining phone SDK delivery gates.

The Harmony implementation now builds as an independent `@podjs/companion` HAR
using `scripts/build-harmony-companion.ps1`. Its archive includes the ARM64
native library and has passed an archive-based consumer build. This is still a
state-only development SDK, not the complete three-channel SDK in the plan.

```sh
bash scripts/test-harmony-background-store.sh
```

`bun run test:companion` runs both the state unit tests and native integration
regressions. The workspace package is registered in `bun.lock`.

Run the core regression tests from the repository root:

```sh
bun test packages/companion/tests/state.test.ts packages/framework/tests/sync-state.test.ts
```
