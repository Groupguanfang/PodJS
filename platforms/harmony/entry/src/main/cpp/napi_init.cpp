#include <ace/xcomponent/native_interface_xcomponent.h>
#include <hilog/log.h>
#include <napi/native_api.h>

#include <algorithm>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "gles_renderer.h"
#include "podjs_runtime.h"

namespace {
struct Host {
  std::mutex mutex;
  OH_NativeXComponent* component = nullptr;
  PodRuntime* runtime = nullptr;
  GlesRenderer renderer;
  std::vector<uint8_t> framebuffer;
  std::vector<PodTouch> touches;
  int32_t rotaryPrimaryMillidegrees = 0;
  uint32_t width = 466;
  uint32_t height = 466;
  uint32_t logicalWidth = 233;
  uint32_t logicalHeight = 233;

  ~Host() {
    if (runtime) pod_runtime_destroy(runtime);
  }

  bool frame() {
    if (!runtime) return false;
    const int32_t rotaryPrimary = rotaryPrimaryMillidegrees;
    rotaryPrimaryMillidegrees = 0;
    PodInputFrame input{sizeof(input), 0, 0, touches.data(),
                        static_cast<uint32_t>(touches.size()), rotaryPrimary, 0};
    if (pod_runtime_frame(runtime, &input) != 0) return false;
    PodDrawList list{};
    const int32_t snapshotResult = pod_runtime_snapshot(runtime, &list);
    if (rotaryPrimary != 0) {
      OH_LOG_Print(LOG_APP, LOG_INFO, 0xD002D00, "PodJS",
                   "axis outcome delta=%{public}d snapshot=%{public}d changed=%{public}d hash=%{public}llu",
                   rotaryPrimary, snapshotResult, list.changed,
                   static_cast<unsigned long long>(list.content_hash));
    }
    if (snapshotResult != 0 || !list.changed) return false;
    if (pod_runtime_render_rgba_incremental(
            runtime, GlesRenderer::kRasterScale, framebuffer.data(), framebuffer.size()) != 0) {
      return false;
    }
    return renderer.submitRgba(framebuffer.data(), framebuffer.size(), list.content_hash);
  }
};

Host g_host;
OH_NativeXComponent_Callback g_callbacks{};

bool bytes(napi_env env, napi_value value, const uint8_t** data, size_t* length) {
  napi_typedarray_type type{};
  napi_value buffer{};
  size_t offset = 0;
  void* raw = nullptr;
  if (napi_get_typedarray_info(env, value, &type, length, &raw, &buffer, &offset) != napi_ok ||
      type != napi_uint8_array || raw == nullptr) {
    napi_throw_type_error(env, nullptr, "PodJS assets must be Uint8Array values");
    return false;
  }
  *data = static_cast<const uint8_t*>(raw);
  return true;
}

napi_value boolean(napi_env env, bool value) {
  napi_value result{};
  napi_get_boolean(env, value, &result);
  return result;
}

napi_value rotary(napi_env env, napi_callback_info info) {
  size_t count = 1;
  napi_value args[1]{};
  napi_get_cb_info(env, info, &count, args, nullptr, nullptr);
  int32_t delta = 0;
  if (count != 1 || napi_get_value_int32(env, args[0], &delta) != napi_ok) {
    napi_throw_type_error(env, nullptr, "rotary requires a millidegree integer");
    return nullptr;
  }
  std::lock_guard lock(g_host.mutex);
  const int64_t accumulated = static_cast<int64_t>(g_host.rotaryPrimaryMillidegrees) + delta;
  g_host.rotaryPrimaryMillidegrees = static_cast<int32_t>(std::clamp(
      accumulated, static_cast<int64_t>(std::numeric_limits<int32_t>::min()),
      static_cast<int64_t>(std::numeric_limits<int32_t>::max())));
  return boolean(env, true);
}

void throwRuntime(napi_env env, const char* fallback) {
  const char* detail = pod_runtime_last_error();
  napi_throw_error(env, nullptr, detail && detail[0] ? detail : fallback);
}

void onSurfaceCreated(OH_NativeXComponent* component, void* window) {
  std::lock_guard lock(g_host.mutex);
  uint64_t width = 0;
  uint64_t height = 0;
  if (OH_NativeXComponent_GetXComponentSize(component, window, &width, &height) !=
      OH_NATIVEXCOMPONENT_RESULT_SUCCESS) return;
  g_host.width = static_cast<uint32_t>(width);
  g_host.height = static_cast<uint32_t>(height);
  if (!g_host.runtime) {
    g_host.logicalWidth = (g_host.width + GlesRenderer::kRasterScale - 1) /
                          GlesRenderer::kRasterScale;
    g_host.logicalHeight = (g_host.height + GlesRenderer::kRasterScale - 1) /
                           GlesRenderer::kRasterScale;
  }
  const uint32_t rasterWidth = g_host.logicalWidth * GlesRenderer::kRasterScale;
  const uint32_t rasterHeight = g_host.logicalHeight * GlesRenderer::kRasterScale;
  if (g_host.renderer.attach(window, g_host.width, g_host.height, rasterWidth, rasterHeight)) g_host.frame();
}

void onSurfaceChanged(OH_NativeXComponent* component, void* window) {
  onSurfaceCreated(component, window);
}

void onSurfaceDestroyed(OH_NativeXComponent*, void*) {
  std::lock_guard lock(g_host.mutex);
  g_host.renderer.detach();
  g_host.touches.clear();
}

void onTouch(OH_NativeXComponent* component, void* window) {
  OH_NativeXComponent_TouchEvent event{};
  if (OH_NativeXComponent_GetTouchEvent(component, window, &event) !=
      OH_NATIVEXCOMPONENT_RESULT_SUCCESS) return;
  std::lock_guard lock(g_host.mutex);
  g_host.touches.clear();
  const uint32_t count = std::min(event.numPoints, static_cast<uint32_t>(PODJS_MAX_TOUCHES));
  for (uint32_t index = 0; index < count; ++index) {
    const auto& point = event.touchPoints[index];
    if (!point.isPressed) continue;
    g_host.touches.push_back({static_cast<uint32_t>(point.id),
                              point.x * g_host.logicalWidth / std::max(1u, g_host.width),
                              point.y * g_host.logicalHeight / std::max(1u, g_host.height)});
  }
  // Some HarmonyOS wearable builds report isPressed=false for every entry in
  // touchPoints during MOVE even though the primary contact is still down.
  // Treating that snapshot as empty synthesizes an early UP, so a swipe turns
  // into a tap at its starting row. The event-level point is authoritative for
  // the active DOWN/MOVE contact and keeps its id stable until the real UP.
  if (g_host.touches.empty() &&
      (event.type == OH_NATIVEXCOMPONENT_DOWN || event.type == OH_NATIVEXCOMPONENT_MOVE)) {
    g_host.touches.push_back({static_cast<uint32_t>(event.id),
                              event.x * g_host.logicalWidth / std::max(1u, g_host.width),
                              event.y * g_host.logicalHeight / std::max(1u, g_host.height)});
  }
}

void onFrame(OH_NativeXComponent*, uint64_t, uint64_t) {
  std::lock_guard lock(g_host.mutex);
  g_host.frame();
}

void onSurfaceShow(OH_NativeXComponent*, void*) {
  std::lock_guard lock(g_host.mutex);
  if (g_host.runtime) pod_runtime_set_lifecycle(g_host.runtime, POD_LIFECYCLE_ACTIVE);
}

void onSurfaceHide(OH_NativeXComponent*, void*) {
  std::lock_guard lock(g_host.mutex);
  g_host.touches.clear();
  g_host.rotaryPrimaryMillidegrees = 0;
  if (g_host.runtime) pod_runtime_set_lifecycle(g_host.runtime, POD_LIFECYCLE_BACKGROUND);
}

napi_value preflight(napi_env env, napi_callback_info info) {
  size_t count = 2;
  napi_value args[2]{};
  napi_get_cb_info(env, info, &count, args, nullptr, nullptr);
  char target[64]{};
  size_t length = 0;
  uint32_t abi = 0;
  napi_get_value_string_utf8(env, args[0], target, sizeof(target), &length);
  napi_get_value_uint32(env, args[1], &abi);
  const bool ok = std::string(target) == "harmonyos-watch" && abi == pod_runtime_abi_version();
  napi_value result{};
  napi_value value{};
  napi_create_object(env, &result);
  napi_get_boolean(env, ok, &value);
  napi_set_named_property(env, result, "ok", value);
  if (!ok) {
    napi_create_string_utf8(env, "target/ABI mismatch", NAPI_AUTO_LENGTH, &value);
    napi_set_named_property(env, result, "error", value);
  }
  return result;
}

napi_value boot(napi_env env, napi_callback_info info) {
  size_t count = 3;
  napi_value args[3]{};
  napi_get_cb_info(env, info, &count, args, nullptr, nullptr);
  if (count != 3) {
    napi_throw_type_error(env, nullptr, "boot requires JS, pak and manifest assets");
    return nullptr;
  }
  const uint8_t* js = nullptr;
  const uint8_t* pak = nullptr;
  const uint8_t* manifest = nullptr;
  size_t jsLength = 0;
  size_t pakLength = 0;
  size_t manifestLength = 0;
  if (!bytes(env, args[0], &js, &jsLength) || !bytes(env, args[1], &pak, &pakLength) ||
      !bytes(env, args[2], &manifest, &manifestLength)) return nullptr;

  std::vector<char> manifestText(manifestLength + 1, 0);
  std::memcpy(manifestText.data(), manifest, manifestLength);
  std::lock_guard lock(g_host.mutex);
  if (g_host.runtime) {
    napi_throw_error(env, nullptr, "PodJS runtime is already booted");
    return nullptr;
  }
  const char* capabilities =
      "[\"input.touch\",\"input.rotary\",\"data.kv\",\"device.haptics\","
      "\"host.lifecycle\",\"host.theme\",\"display.round\",\"input.back\","
      "\"net.http\",\"data.fs\"]";
  PodRuntimeConfig config{sizeof(config), "harmonyos-watch", PODJS_RUNTIME_ABI_VERSION, 2,
                          g_host.width, g_host.height, 2.0f, POD_DISPLAY_ROUND,
                          0, 0, 0, 0, nullptr, capabilities};
  std::unique_ptr<PodRuntime, decltype(&pod_runtime_destroy)> runtime(
      pod_runtime_create(&config), pod_runtime_destroy);
  if (!runtime || pod_runtime_load_pak(runtime.get(), pak, pakLength) != 0 ||
      pod_runtime_validate_package(runtime.get(), manifestText.data()) != 0 ||
      pod_runtime_eval_bundle(runtime.get(), js, jsLength, "app:///main.js") != 0) {
    throwRuntime(env, "PodJS boot failed");
    return nullptr;
  }
  g_host.runtime = runtime.release();
  g_host.logicalWidth = pod_runtime_logical_width(g_host.runtime);
  g_host.logicalHeight = pod_runtime_logical_height(g_host.runtime);
  g_host.framebuffer.resize(
      static_cast<size_t>(g_host.logicalWidth) * GlesRenderer::kRasterScale *
      g_host.logicalHeight * GlesRenderer::kRasterScale * 4);
  g_host.frame();
  return boolean(env, true);
}

napi_value init(napi_env env, napi_value exports) {
  napi_value xcomponentValue{};
  if (napi_get_named_property(env, exports, OH_NATIVE_XCOMPONENT_OBJ, &xcomponentValue) == napi_ok) {
    OH_NativeXComponent* component = nullptr;
    if (napi_unwrap(env, xcomponentValue, reinterpret_cast<void**>(&component)) == napi_ok && component) {
      g_host.component = component;
      g_callbacks.OnSurfaceCreated = onSurfaceCreated;
      g_callbacks.OnSurfaceChanged = onSurfaceChanged;
      g_callbacks.OnSurfaceDestroyed = onSurfaceDestroyed;
      g_callbacks.DispatchTouchEvent = onTouch;
      OH_NativeXComponent_RegisterCallback(component, &g_callbacks);
      OH_NativeXComponent_ExpectedRateRange rate{30, 60, 60};
      OH_NativeXComponent_SetExpectedFrameRateRange(component, &rate);
      OH_NativeXComponent_RegisterOnFrameCallback(component, onFrame);
      OH_NativeXComponent_RegisterSurfaceShowCallback(component, onSurfaceShow);
      OH_NativeXComponent_RegisterSurfaceHideCallback(component, onSurfaceHide);
    }
  }
  napi_property_descriptor properties[] = {
      {"preflight", nullptr, preflight, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"boot", nullptr, boot, nullptr, nullptr, nullptr, napi_default, nullptr},
      {"rotary", nullptr, rotary, nullptr, nullptr, nullptr, napi_default, nullptr},
  };
  napi_define_properties(env, exports, 3, properties);
  return exports;
}
}

static napi_module module = {1, 0, nullptr, init, "podjs_harmony", nullptr, {0}};

extern "C" __attribute__((constructor)) void register_module() {
  napi_module_register(&module);
}
