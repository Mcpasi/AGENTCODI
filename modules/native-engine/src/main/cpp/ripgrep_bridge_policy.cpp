#include "ripgrep_bridge_policy.h"

#include <cerrno>
#include <cstdlib>
#include <cstring>

namespace agentcodi {
namespace {

constexpr const char* kDisabledOptionError =
    "ripgrep options --pre, --search-zip and --follow are disabled by AGENTCODI";

bool is_disabled_long_option(const std::string& argument) {
  const std::size_t equals = argument.find('=');
  const std::string name = argument.substr(0U, equals);
  return name == "--pre"
      || name == "--search-zip"
      || name == "--follow";
}

bool long_option_takes_separate_value(const std::string& argument) {
  constexpr const char* kValueOptions[] = {
      "--regexp", "--file", "--pre-glob", "--dfa-size-limit",
      "--encoding", "--engine", "--max-count", "--regex-size-limit",
      "--threads", "--glob", "--iglob", "--ignore-file", "--max-depth",
      "--max-filesize", "--type", "--type-not", "--type-add",
      "--type-clear", "--after-context", "--before-context", "--color",
      "--colors", "--context", "--context-separator",
      "--field-context-separator", "--field-match-separator",
      "--hostname-bin", "--hyperlink-format", "--max-columns",
      "--path-separator", "--replace", "--sort", "--sortr", "--generate",
  };
  for (const char* option : kValueOptions) {
    if (argument == option) {
      return true;
    }
  }
  return false;
}

bool short_option_takes_value(char option) {
  switch (option) {
    case 'e':
    case 'f':
    case 'E':
    case 'm':
    case 'j':
    case 'g':
    case 'd':
    case 't':
    case 'T':
    case 'A':
    case 'B':
    case 'C':
    case 'M':
    case 'r':
      return true;
    default:
      return false;
  }
}

}  // namespace

bool ValidateRipgrepArguments(
    const std::vector<std::string>& arguments,
    std::string* error) {
  if (error == nullptr) {
    return false;
  }
  error->clear();
  bool options_ended = false;
  bool next_is_value = false;
  for (const std::string& argument : arguments) {
    if (options_ended) {
      continue;
    }
    if (next_is_value) {
      next_is_value = false;
      continue;
    }
    if (argument == "--") {
      options_ended = true;
      continue;
    }
    if (argument.size() > 2U && argument.compare(0U, 2U, "--") == 0) {
      if (is_disabled_long_option(argument)) {
        *error = kDisabledOptionError;
        return false;
      }
      if (argument.find('=') == std::string::npos
          && long_option_takes_separate_value(argument)) {
        next_is_value = true;
      }
      continue;
    }
    if (argument.size() > 1U && argument[0] == '-') {
      for (std::size_t index = 1U; index < argument.size(); ++index) {
        const char option = argument[index];
        if (option == 'z' || option == 'L') {
          *error = kDisabledOptionError;
          return false;
        }
        if (short_option_takes_value(option)) {
          next_is_value = index + 1U == argument.size();
          break;
        }
      }
    }
  }
  return true;
}

bool PrepareRipgrepEnvironment(std::string* error) {
  if (error == nullptr) {
    return false;
  }
  error->clear();
  if (unsetenv("RIPGREP_CONFIG_PATH") == 0) {
    return true;
  }
  *error = std::string("Could not clear ripgrep configuration: ")
      + std::strerror(errno);
  return false;
}

}  // namespace agentcodi
