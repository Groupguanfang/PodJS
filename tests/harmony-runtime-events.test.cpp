#include "runtime_events.h"
#include <cassert>
#include <deque>

static std::deque<std::string> effects;
static std::string borrowed;
static std::string posted;
static int postCalls = 0;
extern "C" const char* pod_runtime_poll_effect(PodRuntime*) {
  if (effects.empty()) return nullptr;
  borrowed = effects.front(); effects.pop_front(); return borrowed.c_str();
}
extern "C" int32_t pod_runtime_post_event(PodRuntime*, const char* json) {
  ++postCalls; posted = json;
  return posted == "invalid" ? -1 : 0;
}
int main() {
  pod_host::RuntimeEvents bridge;
  // Opaque identity only; test doubles never dereference the pointer.
  int identity = 0;
  auto* runtime = reinterpret_cast<PodRuntime*>(&identity);
  assert(!bridge.poll(nullptr));
  assert(!bridge.post(nullptr, "{}"));
  effects = {"first", "第二"};
  assert(bridge.hasEffect(runtime));
  assert(bridge.hasEffect(runtime));
  assert(effects.size() == 1);
  auto first = bridge.poll(runtime);
  assert(first && *first == "first");
  auto second = bridge.poll(runtime);
  assert(second && *second == "第二" && *first == "first");
  assert(!bridge.poll(runtime));
  assert(!bridge.hasEffect(runtime));
  assert(!bridge.post(runtime, ""));
  assert(!bridge.post(runtime, std::string_view("{}\0extra", 8)));
  assert(!bridge.post(runtime, std::string(bridge.maxEventBytes + 1, 'x')));
  assert(postCalls == 0);
  assert(!bridge.post(runtime, "invalid"));
  assert(bridge.post(runtime, "{\"t\":\"service.result\",\"value\":\"你好\"}"));
  assert(posted.find("你好") != std::string::npos);
  for (size_t i = 1; i < bridge.maxPending; ++i) assert(bridge.post(runtime, "{}"));
  const auto calls = postCalls;
  assert(!bridge.post(runtime, "{}") && postCalls == calls);
  assert(bridge.didFrame());
  assert(!bridge.didFrame());
  assert(bridge.post(runtime, "{}"));
  bridge.didFrame();
  const std::string large = "{\"v\":\"" + std::string(bridge.maxEventBytes - 8, 'x') + "\"}";
  assert(large.size() == bridge.maxEventBytes);
  for (int i = 0; i < 4; ++i) assert(bridge.post(runtime, large));
  assert(!bridge.post(runtime, "{}"));
  bridge.didFrame();
  assert(bridge.post(runtime, large));
}
