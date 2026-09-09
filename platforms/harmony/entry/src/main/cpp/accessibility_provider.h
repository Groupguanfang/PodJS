#pragma once
#include <cstdint>
#include <functional>
#include <array>
#include <string>
#include "podjs_runtime.h"
struct ArkUI_AccessibilityProvider;

namespace pod_a11y {
using Action = std::function<bool(int32_t, uint64_t, uint8_t)>;
bool bind(ArkUI_AccessibilityProvider* provider, Action action);
// checked, unchecked, mixed, expanded, collapsed, busy; all labels are copied.
void setStateLabels(const std::array<std::string, 6>& labels);
// Caller owns the runtime mutex. Copies all borrowed C ABI text before return.
void commit(PodRuntime* runtime, uint32_t width, uint32_t height, double x, double y);
void hide();
// Invoke after releasing the runtime mutex. No OS calls occur during commit.
void flush();
}
