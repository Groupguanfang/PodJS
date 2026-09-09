#include "journal_napi.h"
static napi_value init(napi_env env, napi_value exports) {
  napi_property_descriptor properties[] = {
    {"open", nullptr, pod_store::open, nullptr, nullptr, nullptr, napi_default, nullptr},
    {"read", nullptr, pod_store::read, nullptr, nullptr, nullptr, napi_default, nullptr},
    {"write", nullptr, pod_store::write, nullptr, nullptr, nullptr, napi_default, nullptr}
  };
  napi_define_properties(env, exports, 3, properties); return exports;
}
NAPI_MODULE(NODE_GYP_MODULE_NAME, init)
