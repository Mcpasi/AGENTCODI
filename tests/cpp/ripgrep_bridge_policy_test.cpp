#include "ripgrep_bridge_policy.h"

#include <cstdlib>
#include <iostream>
#include <string>
#include <vector>

namespace {

int assertions = 0;

void expect(bool condition, const char* message) {
  ++assertions;
  if (!condition) {
    std::cerr << "Assertion failed: " << message << '\n';
    std::exit(1);
  }
}

bool allowed(std::initializer_list<const char*> values) {
  std::vector<std::string> arguments;
  for (const char* value : values) {
    arguments.emplace_back(value);
  }
  std::string error;
  return agentcodi::ValidateRipgrepArguments(arguments, &error);
}

}  // namespace

int main() {
  expect(!allowed({"--pre", "cat", "needle", "."}), "blocks --pre value");
  expect(!allowed({"--pre=cat", "needle", "."}), "blocks --pre=value");
  expect(!allowed({"--search-zip", "needle", "."}), "blocks --search-zip");
  expect(!allowed({"--search-zip=true", "needle", "."}), "blocks search-zip equals");
  expect(!allowed({"--follow", "needle", "."}), "blocks --follow");
  expect(!allowed({"--follow=yes", "needle", "."}), "blocks follow equals");
  expect(!allowed({"-z", "needle", "."}), "blocks -z");
  expect(!allowed({"-L", "needle", "."}), "blocks -L");
  expect(!allowed({"-inL", "needle", "."}), "blocks clustered -L");
  expect(!allowed({"-uz", "needle", "."}), "blocks clustered -z");

  expect(allowed({"needle", "."}), "allows ordinary search");
  expect(allowed({"--no-follow", "needle", "."}), "allows disabling follow");
  expect(allowed({"--no-search-zip", "needle", "."}), "allows disabling zip search");
  expect(allowed({"--no-pre", "needle", "."}), "allows disabling preprocessor");
  expect(allowed({"--", "--follow", "-z"}), "delimiter ends option parsing");
  expect(allowed({"-eLazy", "."}), "attached regexp may contain L");
  expect(allowed({"-gfooz", "."}), "attached glob may contain z");
  expect(allowed({"-e", "-L", "."}), "separate regexp may equal -L");
  expect(allowed({"--regexp", "--follow", "."}), "long regexp value may look like flag");
  expect(allowed({"--glob", "--search-zip", "."}), "long glob value may look like flag");

  expect(setenv("RIPGREP_CONFIG_PATH", "/private/config", 1) == 0,
         "sets config test environment");
  std::string error;
  expect(agentcodi::PrepareRipgrepEnvironment(&error), "clears config environment");
  expect(std::getenv("RIPGREP_CONFIG_PATH") == nullptr, "config environment absent");
  expect(!agentcodi::ValidateRipgrepArguments({}, nullptr), "rejects null error sink");
  expect(!agentcodi::PrepareRipgrepEnvironment(nullptr), "rejects null environment error sink");

  std::cout << "ripgrep bridge policy tests passed: " << assertions
            << " assertions\n";
  return 0;
}
