#ifndef AGENTCODI_PNG_VALIDATOR_H
#define AGENTCODI_PNG_VALIDATOR_H

#include <string>
#include <vector>

namespace agentcodi {

// Validates the complete bounded PNG container and its inflated scanline shape.
bool ValidatePngImage(
    const std::vector<unsigned char>& bytes,
    std::string* error);

}  // namespace agentcodi

#endif  // AGENTCODI_PNG_VALIDATOR_H
