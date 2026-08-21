#ifndef AGENTCODI_WORKSPACE_IMPORT_INSTALLER_H
#define AGENTCODI_WORKSPACE_IMPORT_INSTALLER_H

#include <cstdint>
#include <string>

namespace agentcodi {

// Atomically moves a completed pending import to its final random name. The
// target is never replaced; a collision leaves both existing entries intact.
bool InstallWorkspaceImportNoReplace(
    const std::string& workspace,
    const std::string& pending_name,
    const std::string& final_name,
    std::int64_t expected_byte_count,
    std::string* error);

}  // namespace agentcodi

#endif
