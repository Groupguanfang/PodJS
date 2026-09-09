#pragma once
#include <napi/native_api.h>
#include "podjs_runtime.h"
#include "journal_storage.h"
#include <atomic>
#include <cmath>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

// Host-only independent one-shot runs. No renderer, UI runtime, or frame lock.
// The caller must authenticate installed bundles and bind config to OS identity.
namespace pod_background {
inline std::atomic<size_t> liveRuns{0};
struct Run {
  PodBackgroundRun* handle = nullptr;
  napi_env owner;
  bool started = false; // Accessed only on the owning JS thread.
  bool ready = true;
  bool schedulerOnly = false;
  bool cancelRequested = false; // Owning JS thread only.
  std::atomic<bool> closed{false}, envClosing{false};
  // Held before the host claims a task, throughout execution/result persistence,
  // and until close. Work retains Run when close races execution.
  std::unique_ptr<pod_store::JournalStorage> lease;
  Run() { ++liveRuns; }
  ~Run() { pod_background_close(handle); --liveRuns; }
};
inline std::mutex registryMutex;
inline std::map<uint32_t, std::shared_ptr<Run>> registry;
inline uint64_t nextId = 1;
inline void cleanup(void* data) {
  auto env = static_cast<napi_env>(data);
  std::lock_guard lock(registryMutex);
  for (auto it = registry.begin(); it != registry.end();) {
    if (it->second->owner == env) {
      it->second->closed = true; it->second->envClosing = true;
      pod_background_cancel(it->second->handle);
      it = registry.erase(it);
    } else ++it;
  }
}
inline napi_value error(napi_env env, const char* message) {
  napi_throw_error(env, nullptr, message); return nullptr;
}
inline uint32_t argument(napi_env env, napi_callback_info info) {
  size_t count = 1; napi_value value{}; double id = 0;
  if (napi_get_cb_info(env, info, &count, &value, nullptr, nullptr) != napi_ok || count != 1 ||
      napi_get_value_double(env, value, &id) != napi_ok || !std::isfinite(id) ||
      id < 1 || id > UINT32_MAX || std::floor(id) != id) return 0;
  return static_cast<uint32_t>(id);
}
inline std::shared_ptr<Run> lookup(napi_env env, uint32_t id) {
  std::lock_guard lock(registryMutex);
  auto it = registry.find(id);
  return it != registry.end() && it->second->owner == env ? it->second : nullptr;
}
inline napi_value openConfig(napi_env env, napi_value value) {
  size_t length = 0;
  if (napi_get_value_string_utf8(env, value, nullptr, 0, &length) != napi_ok ||
      length == 0 || length > 2 * 1024 * 1024) return error(env, "Invalid background config");
  std::vector<char> bytes(length + 1);
  if (napi_get_value_string_utf8(env, value, bytes.data(), bytes.size(), &length) != napi_ok)
    return error(env, "Invalid background config");
  std::lock_guard lock(registryMutex);
  // Process-wide bound also limits abandoned handles across ArkTS environments.
  if (liveRuns >= 16 || nextId > UINT32_MAX) return error(env, "Background handle capacity exceeded");
  auto run = std::make_shared<Run>(); run->owner = env;
  run->handle = pod_background_open(reinterpret_cast<const uint8_t*>(bytes.data()), length);
  if (!run->handle) return error(env, "Invalid background config");
  const auto id = static_cast<uint32_t>(nextId++);
  napi_value result{};
  if (napi_create_uint32(env, id, &result) != napi_ok) return error(env, "Background handle allocation failed");
  registry.emplace(id, run); return result;
}
inline napi_value open(napi_env env, napi_callback_info info) {
  size_t count = 1; napi_value value{};
  if (napi_get_cb_info(env, info, &count, &value, nullptr, nullptr) != napi_ok || count != 1)
    return error(env, "Invalid background config");
  return openConfig(env, value);
}
struct OpenWork {
  std::shared_ptr<Run> run;
  uint32_t id = 0;
  napi_async_work work = nullptr;
  napi_deferred deferred = nullptr;
  std::string root, error;
};
inline void discard(const std::shared_ptr<Run>& run, uint32_t id) {
  std::lock_guard lock(registryMutex);
  run->closed = true; pod_background_cancel(run->handle);
  auto it = registry.find(id);
  if (it != registry.end() && it->second == run) registry.erase(it);
}
inline void acquireLease(napi_env, void* data) {
  auto& job = *static_cast<OpenWork*>(data);
  try {
    if (job.run->closed) throw std::runtime_error("Background handle closed");
    job.run->lease = std::make_unique<pod_store::JournalStorage>(job.root,
        job.run->schedulerOnly ? "podjs-scheduler" : "podjs-execution");
  } catch (const std::exception& error) { job.error = error.what(); }
  catch (...) { job.error = "Cannot acquire background execution lease"; }
}
inline void completeLease(napi_env env, napi_status status, void* data) {
  std::unique_ptr<OpenWork> job(static_cast<OpenWork*>(data));
  if (job->run->envClosing) {
    discard(job->run, job->id);
    napi_delete_async_work(env, job->work); return;
  }
  if (status != napi_ok || job->run->closed) job->error = "Background handle closed";
  napi_value value{};
  if (job->error.empty()) {
    if (napi_create_uint32(env, job->id, &value) == napi_ok) {
      job->run->ready = true;
      if (napi_resolve_deferred(env, job->deferred, value) != napi_ok) discard(job->run, job->id);
    } else discard(job->run, job->id);
  } else {
    discard(job->run, job->id);
    napi_value message{}, code{};
    const char* label = job->error == "Journal already owned" ? "busy" : "storage_error";
    napi_create_string_utf8(env, label, NAPI_AUTO_LENGTH, &code);
    napi_create_string_utf8(env, job->error.data(), job->error.size(), &message);
    napi_create_error(env, code, message, &value); napi_reject_deferred(env, job->deferred, value);
  }
  napi_delete_async_work(env, job->work);
}
inline napi_value openLease(napi_env env, napi_callback_info info, bool schedulerOnly) {
  size_t count = 2, length = 0; napi_value args[2]{};
  const size_t rootIndex = schedulerOnly ? 0 : 1;
  if (napi_get_cb_info(env, info, &count, args, nullptr, nullptr) != napi_ok || count != rootIndex + 1 ||
      napi_get_value_string_utf8(env, args[rootIndex], nullptr, 0, &length) != napi_ok || !length || length > 4096)
    return error(env, "Invalid background files directory");
  std::vector<char> bytes(length + 1);
  if (napi_get_value_string_utf8(env, args[rootIndex], bytes.data(), bytes.size(), &length) != napi_ok)
    return error(env, "Invalid background files directory");
  std::string root(bytes.data(), length);
  if (root.front() != '/' || !pod_store::utf8(root)) return error(env, "Invalid background files directory");
  // The numeric ID remains private until lease acquisition completes.
  uint32_t id = 0;
  if (schedulerOnly) {
    std::lock_guard lock(registryMutex);
    if (liveRuns >= 16 || nextId > UINT32_MAX) return error(env, "Background handle capacity exceeded");
    auto run = std::make_shared<Run>(); run->owner = env; run->schedulerOnly = true;
    id = static_cast<uint32_t>(nextId++); registry.emplace(id, run);
  } else {
    napi_value value = openConfig(env, args[0]); if (!value) return nullptr;
    napi_get_value_uint32(env, value, &id);
  }
  auto run = lookup(env, id); run->ready = false;
  auto job = std::make_unique<OpenWork>(); job->run = run; job->id = id; job->root = std::move(root);
  napi_value promise{}, name{};
  if (napi_create_promise(env, &job->deferred, &promise) != napi_ok ||
      napi_create_string_utf8(env, "PodJS execution lease", NAPI_AUTO_LENGTH, &name) != napi_ok ||
      napi_create_async_work(env, nullptr, name, acquireLease, completeLease, job.get(), &job->work) != napi_ok) {
    discard(run, id); return error(env, "Background lease worker allocation failed");
  }
  if (napi_queue_async_work(env, job->work) != napi_ok) {
    napi_delete_async_work(env, job->work); discard(run, id); return error(env, "Background lease worker queue failed");
  }
  job.release(); return promise;
}
inline napi_value openLeased(napi_env env, napi_callback_info info) { return openLease(env, info, false); }
inline napi_value openSchedulerLease(napi_env env, napi_callback_info info) { return openLease(env, info, true); }
struct Work {
  std::shared_ptr<Run> run;
  napi_async_work work = nullptr;
  napi_deferred deferred = nullptr;
  std::string result;
};
inline void execute(napi_env, void* data) {
  auto& job = *static_cast<Work*>(data);
  const char* result = pod_background_execute(job.run->handle);
  if (result) job.result = result;
}
inline void complete(napi_env env, napi_status status, void* data) {
  std::unique_ptr<Work> job(static_cast<Work*>(data));
  napi_value value{};
  if (status == napi_ok && !job->result.empty()) {
    napi_create_string_utf8(env, job->result.data(), job->result.size(), &value);
    napi_resolve_deferred(env, job->deferred, value);
  } else {
    napi_value message{};
    napi_create_string_utf8(env, "Background execution failed", NAPI_AUTO_LENGTH, &message);
    napi_create_error(env, nullptr, message, &value);
    napi_reject_deferred(env, job->deferred, value);
  }
  napi_delete_async_work(env, job->work);
}
inline napi_value start(napi_env env, napi_callback_info info) {
  auto run = lookup(env, argument(env, info));
  if (!run || run->schedulerOnly || !run->ready || run->closed || run->started) return error(env, "Background handle missing or already started");
  auto job = std::make_unique<Work>(); job->run = run;
  napi_value promise{}, name{};
  if (napi_create_promise(env, &job->deferred, &promise) != napi_ok ||
      napi_create_string_utf8(env, "PodJS background run", NAPI_AUTO_LENGTH, &name) != napi_ok ||
      napi_create_async_work(env, nullptr, name, execute, complete, job.get(), &job->work) != napi_ok)
    return error(env, "Background worker allocation failed");
  if (napi_queue_async_work(env, job->work) != napi_ok) {
    napi_delete_async_work(env, job->work); return error(env, "Background worker queue failed");
  }
  run->started = true;
  job.release(); return promise;
}
inline napi_value cancel(napi_env env, napi_callback_info info) {
  auto run = lookup(env, argument(env, info));
  if (!run) return error(env, "Background handle missing");
  run->cancelRequested = true;
  pod_background_cancel(run->handle);
  napi_value value{}; napi_get_undefined(env, &value); return value;
}
inline napi_value configure(napi_env env, napi_callback_info info) {
  size_t argc = 2, length = 0; napi_value args[2]{}; double id = 0;
  if (napi_get_cb_info(env, info, &argc, args, nullptr, nullptr) != napi_ok || argc != 2 ||
      napi_get_value_double(env, args[0], &id) != napi_ok || !std::isfinite(id) || id < 1 || id > UINT32_MAX || std::floor(id) != id)
    return error(env, "Invalid background handle");
  auto run = lookup(env, static_cast<uint32_t>(id));
  if (!run || run->schedulerOnly || !run->ready || !run->lease || run->closed || run->started || run->cancelRequested)
    return error(env, "Background handle cannot be configured");
  if (napi_get_value_string_utf8(env, args[1], nullptr, 0, &length) != napi_ok || !length || length > 2 * 1024 * 1024)
    return error(env, "Invalid background config");
  std::vector<char> bytes(length + 1);
  if (napi_get_value_string_utf8(env, args[1], bytes.data(), bytes.size(), &length) != napi_ok)
    return error(env, "Invalid background config");
  auto* replacement = pod_background_open(reinterpret_cast<const uint8_t*>(bytes.data()), length);
  if (!replacement) return error(env, "Invalid background config");
  pod_background_close(run->handle); run->handle = replacement;
  napi_value value{}; napi_get_undefined(env, &value); return value;
}
inline napi_value close(napi_env env, napi_callback_info info) {
  const auto id = argument(env, info);
  std::lock_guard lock(registryMutex);
  auto it = registry.find(id);
  if (it == registry.end() || it->second->owner != env) return error(env, "Background handle missing");
  it->second->closed = true;
  pod_background_cancel(it->second->handle);
  // An in-flight Work retains ownership until execute has actually returned.
  registry.erase(it);
  napi_value value{}; napi_get_undefined(env, &value); return value;
}
inline bool install(napi_env env, napi_value exports) {
  if (napi_add_env_cleanup_hook(env, cleanup, env) != napi_ok) return false;
  napi_property_descriptor properties[] = {
    {"backgroundOpen", nullptr, open, nullptr, nullptr, nullptr, napi_default, nullptr},
    {"backgroundOpenLeased", nullptr, openLeased, nullptr, nullptr, nullptr, napi_default, nullptr},
    {"backgroundOpenSchedulerLease", nullptr, openSchedulerLease, nullptr, nullptr, nullptr, napi_default, nullptr},
    {"backgroundConfigure", nullptr, configure, nullptr, nullptr, nullptr, napi_default, nullptr},
    {"backgroundExecute", nullptr, start, nullptr, nullptr, nullptr, napi_default, nullptr},
    {"backgroundCancel", nullptr, cancel, nullptr, nullptr, nullptr, napi_default, nullptr},
    {"backgroundClose", nullptr, close, nullptr, nullptr, nullptr, napi_default, nullptr}
  };
  return napi_define_properties(env, exports, 7, properties) == napi_ok;
}
}
