#ifndef AGENTCODI_TOOLCHAIN_POLICY_H
#define AGENTCODI_TOOLCHAIN_POLICY_H

#include <string>
#include <vector>

namespace agentcodi {

struct PackageSpec {
  const char* name;
  const char* display_name;
  const char* version;
  const char* marker;
};

extern const PackageSpec kNodePackage;
extern const PackageSpec kNpmPackage;
extern const PackageSpec kPythonPackage;
extern const PackageSpec kRipgrepPackage;

bool ContainsPath(const std::string& parent, const std::string& child);

bool CanonicalPrivateToolDirectory(
    const char* supplied,
    std::string* canonical,
    std::string* error);

bool ResolveToolchainDirectories(
    std::string* workspace,
    std::string* toolchain,
    std::string* error);

int OpenToolActivationDirectory(bool create, std::string* error);

bool ValidateToolActivationMarker(
    int directory,
    const PackageSpec& package,
    std::string* error);

bool IsToolPackageEnabled(
    const PackageSpec& package,
    std::string* error);

enum class GuardedTool {
  kNode,
  kPython,
  kRipgrep,
};

// This is the single security-policy entry point used both by the public
// shell bridge and by constructors injected into the real packaged ELFs.
// A check added for a guarded executable therefore applies to every exec
// route, including an absolute invocation of the underlying library name.
bool PrepareGuardedToolInvocation(
    GuardedTool tool,
    const std::vector<std::string>& arguments,
    std::string* error,
    int* exit_code);

}  // namespace agentcodi

#endif
