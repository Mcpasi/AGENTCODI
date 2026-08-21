#include "agentcodi_engine.h"
#include "app_server_process.h"
#include "workspace_file_reader.h"
#include "workspace_import_installer.h"

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

std::mutex workspace_file_registry_mutex;
std::unordered_map<jlong, std::shared_ptr<agentcodi::WorkspaceFileReader>>
    workspace_file_registry;
std::atomic<jlong> next_workspace_file_handle{1};

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

std::shared_ptr<agentcodi::WorkspaceFileReader> find_workspace_file(jlong handle) {
  std::lock_guard<std::mutex> guard(workspace_file_registry_mutex);
  const auto found = workspace_file_registry.find(handle);
  return found == workspace_file_registry.end() ? nullptr : found->second;
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
    jstring code_mode_host_executable,
    jstring shell_executable,
    jstring node_executable,
    jstring python_executable,
    jstring workspace,
    jstring toolchain,
    jstring tool_binary_directory,
    jstring tool_runtime_directory,
    jstring codex_home,
    jstring home,
    jstring state_directory,
    jstring temporary_directory,
    jstring native_library_directory) {
  agentcodi::ProcessConfig config;
  if (!from_java_string(environment, executable, "Executable", &config.executable)
      || !from_java_string(
          environment,
          code_mode_host_executable,
          "Code-mode host executable",
          &config.code_mode_host_executable)
      || !from_java_string(
          environment,
          shell_executable,
          "Terminal shell executable",
          &config.shell_executable)
      || !from_java_string(
          environment,
          node_executable,
          "Node executable",
          &config.node_executable)
      || !from_java_string(
          environment,
          python_executable,
          "Python executable",
          &config.python_executable)
      || !from_java_string(environment, workspace, "Workspace", &config.working_directory)
      || !from_java_string(
          environment,
          toolchain,
          "Toolchain",
          &config.toolchain_directory)
      || !from_java_string(
          environment,
          tool_binary_directory,
          "Packaged tool directory",
          &config.tool_binary_directory)
      || !from_java_string(
          environment,
          tool_runtime_directory,
          "Packaged tool runtime",
          &config.tool_runtime_directory)
      || !from_java_string(environment, codex_home, "Codex home", &config.codex_home)
      || !from_java_string(environment, home, "Home", &config.home_directory)
      || !from_java_string(
          environment,
          state_directory,
          "State",
          &config.state_directory)
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
    jint line_length,
    jint maximum_bytes) {
  if (line == nullptr || maximum_bytes <= 0 || maximum_bytes > 256 * 1024) {
    throw_io_exception(environment, "Outgoing app-server byte limit is invalid");
    return;
  }
  const jsize array_length = environment->GetArrayLength(line);
  if (line_length <= 0 || line_length > array_length
      || line_length > maximum_bytes) {
    throw_io_exception(environment, "Outgoing app-server line exceeds the byte limit");
    return;
  }
  std::shared_ptr<agentcodi::AppServerProcess> process = find_process(handle);
  if (process == nullptr) {
    throw_io_exception(environment, "App-server process is not running");
    return;
  }
  std::vector<unsigned char> bytes(static_cast<std::size_t>(line_length));
  environment->GetByteArrayRegion(
      line,
      0,
      line_length,
      reinterpret_cast<jbyte*>(bytes.data()));
  if (environment->ExceptionCheck()) {
    volatile unsigned char* cursor = bytes.data();
    for (std::size_t index = 0U; index < bytes.size(); ++index) {
      cursor[index] = 0U;
    }
    return;
  }
  std::string error;
  if (!process->WriteBytes(
          &bytes,
          static_cast<std::size_t>(line_length),
          static_cast<std::size_t>(maximum_bytes),
          &error)) {
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

extern "C" JNIEXPORT jlong JNICALL
Java_de_agentcodi_runtime_NativeEngine_nativeOpenWorkspaceFile(
    JNIEnv* environment,
    jclass,
    jstring workspace,
    jstring relative_path,
    jlong maximum_bytes) {
  if (maximum_bytes < 0) {
    throw_io_exception(environment, "Workspace file byte limit is invalid");
    return 0;
  }
  std::string workspace_value;
  std::string relative_path_value;
  if (!from_java_string(
          environment,
          workspace,
          "Workspace root",
          &workspace_value)
      || !from_java_string(
          environment,
          relative_path,
          "Workspace relative path",
          &relative_path_value)) {
    return 0;
  }
  std::string error;
  std::unique_ptr<agentcodi::WorkspaceFileReader> opened =
      agentcodi::WorkspaceFileReader::Open(
          workspace_value,
          relative_path_value,
          static_cast<std::int64_t>(maximum_bytes),
          &error);
  if (opened == nullptr) {
    throw_io_exception(
        environment,
        error.empty() ? "Workspace file could not be opened safely" : error);
    return 0;
  }
  const jlong handle = next_workspace_file_handle.fetch_add(1);
  if (handle <= 0) {
    throw_io_exception(environment, "Workspace file handle space is exhausted");
    return 0;
  }
  {
    std::lock_guard<std::mutex> guard(workspace_file_registry_mutex);
    workspace_file_registry.emplace(
        handle,
        std::shared_ptr<agentcodi::WorkspaceFileReader>(std::move(opened)));
  }
  return handle;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_de_agentcodi_runtime_NativeEngine_nativeWorkspaceFileMetadata(
    JNIEnv* environment,
    jclass,
    jlong handle) {
  std::shared_ptr<agentcodi::WorkspaceFileReader> source =
      find_workspace_file(handle);
  if (source == nullptr) {
    throw_io_exception(environment, "Workspace file handle is not open");
    return nullptr;
  }
  const agentcodi::WorkspaceFileMetadata& metadata = source->metadata();
  const jlong values[] = {
      static_cast<jlong>(metadata.size),
      static_cast<jlong>(metadata.modified_seconds),
      static_cast<jlong>(metadata.modified_nanoseconds),
      static_cast<jlong>(metadata.changed_seconds),
      static_cast<jlong>(metadata.changed_nanoseconds),
      static_cast<jlong>(metadata.device),
      static_cast<jlong>(metadata.inode),
  };
  jlongArray result = environment->NewLongArray(
      static_cast<jsize>(sizeof(values) / sizeof(values[0])));
  if (result == nullptr) {
    return nullptr;
  }
  environment->SetLongArrayRegion(
      result,
      0,
      static_cast<jsize>(sizeof(values) / sizeof(values[0])),
      values);
  return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_de_agentcodi_runtime_NativeEngine_nativeReadWorkspaceFile(
    JNIEnv* environment,
    jclass,
    jlong handle,
    jbyteArray destination,
    jint offset,
    jint length) {
  if (destination == nullptr || length <= 0 || length > 64 * 1024) {
    throw_io_exception(environment, "Workspace file read request is invalid");
    return -1;
  }
  const jsize destination_length = environment->GetArrayLength(destination);
  if (offset < 0 || offset > destination_length - length) {
    throw_io_exception(environment, "Workspace file read bounds are invalid");
    return -1;
  }
  std::shared_ptr<agentcodi::WorkspaceFileReader> source =
      find_workspace_file(handle);
  if (source == nullptr) {
    throw_io_exception(environment, "Workspace file handle is not open");
    return -1;
  }
  std::vector<unsigned char> buffer(static_cast<std::size_t>(length));
  std::string error;
  const ssize_t count = source->Read(buffer.data(), buffer.size(), &error);
  if (count < 0) {
    volatile unsigned char* cursor = buffer.data();
    for (std::size_t index = 0; index < buffer.size(); ++index) {
      cursor[index] = 0U;
    }
    throw_io_exception(
        environment,
        error.empty() ? "Workspace file read failed" : error);
    return -1;
  }
  if (count > 0) {
    environment->SetByteArrayRegion(
        destination,
        offset,
        static_cast<jsize>(count),
        reinterpret_cast<const jbyte*>(buffer.data()));
  }
  volatile unsigned char* cursor = buffer.data();
  for (std::size_t index = 0; index < buffer.size(); ++index) {
    cursor[index] = 0U;
  }
  if (environment->ExceptionCheck()) {
    return -1;
  }
  return count == 0 ? -1 : static_cast<jint>(count);
}

extern "C" JNIEXPORT void JNICALL
Java_de_agentcodi_runtime_NativeEngine_nativeVerifyWorkspaceFile(
    JNIEnv* environment,
    jclass,
    jlong handle) {
  std::shared_ptr<agentcodi::WorkspaceFileReader> source =
      find_workspace_file(handle);
  if (source == nullptr) {
    throw_io_exception(environment, "Workspace file handle is not open");
    return;
  }
  std::string error;
  if (!source->VerifyUnchanged(&error)) {
    throw_io_exception(
        environment,
        error.empty() ? "Workspace file changed during export" : error);
  }
}

extern "C" JNIEXPORT void JNICALL
Java_de_agentcodi_runtime_NativeEngine_nativeCloseWorkspaceFile(
    JNIEnv*,
    jclass,
    jlong handle) {
  std::lock_guard<std::mutex> guard(workspace_file_registry_mutex);
  workspace_file_registry.erase(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_de_agentcodi_runtime_NativeEngine_nativeInstallWorkspaceImportNoReplace(
    JNIEnv* environment,
    jclass,
    jstring workspace,
    jstring pending_name,
    jstring final_name,
    jlong expected_byte_count) {
  std::string workspace_value;
  std::string pending_name_value;
  std::string final_name_value;
  if (!from_java_string(
          environment,
          workspace,
          "Workspace root",
          &workspace_value)
      || !from_java_string(
          environment,
          pending_name,
          "Pending import name",
          &pending_name_value)
      || !from_java_string(
          environment,
          final_name,
          "Final import name",
          &final_name_value)) {
    return;
  }
  std::string error;
  if (!agentcodi::InstallWorkspaceImportNoReplace(
          workspace_value,
          pending_name_value,
          final_name_value,
          static_cast<std::int64_t>(expected_byte_count),
          &error)) {
    throw_io_exception(
        environment,
        error.empty()
            ? "Workspace import could not be installed safely"
            : error);
  }
}
