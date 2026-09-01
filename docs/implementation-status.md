# Implementation status

This repository is an executable framework baseline, not a claim that all v1
acceptance gates have passed.

| Milestone | Current evidence | Remaining gate |
| --- | --- | --- |
| Feasibility / Android | OWW242 API 30 real device loads the signed bundle, starts QuickJS, emits a 6,464-word DrawList and presents Vulkan rectangles | font/texture/triangle rendering |
| Feasibility / Wear OS | ARMv7/ARM64 APK builds with the shared Vulkan AAR and rotary adapter | emulator launch/input run |
| Feasibility / watchOS | Xcode 26.6 on an Apple Silicon Mac cross-builds Rust/QuickJS for watchOS device and simulator, packages both slices as an XCFramework, compiles the Swift/SpriteKit host, links the runtime and passes two parser tests on a watchOS 26.5 Apple Watch SE simulator | embed a runnable gallery app, validate input/rendering, and run on Apple Watch hardware |
| Feasibility / HarmonyOS | DevEco 26 on Windows cross-builds ARM64 Rust/QuickJS; the compiled N-API host validates and evaluates embedded assets, owns the XComponent NativeWindow lifecycle, accepts touch/frame callbacks and emits an unsigned HAP | configure signing, build with the HarmonyOS 6.1 wearable SDK and run on WATCH 5 |
| Runtime Alpha | C ABI, package preflight, 240x240 metrics, frame-boundary events and deterministic hash snapshots have native tests | four-host tape parity |
| Renderer Alpha | Android Vulkan swapchain/RECT is live; the DevEco-compiled Harmony EGL/GLES3 path clears, scales RECT commands and swaps the XComponent surface | run GLES on WATCH 5; full DrawList on Vulkan/GLES/SpriteKit and screenshot goldens |
| Framework Beta | Solid API, capabilities, rotary, lifecycle/theme, KV, haptics and Android HTTP bridge exist | four-host error recovery and storage/network integration tests |
| v1 | not reached | device matrix, 60-second frame gate and 10-minute stability/power baseline |

Platform source that has not been compiled by its native SDK must not be used as
release evidence. A successful APK/HAP/XCFramework transfer is likewise not a
physical interaction or performance acceptance result.

The current HarmonyOS build proof used SDK/API 26 and a connected API 26 phone
emulator. It validates the project schema and native toolchain only: the emulator
is not a wearable, the HAP is unsigned, and none of those facts satisfy the
HarmonyOS 6.1 / HUAWEI WATCH 5 device gate.
An install attempt on that emulator was rejected with bundle-manager code
`9568320` (`no signature file`); no unsigned-install bypass was applied.

The current watchOS proof used Xcode 26.6, watchOS SDK/runtime 26.5 and the
Apple Watch SE (3rd generation, 40 mm) arm64 simulator. The device archive
contains both watchOS 11-compatible `arm64_32` and newer `arm64` slices, but it
has not been signed, embedded, installed or exercised on physical Apple Watch
hardware.
