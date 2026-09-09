#pragma once
#include "state_store_napi.h"
#include <mutex>
#include <unordered_set>

namespace pod_pairing_napi {
struct Lease {
  std::unique_ptr<pod_store::JournalStorage> storage;
  std::atomic<bool> closed{false}, busy{false};
};
struct Holder { std::shared_ptr<Lease> lease; };
inline std::mutex mutex;
inline std::unordered_set<Holder*> holders;
inline std::atomic<unsigned> jobs{0};
struct Work {
  napi_async_work work = nullptr;
  napi_deferred deferred = nullptr;
  std::shared_ptr<Lease> lease;
  bool opening = false, compare = false, exchanged = false;
  std::string root, app, desired, error;
  std::optional<std::string> expected, output;
  ~Work() { if (!opening && lease) lease->busy = false; --jobs; }
};
inline std::shared_ptr<Lease> get(napi_env env, napi_value value) {
  void* pointer = nullptr;
  pod_store::require(napi_get_value_external(env, value, &pointer) == napi_ok, "Invalid pairing lease");
  auto* holder = static_cast<Holder*>(pointer); std::lock_guard lock(mutex);
  pod_store::require(holders.count(holder) && holder->lease && !holder->lease->closed, "Pairing lease closed");
  return holder->lease;
}
inline void finalize(napi_env, void* pointer, void*) {
  auto* holder = static_cast<Holder*>(pointer);
  { std::lock_guard lock(mutex); holders.erase(holder); if (holder->lease) holder->lease->closed = true; }
  delete holder;
}
inline void execute(napi_env, void* pointer) {
  auto& job = *static_cast<Work*>(pointer);
  try {
    if (job.opening) {
      pod_store::companionStateNamespace(job.app);
      job.lease = std::make_shared<Lease>();
      job.lease->storage = std::make_unique<pod_store::JournalStorage>(job.root, "podjs-companion-pairings-" + job.app);
      return;
    }
    pod_store::require(!job.lease->closed, "Pairing lease closed");
    job.output = job.lease->storage->read();
    if (job.compare && job.output == job.expected) { job.lease->storage->write(job.desired); job.exchanged = true; }
    pod_store::require(!job.lease->closed, "Pairing lease closed");
  } catch (const std::exception& error) { job.error = error.what(); }
  catch (...) { job.error = "Pairing storage failure"; }
}
inline void reject(napi_env env, napi_deferred deferred, const std::string& text) {
  napi_value message{}, error{};
  napi_create_string_utf8(env, text.data(), text.size(), &message);
  napi_create_error(env, nullptr, message, &error); napi_reject_deferred(env, deferred, error);
}
inline void complete(napi_env env, napi_status status, void* pointer) {
  std::unique_ptr<Work> job(static_cast<Work*>(pointer)); napi_value value{};
  if (status != napi_ok && job->error.empty()) job->error = "Pairing IO cancelled";
  if (!job->opening && job->lease && job->lease->closed && job->error.empty()) job->error = "Pairing lease closed";
  if (!job->error.empty()) reject(env, job->deferred, job->error);
  else {
    if (job->opening) {
      auto holder = std::make_unique<Holder>(); holder->lease = job->lease;
      if (napi_create_external(env, holder.get(), finalize, nullptr, &value) != napi_ok) {
        reject(env, job->deferred, "Cannot create pairing lease"); napi_delete_async_work(env, job->work); return;
      }
      { std::lock_guard lock(mutex); holders.insert(holder.get()); } holder.release();
    } else if (job->compare) napi_get_boolean(env, job->exchanged, &value);
    else if (job->output) napi_create_string_utf8(env, job->output->data(), job->output->size(), &value);
    else napi_get_null(env, &value);
    napi_resolve_deferred(env, job->deferred, value);
  }
  napi_delete_async_work(env, job->work);
}
inline napi_value operation(napi_env env, napi_callback_info info, bool opening, bool compare) {
  try {
    if (jobs.fetch_add(1) >= 16) { --jobs; throw std::runtime_error("Pairing IO queue full"); }
    std::unique_ptr<Work> job;
    try { job = std::make_unique<Work>(); } catch (...) { --jobs; throw; }
    job->opening = opening; job->compare = compare;
    size_t argc = 4; napi_value args[4]{}; napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    pod_store::require(argc == (opening ? 2u : compare ? 3u : 1u), "Invalid pairing IO arguments");
    if (opening) {
      pod_store::require(pod_background_store::string(env, args[0], 4096, job->root) &&
        pod_background_store::string(env, args[1], 128, job->app), "Invalid pairing identity");
    } else {
      auto lease = get(env, args[0]); bool expected = false;
      pod_store::require(lease->busy.compare_exchange_strong(expected, true), "Pairing lease busy"); job->lease = lease;
      if (compare) {
        napi_valuetype type{}; pod_store::require(napi_typeof(env, args[1], &type) == napi_ok, "Invalid pairing expected value");
        if (type != napi_null) {
          std::string text; pod_store::require(pod_background_store::string(env, args[1], 65536, text), "Invalid pairing expected value"); job->expected = text;
        }
        pod_store::require(pod_background_store::string(env, args[2], 65536, job->desired), "Invalid pairing desired value");
      }
    }
    napi_value promise{}, name{};
    pod_store::require(napi_create_promise(env, &job->deferred, &promise) == napi_ok, "Cannot create pairing promise");
    napi_create_string_utf8(env, "PodJS pairing IO", NAPI_AUTO_LENGTH, &name);
    if (napi_create_async_work(env, nullptr, name, execute, complete, job.get(), &job->work) != napi_ok ||
        napi_queue_async_work(env, job->work) != napi_ok) {
      if (job->work) napi_delete_async_work(env, job->work);
      reject(env, job->deferred, "Cannot queue pairing IO"); return promise;
    }
    job.release(); return promise;
  } catch (const std::exception& error) { napi_throw_error(env, nullptr, error.what()); return nullptr; }
}
inline napi_value ownership(napi_env env, napi_callback_info info, bool close) {
  try {
    size_t argc = 2; napi_value args[2]{}; napi_get_cb_info(env, info, &argc, args, nullptr, nullptr);
    pod_store::require(argc == 1, "Invalid pairing lease arguments");
    if (!close) get(env, args[0]);
    else {
      void* pointer = nullptr; pod_store::require(napi_get_value_external(env, args[0], &pointer) == napi_ok, "Invalid pairing lease");
      auto* holder = static_cast<Holder*>(pointer); std::lock_guard lock(mutex);
      pod_store::require(holders.count(holder), "Invalid pairing lease");
      if (holder->lease) { holder->lease->closed = true; holder->lease.reset(); }
    }
    napi_value result{}; napi_get_undefined(env, &result); return result;
  } catch (const std::exception& error) { napi_throw_error(env, nullptr, error.what()); return nullptr; }
}
inline bool install(napi_env env, napi_value exports) {
  napi_property_descriptor properties[] = {
    {"companionPairingLeaseOpen", nullptr, [](napi_env e, napi_callback_info i) { return operation(e, i, true, false); }, nullptr, nullptr, nullptr, napi_default, nullptr},
    {"companionPairingLeaseRead", nullptr, [](napi_env e, napi_callback_info i) { return operation(e, i, false, false); }, nullptr, nullptr, nullptr, napi_default, nullptr},
    {"companionPairingLeaseCompareExchange", nullptr, [](napi_env e, napi_callback_info i) { return operation(e, i, false, true); }, nullptr, nullptr, nullptr, napi_default, nullptr},
    {"companionPairingLeaseAssert", nullptr, [](napi_env e, napi_callback_info i) { return ownership(e, i, false); }, nullptr, nullptr, nullptr, napi_default, nullptr},
    {"companionPairingLeaseClose", nullptr, [](napi_env e, napi_callback_info i) { return ownership(e, i, true); }, nullptr, nullptr, nullptr, napi_default, nullptr}
  };
  return napi_define_properties(env, exports, sizeof(properties) / sizeof(properties[0]), properties) == napi_ok;
}
}
