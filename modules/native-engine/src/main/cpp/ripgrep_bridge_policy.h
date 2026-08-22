#ifndef AGENTCODI_RIPGREP_BRIDGE_POLICY_H
#define AGENTCODI_RIPGREP_BRIDGE_POLICY_H

#include <string>
#include <vector>

namespace agentcodi {

bool ValidateRipgrepArguments(
    const std::vector<std::string>& arguments,
    std::string* error);

bool PrepareRipgrepEnvironment(std::string* error);

}  // namespace agentcodi

#endif
