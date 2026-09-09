#pragma once

#include <cmath>
#include <array>
#include <string>
#include <string_view>
#include "podjs_runtime.h"

namespace pod_host {
inline constexpr std::array<std::string_view, 10> capabilities = {
    "input.touch", "input.rotary", "data.kv", "device.haptics",
    "host.lifecycle", "host.theme", "display.round", "input.back", "net.http", "data.fs"};

inline bool hasCapability(std::string_view name, bool booted) {
  if (!booted) return false;
  for (const auto capability : capabilities) if (name == capability) return true;
  return false;
}

inline std::string capabilitiesJson() {
  std::string result = "[";
  for (const auto capability : capabilities) {
    if (result.size() > 1) result += ',';
    result += '"'; result += capability; result += '"';
  }
  return result + ']';
}

// Reject a stale linked runtime before advertising support. ABI 2 deliberately
// preserves ABI 1 package compatibility; the package is validated again at boot.
inline bool accepts(std::string_view target, double requested, uint32_t linked) {
  return target == "harmonyos-watch" && linked == PODJS_RUNTIME_ABI_VERSION &&
      std::isfinite(requested) && std::floor(requested) == requested &&
      requested >= PODJS_RUNTIME_MIN_ABI_VERSION && requested <= linked;
}
}
