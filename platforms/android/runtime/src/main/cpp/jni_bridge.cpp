#include <jni.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <algorithm>
#include <memory>
#include <string>
#include <vector>
#include "podjs_runtime.h"
#include "vulkan_renderer.h"

struct Host {
  PodRuntime* runtime{};
  std::unique_ptr<VulkanRenderer> renderer;
  std::vector<PodTouch> touches;
  std::vector<uint8_t> framebuffer;
  uint32_t width{};
  uint32_t height{};
  uint32_t logicalWidth{};
  uint32_t logicalHeight{};
  int32_t rotary{};
};
static void fail(JNIEnv* env, const char* message) { jclass c = env->FindClass("java/lang/IllegalStateException"); env->ThrowNew(c, message); }

extern "C" JNIEXPORT jlong JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeCreate(
    JNIEnv* env, jclass, jobject surface, jstring target, jint width, jint height, jfloat density, jstring dataDir) {
  const char* t = env->GetStringUTFChars(target, nullptr); const char* d = env->GetStringUTFChars(dataDir, nullptr);
  if (std::string(t) != "android-watch" && std::string(t) != "wearos-watch") { fail(env, "PodJS target mismatch"); return 0; }
  ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
  const char* capabilities = "[\"input.touch\",\"input.rotary\",\"data.kv\",\"device.haptics\",\"host.lifecycle\",\"host.theme\",\"display.round\",\"input.back\",\"net.http\",\"data.fs\"]";
  PodRuntimeConfig config{sizeof(config), t, PODJS_RUNTIME_ABI_VERSION, 2, (uint32_t)width, (uint32_t)height,
      density, POD_DISPLAY_ROUND, 0, 0, 0, 0, d, capabilities};
  auto host = std::make_unique<Host>(); host->runtime = pod_runtime_create(&config);
  if (!host->runtime) {
    ANativeWindow_release(window);
    env->ReleaseStringUTFChars(target, t); env->ReleaseStringUTFChars(dataDir, d);
    fail(env, pod_runtime_last_error()); return 0;
  }
  host->width = static_cast<uint32_t>(width); host->height = static_cast<uint32_t>(height);
  host->logicalWidth = pod_runtime_logical_width(host->runtime);
  host->logicalHeight = pod_runtime_logical_height(host->runtime);
  const uint32_t rasterWidth = host->logicalWidth * VulkanRenderer::kRasterScale;
  const uint32_t rasterHeight = host->logicalHeight * VulkanRenderer::kRasterScale;
  host->renderer = std::make_unique<VulkanRenderer>(window, width, height, rasterWidth, rasterHeight); ANativeWindow_release(window);
  host->framebuffer.resize(host->renderer->rasterBytes());
  env->ReleaseStringUTFChars(target, t); env->ReleaseStringUTFChars(dataDir, d);
  if (!host->renderer->valid()) { fail(env, "PodJS Vulkan renderer initialization failed"); return 0; }
  return reinterpret_cast<jlong>(host.release());
}
extern "C" JNIEXPORT void JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeBoot(JNIEnv* env, jclass, jlong p, jbyteArray pak, jbyteArray js, jbyteArray manifest) {
  auto* h = reinterpret_cast<Host*>(p); if (!h) return;
  jsize pn=env->GetArrayLength(pak), jn=env->GetArrayLength(js), mn=env->GetArrayLength(manifest); std::vector<uint8_t> pb(pn), jb(jn), mb(mn+1);
  env->GetByteArrayRegion(pak,0,pn,reinterpret_cast<jbyte*>(pb.data())); env->GetByteArrayRegion(js,0,jn,reinterpret_cast<jbyte*>(jb.data()));
  env->GetByteArrayRegion(manifest,0,mn,reinterpret_cast<jbyte*>(mb.data()));
  if (pod_runtime_load_pak(h->runtime,pb.data(),pb.size()) || pod_runtime_validate_package(h->runtime,reinterpret_cast<char*>(mb.data())) || pod_runtime_eval_bundle(h->runtime,jb.data(),jb.size(),"app:///main.js")) fail(env,pod_runtime_last_error());
  else __android_log_print(ANDROID_LOG_INFO, "PodJS", "%s", pod_runtime_receipt(h->runtime));
}
extern "C" JNIEXPORT void JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeResize(JNIEnv* env,jclass,jlong p,jobject s,jint w,jint ht){auto*h=reinterpret_cast<Host*>(p);if(!h)return;h->width=static_cast<uint32_t>(w);h->height=static_cast<uint32_t>(ht);auto*n=ANativeWindow_fromSurface(env,s);h->renderer->replaceWindow(n,w,ht);ANativeWindow_release(n);}
extern "C" JNIEXPORT void JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeDetachSurface(JNIEnv*,jclass,jlong p){auto*h=reinterpret_cast<Host*>(p);if(h)h->renderer->replaceWindow(nullptr,0,0);}
extern "C" JNIEXPORT void JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeInput(JNIEnv* env,jclass,jlong p,jintArray ids,jfloatArray xy,jint count,jint rotary){auto*h=reinterpret_cast<Host*>(p);if(!h)return;std::vector<jint> id(count);std::vector<jfloat> pos(count*2);if(count){env->GetIntArrayRegion(ids,0,count,id.data());env->GetFloatArrayRegion(xy,0,count*2,pos.data());}h->touches.resize(count);for(int i=0;i<count;i++)h->touches[i]={(uint32_t)id[i],pos[i*2]*h->logicalWidth/std::max(1u,h->width),pos[i*2+1]*h->logicalHeight/std::max(1u,h->height)};h->rotary+=rotary;}
extern "C" JNIEXPORT void JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeFrame(JNIEnv*,jclass,jlong p){auto*h=reinterpret_cast<Host*>(p);if(!h)return;PodInputFrame in{sizeof(in),0,0,h->touches.data(),(uint32_t)h->touches.size(),h->rotary,0};h->rotary=0;if(pod_runtime_frame(h->runtime,&in)==0){PodDrawList list{};if(!pod_runtime_snapshot(h->runtime,&list)&&list.changed){bool rasterized=pod_runtime_render_rgba_incremental(h->runtime,VulkanRenderer::kRasterScale,h->framebuffer.data(),h->framebuffer.size())==0;bool submitted=rasterized&&h->renderer->submitRgba(h->framebuffer.data(),h->framebuffer.size(),list.content_hash);__android_log_print(ANDROID_LOG_DEBUG,"PodJS","frame=%llu words=%zu hash=%016llx rasterized=%d submitted=%d",(unsigned long long)list.frame_number,list.word_count,(unsigned long long)list.content_hash,rasterized,submitted);}}}
extern "C" JNIEXPORT jstring JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativePollEffect(JNIEnv* env,jclass,jlong p){auto*h=reinterpret_cast<Host*>(p);if(!h)return nullptr;const char* line=pod_runtime_poll_effect(h->runtime);return line?env->NewStringUTF(line):nullptr;}
extern "C" JNIEXPORT jstring JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativePollNet(JNIEnv* env,jclass,jlong p){auto*h=reinterpret_cast<Host*>(p);if(!h)return nullptr;const char* line=pod_runtime_poll_net_command(h->runtime);return line?env->NewStringUTF(line):nullptr;}
extern "C" JNIEXPORT void JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeCompleteHttp(JNIEnv* env,jclass,jlong p,jint handle,jint status,jstring url,jstring headers,jbyteArray body){auto*h=reinterpret_cast<Host*>(p);if(!h)return;const char*u=env->GetStringUTFChars(url,nullptr);const char*hs=env->GetStringUTFChars(headers,nullptr);jsize n=env->GetArrayLength(body);std::vector<uint8_t>b(n);env->GetByteArrayRegion(body,0,n,reinterpret_cast<jbyte*>(b.data()));pod_runtime_complete_http(h->runtime,handle,status,u,hs,b.data(),b.size());env->ReleaseStringUTFChars(url,u);env->ReleaseStringUTFChars(headers,hs);}
extern "C" JNIEXPORT void JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeFailHttp(JNIEnv* env,jclass,jlong p,jint handle,jstring code,jstring message){auto*h=reinterpret_cast<Host*>(p);if(!h)return;const char*c=env->GetStringUTFChars(code,nullptr);const char*m=env->GetStringUTFChars(message,nullptr);pod_runtime_fail_http(h->runtime,handle,c,m);env->ReleaseStringUTFChars(code,c);env->ReleaseStringUTFChars(message,m);}
extern "C" JNIEXPORT void JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeLifecycle(JNIEnv*,jclass,jlong p,jint s){auto*h=reinterpret_cast<Host*>(p);if(h){if(s==2){h->touches.clear();h->rotary=0;}pod_runtime_set_lifecycle(h->runtime,s);}}
extern "C" JNIEXPORT void JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeTheme(JNIEnv* env,jclass,jlong p,jstring theme){auto*h=reinterpret_cast<Host*>(p);if(!h)return;const char*t=env->GetStringUTFChars(theme,nullptr);pod_runtime_set_theme(h->runtime,t);env->ReleaseStringUTFChars(theme,t);}
extern "C" JNIEXPORT jboolean JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeBack(JNIEnv*,jclass,jlong p){auto*h=reinterpret_cast<Host*>(p);return h&&pod_runtime_post_event(h->runtime,"{\"t\":\"back\"}")==0;}
extern "C" JNIEXPORT void JNICALL Java_dev_podjs_runtime_PodRuntimeView_nativeDestroy(JNIEnv*,jclass,jlong p){auto*h=reinterpret_cast<Host*>(p);if(h){pod_runtime_destroy(h->runtime);delete h;}}
