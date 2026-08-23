#ifndef AGENTCODI_TOOLCHAIN_ELF_ATTESTOR_INJECTOR_H
#define AGENTCODI_TOOLCHAIN_ELF_ATTESTOR_INJECTOR_H

#include <string>

namespace agentcodi {

// Adds one bounded read/execute PT_LOAD segment to an Android ARM64 PIE and
// redirects its entry point through the supplied relocation-free attestor.
// The original Android note bytes remain covered by their existing PT_LOAD;
// only that redundant PT_NOTE program-header slot is reused for the new load.
bool InjectToolchainElfAttestor(
    const std::string& input_path,
    const std::string& payload_path,
    const std::string& output_path,
    std::string* error);

}  // namespace agentcodi

#endif
