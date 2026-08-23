#include <iostream>
#include <string>

#include "toolchain_elf_attestor_injector.h"

int main(int argc, char* argv[]) {
  if (argc != 4) {
    std::cerr << "Usage: toolchain-elf-attestor-injector INPUT PAYLOAD OUTPUT\n";
    return 2;
  }
  std::string error;
  if (!agentcodi::InjectToolchainElfAttestor(
          argv[1],
          argv[2],
          argv[3],
          &error)) {
    std::cerr << (error.empty() ? "ELF attestor injection failed" : error)
              << '\n';
    return 1;
  }
  return 0;
}
