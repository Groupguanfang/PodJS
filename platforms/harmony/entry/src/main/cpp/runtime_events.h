#pragma once

#include <optional>
#include <string>
#include <string_view>
#include "podjs_runtime.h"

namespace pod_host {
// All methods must be serialized with frame/boot/destruction by the host mutex.
// No method runs guest code or initializes a renderer.
class RuntimeEvents {
 public:
  static constexpr size_t maxEventBytes = 1024 * 1024;
  static constexpr size_t maxPending = 256;
  static constexpr size_t maxPendingBytes = 4 * 1024 * 1024;

  std::optional<std::string> poll(PodRuntime* runtime) {
    if (!runtime) return std::nullopt;
    if (ready_) {
      auto result = std::move(ready_); ready_.reset(); return result;
    }
    const char* borrowed = pod_runtime_poll_effect(runtime);
    if (!borrowed) return std::nullopt;
    return std::string(borrowed); // Copy before another frame/poll can invalidate it.
  }

  bool hasEffect(PodRuntime* runtime) {
    if (!ready_) ready_ = poll(runtime);
    return ready_.has_value();
  }

  bool post(PodRuntime* runtime, std::string_view json) {
    if (!runtime || pending_ >= maxPending || json.empty() ||
        json.size() > maxEventBytes || json.size() > maxPendingBytes - pendingBytes_ ||
        json.find('\0') != std::string_view::npos) return false;
    const std::string terminated(json);
    // Core validates that the payload is a JSON object before enqueuing it.
    if (pod_runtime_post_event(runtime, terminated.c_str()) != 0) return false;
    ++pending_;
    pendingBytes_ += json.size();
    return true;
  }

  bool didFrame() {
    const bool hadEvents = pending_ != 0;
    pending_ = 0; pendingBytes_ = 0; return hadEvents;
  }

 private:
  size_t pending_ = 0;
  size_t pendingBytes_ = 0;
  std::optional<std::string> ready_;
};
}
