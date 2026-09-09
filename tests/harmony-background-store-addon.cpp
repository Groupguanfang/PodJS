#include "background_store_napi.h"
static napi_value init(napi_env env, napi_value exports) {
  return pod_background_store::install(env, exports) ? exports : nullptr;
}
NAPI_MODULE(NODE_GYP_MODULE_NAME, init)
