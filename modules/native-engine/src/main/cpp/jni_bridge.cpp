#include "agentcodi_engine.h"
#include "app_server_process.h"

#include <jni.h>

#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace {

jstring to_java_string(JNIEnv* environment, const std::string& value) {
  return environment->NewStringUTF(value.c_str());
}

std::mutex process_registry_mutex;
std::unordered_map<jlong, std::shared_ptr<agentcodi::AppServerProcess>> process_registry;
std::atomic<jlong> next_process_handle{1};

void throw_io_exception(JNIEnv* environment, const std::string& message) {
  if (environment->ExceptionCheck()) {
    return;
  }
  jclass exception_class = environment->FindClass("java/io/IOException");
  if (exception_class != nullptr) {
    environment->ThrowNew(exception_class, message.c_str());
  }
}

bool from_java_string(
    JNIEnv* environment,
    jstring value,
    const char* label,
    std::string* output) {
  if (value == nullptr) {
    throw_io_exception(environment, std::string(label) + " is missing");
    return false;
  }
  const char* utf = environment->GetStringUTFChars(value, nullptr);
  if (utf == nullptr) {
    return false;
  }
  *output = utf;
  environment->ReleaseStringUTFChars(value, utf);
  if (output->empty() || output->find('\0') != std::string::npos) {
    throw_io_exception(environment, std::string(label) + " is invalid");
    return false;
  }
  return true;
}

std::shared_ptr<agentcodi::AppServerProcess> find_process(jlong handle) {
  std::lock_guard<std::mutex> guard(process_registry_mutex);
  const auto found = process_registry.find(handle);
  return found == process_registry.end() ? nullptr : found->second;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_de_agentcodi_runtime_NativeEngine_nativeVersion(JNIEnv* environment, jclass) {
  return to_java_string(environment, agentcodi::engine_version());
}

extern "C" JNIEXPORT jint JNICALL
Java_de_agentcodi_runtime_NativeEngine_nativeSelfTest(JNIEnv*, jclass) {
  return static_cast<jint>(agentcodi::run_self_test());
}

extern "C" JNIEXPORT jstring JNICALL
Java_de_agentcodi_runtime_NativeEngine_nativeDiagnostics(JNIEnv* environment, jclass) {
  return to_java_string(environment, agentcodi::runtime_diagnostics());
}

extern "C" JNIEXPORT jlong JNICALL
Java_de_agentcodi_runtime_NativeEngine_nativeStartAppServer(
    JNIEnv* environment,
    jclass,
    jstring executable,
    jstring workspace,
    jstring codex_home,
    jstring home,
    jstring temporary_directory,
    jstring native_library_directory) {
  agentcodi::ProcessConfig config;
  if (!from_java_string(environment, executable, "Executable", &config.executable)
      || !from_java_string(environment, workspace, "Workspace", &config.working_directory)
      || !from_java_string(environment, codex_home, "Codex home", &config.codex_home)
      || !from_java_string(environment, home, "Home", &config.home_directory)
      || !from_java_string(
          environment,
          temporary_directory,
          "Temporary directory",
          &config.temporary_directory)
      || !from_java_string(
          environment,
          native_library_directory,
          "Native library directory",
          &config.library_directory)) {
    return 0;
  }
  config.arguments = agentcodi::CodexAppServerArguments();

  std::string error;
  std::shared_ptr<agentcodi::AppServerProcess> process =
      agentcodi::AppServerProcess::Start(config, &error);
  if (process == nullptr) {
    throw_io_exception(environment, error);
    return 0;
  }
  const jlong handle = next_process_handle.fetch_add(1);
  {
    std::lock_guard<std::mutex> guard(process_registry_mutex);
    process_registry.emplace(handle, std::move(process));
  }
  return handle;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_de_agentcodi_runtime_NativeEngine_nativeReadAppServerLine(
    JNIEnv* environment,
    jclass,
    jlong handle,
    jint maximum_bytes) {
  if (maximum_bytes <= 0 || maximum_bytes > 1024 * 1024) {
    throw_io_exception(environment, "Incoming app-server byte limit is invalid");
    return nullptr;
  }
  std::shared_ptr<agentcodi::AppServerProcess> process = find_process(handle);
  if (process == nullptr) {
    return nullptr;
  }
  std::string line;
  std::string error;
  const agentcodi::LineReadStatus status = process->ReadLine(
      static_cast<std::size_t>(maximum_bytes),
      &line,
      &error);
  if (status == agentcodi::LineReadStatus::kEndOfStream) {
    return nullptr;
  }
  if (status != agentcodi::LineReadStatus::kLine) {
    throw_io_exception(environment, error.empty() ? "App-server read failed" : error);
    return nullptr;
  }
  jbyteArray result = environment->NewByteArray(static_cast<jsize>(line.size()));
  if (result == nullptr) {
    return nullptr;
  }
  if (!line.empty()) {
    environment->SetByteArrayRegion(
        result,
        0,
        static_cast<jsize>(line.size()),
        reinterpret_cast<const jbyte*>(line.data()));
  }
  return result;
}

extern "C" JNIEXPORT void JNICALL
Java_de_agentcodi_runtime_NativeEngine_nativeWriteAppServerLine(
    JNIEnv* environment,
    jclass,
    jlong handle,
    jbyteArray line,
    jint maximum_bytes) {
  if (line == nullptr || maximum_bytes <= 0 || maximum_bytes > 256 * 1024) {
    throw_io_exception(environment, "Outgoing app-server byte limit is invalid");
    return;
  }
  const jsize length = environment->GetArrayLength(line);
  if (length <= 0 || length > maximum_bytes) {
    throw_io_exception(environment, "Outgoing app-server line exceeds the byte limit");
    return;
  }
  std::vector<jbyte> bytes(static_cast<std::size_t>(length));
  environment->GetByteArrayRegion(line, 0, length, bytes.data());
  if (environment->ExceptionCheck()) {
    return;
  }
  std::shared_ptr<agentcodi::AppServerProcess> process = find_process(handle);
  if (process == nullptr) {
    throw_io_exception(environment, "App-server process is not running");
    return;
  }
  const std::string value(
      reinterpret_cast<const char*>(bytes.data()),
      static_cast<std::size_t>(length));
  std::string error;
  if (!process->WriteLine(value, static_cast<std::size_t>(maximum_bytes), &error)) {
    throw_io_exception(environment, error);
  }
}

extern "C" JNIEXPORT jint JNICALL
Java_de_agentcodi_runtime_NativeEngine_nativeStopAppServer(
    JNIEnv*,
    jclass,
    jlong handle,
    jint timeout_milliseconds) {
  std::shared_ptr<agentcodi::AppServerProcess> process;
  {
    std::lock_guard<std::mutex> guard(process_registry_mutex);
    const auto found = process_registry.find(handle);
    if (found == process_registry.end()) {
      return -1;
    }
    process = found->second;
    process_registry.erase(found);
  }
  return static_cast<jint>(process->Stop(timeout_milliseconds));
}
