# PodJS architecture

PodJS keeps PocketJS as a pinned submodule and adds watch hosts around its
existing core/surface/guest split. The submodule revision is the single source
for the TypeScript framework, Rust crates, DrawList specification and asset
compiler.

## Frame transaction

Every active frame performs these operations in order:

1. Queue host lifecycle, theme, back and relative-axis facts.
2. Deliver those facts through the `pod` surface at the frame boundary.
3. Resolve touch-down hit facts against the last committed PocketJS tree.
4. Run exactly one guest `frame()` turn.
5. Advance the retained UI core once at 60 Hz.
6. Snapshot the DrawList and resource revisions.
7. Submit GPU work only when the DrawList hash or a resource revision changed.

Background state stops both the guest clock and GPU submission. Resuming keeps
the guest/core state, clears contacts and pending relative-axis motion, and
allows the platform renderer to rebuild its swapchain or scene resources.

## Renderer split

Android and Wear OS consume the C ABI from a shared JNI library and translate
the DrawList through Vulkan 1.1. HarmonyOS exposes the same ABI through N-API
and presents with EGL/GLES3. watchOS consumes the encoded DrawList through a
Swift C module and maps supported operations to SpriteKit nodes. Unsupported
watchOS operations are build errors; they never disappear at runtime.

## Production boundary

The runtime has no API for downloading or evaluating another bundle after
mount. `pod_runtime_eval_bundle` is a one-shot boot operation. Network results,
storage, haptics and system facts enter only through mounted capabilities and
frame-boundary deliveries.
