#include "agentcodi_engine.h"

#include <cstdint>
#include <string>

namespace agentcodi {
namespace {

constexpr const char* kEngineVersion = "agentcodi-native/0.6.10";

}  // namespace

const char* engine_version() {
  return kEngineVersion;
}

int run_self_test() {
  if (sizeof(void*) != 8U) {
    return 10;
  }
  if (sizeof(std::uint64_t) != 8U) {
    return 11;
  }
  const std::string probe = std::string("agent") + "codi";
  if (probe != "agentcodi") {
    return 12;
  }
  return 0;
}

std::string runtime_diagnostics() {
  return "abi=arm64-v8a;pointerBits=64;language=cpp;jni=ready;"
      "appServerSupervisor=ready;codeModeHost=android-sibling;responsesTransport=https";
}

}  // namespace agentcodi
