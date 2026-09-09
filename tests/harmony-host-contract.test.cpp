#include "host_contract.h"
#include <cassert>
#include <limits>

int main() {
  using pod_host::accepts;
  const auto linked = PODJS_RUNTIME_ABI_VERSION;
  assert(accepts("harmonyos-watch", 1, linked));
  assert(accepts("harmonyos-watch", 2, linked));
  for (double invalid : {0.0, -1.0, 1.5, 2.5, 3.0, 4294967297.0,
      std::numeric_limits<double>::infinity(), std::numeric_limits<double>::quiet_NaN()}) {
    assert(!accepts("harmonyos-watch", invalid, linked));
  }
  assert(!accepts("android-watch", 2, linked));
  assert(!accepts(std::string_view("harmonyos-watch\0extra", 20), 2, linked));
  assert(!accepts("harmonyos-watch", 1, 1));
  assert(!accepts("harmonyos-watch", 2, 3));
  assert(!pod_host::hasCapability("net.http", false));
  assert(pod_host::hasCapability("net.http", true));
  assert(!pod_host::hasCapability("notification.local", true));
  assert(!pod_host::hasCapability("companion.sync.state", true));
  assert(!pod_host::hasCapability(std::string_view("net.http\0extra", 14), true));
  assert(pod_host::capabilitiesJson() == "[\"input.touch\",\"input.rotary\",\"data.kv\",\"device.haptics\",\"host.lifecycle\",\"host.theme\",\"display.round\",\"input.back\",\"net.http\",\"data.fs\"]");
}
