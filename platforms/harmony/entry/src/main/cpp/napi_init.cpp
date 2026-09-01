#include <napi/native_api.h>
#include <string>
#include "podjs_runtime.h"
static napi_value preflight(napi_env env,napi_callback_info info){size_t n=2;napi_value a[2];napi_get_cb_info(env,info,&n,a,nullptr,nullptr);char target[64]{};size_t len=0;napi_get_value_string_utf8(env,a[0],target,sizeof(target),&len);uint32_t abi=0;napi_get_value_uint32(env,a[1],&abi);bool ok=std::string(target)=="harmonyos-watch"&&abi==pod_runtime_abi_version();napi_value out,key,val;napi_create_object(env,&out);napi_create_string_utf8(env,"ok",NAPI_AUTO_LENGTH,&key);napi_get_boolean(env,ok,&val);napi_set_property(env,out,key,val);if(!ok){napi_create_string_utf8(env,"target/ABI mismatch",NAPI_AUTO_LENGTH,&val);napi_set_named_property(env,out,"error",val);}return out;}
static napi_value boot(napi_env env,napi_callback_info){napi_value v;napi_get_boolean(env,true,&v);return v;}
EXTERN_C_START static napi_value init(napi_env env,napi_value exports){napi_property_descriptor p[]={{"preflight",nullptr,preflight,nullptr,nullptr,nullptr,napi_default,nullptr},{"boot",nullptr,boot,nullptr,nullptr,nullptr,napi_default,nullptr}};napi_define_properties(env,exports,2,p);return exports;} EXTERN_C_END
static napi_module mod={1,0,nullptr,init,"podjs_harmony",nullptr,{0}};extern "C" __attribute__((constructor)) void register_module(){napi_module_register(&mod);}
