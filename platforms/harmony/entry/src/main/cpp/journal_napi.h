#pragma once
#include <napi/native_api.h>
#include <memory>
#include <vector>
#include "journal_storage.h"

namespace pod_store {
inline std::mutex bindingMutex;
inline std::shared_ptr<JournalStorage> binding;
enum class Operation { Open, Read, Write };
struct Work {
  napi_async_work work = nullptr;
  napi_deferred deferred = nullptr;
  Operation operation;
  std::string input, error;
  std::optional<std::string> output;
};
inline void execute(napi_env, void* data) {
  auto& job = *static_cast<Work*>(data);
  try {
    if (job.operation == Operation::Open) {
      std::lock_guard lock(bindingMutex);
      require(!binding, "Journal already opened; use one app-owned coordinator");
      binding = std::make_shared<JournalStorage>(job.input);
    } else {
      std::shared_ptr<JournalStorage> store;
      { std::lock_guard lock(bindingMutex); store = binding; }
      require(static_cast<bool>(store), "Journal is not open");
      if (job.operation == Operation::Read) job.output = store->read();
      else store->write(job.input);
    }
  } catch (const std::exception& error) { job.error = error.what(); }
  catch (...) { job.error = "Journal IO failed"; }
}
inline void complete(napi_env env, napi_status status, void* data) {
  std::unique_ptr<Work> job(static_cast<Work*>(data));
  napi_value value{};
  if (status != napi_ok && job->error.empty()) job->error = "Journal IO cancelled";
  if (!job->error.empty()) {
    napi_value message{};
    napi_create_string_utf8(env, job->error.data(), job->error.size(), &message);
    napi_create_error(env, nullptr, message, &value);
    napi_reject_deferred(env, job->deferred, value);
  } else {
    if (job->operation == Operation::Read) {
      if (job->output) napi_create_string_utf8(env, job->output->data(), job->output->size(), &value);
      else napi_get_null(env, &value);
    } else napi_get_undefined(env, &value);
    napi_resolve_deferred(env, job->deferred, value);
  }
  napi_delete_async_work(env, job->work);
}
inline napi_value enqueue(napi_env env, napi_callback_info info, Operation operation) {
  auto job = std::make_unique<Work>(); job->operation = operation;
  if (operation != Operation::Read) {
    size_t count = 1, length = 0; napi_value args[1]{};
    napi_get_cb_info(env, info, &count, args, nullptr, nullptr);
    const size_t limit = operation == Operation::Open ? 4096 : JournalStorage::maxBytes;
    if (count != 1 || napi_get_value_string_utf8(env, args[0], nullptr, 0, &length) != napi_ok || length == 0 || length > limit) {
      napi_throw_type_error(env, nullptr, "Journal requires a bounded nonempty string"); return nullptr;
    }
    std::vector<char> buffer(length + 1);
    if (napi_get_value_string_utf8(env, args[0], buffer.data(), buffer.size(), &length) != napi_ok) return nullptr;
    job->input.assign(buffer.data(), length);
  }
  napi_value promise{}, name{};
  if (napi_create_promise(env, &job->deferred, &promise) != napi_ok) return nullptr;
  napi_create_string_utf8(env, "PodJS journal IO", NAPI_AUTO_LENGTH, &name);
  const auto created = napi_create_async_work(env, nullptr, name, execute, complete, job.get(), &job->work);
  if (created != napi_ok || napi_queue_async_work(env, job->work) != napi_ok) {
    napi_value message{}, error{};
    napi_create_string_utf8(env, "Cannot queue journal IO", NAPI_AUTO_LENGTH, &message);
    napi_create_error(env, nullptr, message, &error); napi_reject_deferred(env, job->deferred, error);
    if (job->work) napi_delete_async_work(env, job->work);
    return promise;
  }
  job.release(); return promise;
}
inline napi_value open(napi_env env, napi_callback_info info) { return enqueue(env, info, Operation::Open); }
inline napi_value read(napi_env env, napi_callback_info info) { return enqueue(env, info, Operation::Read); }
inline napi_value write(napi_env env, napi_callback_info info) { return enqueue(env, info, Operation::Write); }
}
