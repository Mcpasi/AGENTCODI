#ifndef AGENTCODI_SHA256_H
#define AGENTCODI_SHA256_H

#include <cstddef>
#include <string>

namespace agentcodi {

std::string Sha256Hex(const unsigned char* bytes, std::size_t size);

}  // namespace agentcodi

#endif
