#include "state_store_napi.h"
#include "incoming_file_napi.h"
#include "pairing_lease_napi.h"
static napi_value init(napi_env env, napi_value exports) {
  return pod_background_store::install(env, exports, true) && pod_incoming_napi::install(env, exports) && pod_pairing_napi::install(env, exports) ? exports : nullptr;
}
static napi_module module = { 1, 0, nullptr, init, "podjs_companion", nullptr, {0} };
extern "C" __attribute__((constructor)) void registerPodCompanionModule() {
  napi_module_register(&module);
}
