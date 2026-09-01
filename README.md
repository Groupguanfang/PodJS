# PodJS

PodJS is a watch-first host layer for PocketJS. One bundled Solid TSX app runs
inside the PocketJS QuickJS guest and targets four native watch shells:

- `android-watch` — Android 11/API 30+, Vulkan 1.1, ARMv7 and ARM64
- `wearos-watch` — Wear OS API 30+, Vulkan 1.1
- `watchos-watch` — watchOS 11+, WatchKit/SpriteKit
- `harmonyos-watch` — HarmonyOS 6.1 smart wearable, ArkUI/XComponent/GLES3

The application viewport is always 240×240 logical pixels. Hosts report the
physical display, shape and safe insets through `@podjs/framework`; application
code branches on capabilities, never target names.

The production runtime evaluates only the JS and pak embedded in the signed
application package. Debug deployment may replace those assets over ADB or HDC.

## Bootstrap

```sh
bun install
bun run doctor
bun test
cargo test --workspace
```

Build and package a target with the checked-in CLI:

```sh
bun run pod build --target=android-watch
bun run pod package --target=android-watch
```

Android packaging emits an ARMv7/ARM64 debug APK, release AAB and reusable
AAR. Wear OS uses the same runtime/AAR with a separate manifest and rotary
shell. watchOS packaging is run on the configured Xcode 16+ Mac; HarmonyOS
packaging is run with DevEco/hvigor on the HarmonyOS 6.1 host.

## Implemented baseline

- Pinned PocketJS submodule, Solid TSX compiler path and four target profiles.
- Graphics-independent Rust/QuickJS runtime with a versioned C ABI, 60 Hz
  frame transaction, touch/rotary/lifecycle/theme delivery, HTTP host commands,
  bounded per-app KV/files, demand rendering and resource revisions.
- Fail-closed package manifest validation before JavaScript execution. Target,
  host ABI, capability list, PocketJS revision and bundle/pak hashes are checked.
- Android/Wear AAR/JNI with dual ARM ABIs, API 30-compatible asset loading,
  lifecycle/surface recovery, haptics/network bridge and Vulkan 1.1 swapchain.
- watchOS Swift Package/SpriteKit and HarmonyOS Stage/XComponent/N-API/GLES3
  source trees, ready for their platform-native build hosts.
- A 1,000-row Solid component/performance gallery and contract tests.

The Android Vulkan feasibility renderer currently submits RECT and a flat
fallback for GRAD_RECT. Font atlases, textures, triangles and precise gradients
remain Renderer Alpha work; therefore the current gallery intentionally proves
QuickJS/DrawList/GPU plumbing but is not yet a v1 visual acceptance build.

See [architecture.md](docs/architecture.md) for the runtime and host contract.
