#ifndef AGENTCODI_ENGINE_H
#define AGENTCODI_ENGINE_H

#include <string>

namespace agentcodi {

const char* engine_version();
int run_self_test();
std::string runtime_diagnostics();

}  // namespace agentcodi

#endif

