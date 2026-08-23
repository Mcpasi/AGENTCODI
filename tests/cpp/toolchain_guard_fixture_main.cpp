#include <cstdlib>
#include <iostream>
#include <string>

extern "C" void AgentCodiToolGuardLinked();

int main(int argc, char* argv[]) {
  AgentCodiToolGuardLinked();
  if (argc == 2 && std::string(argv[1]) == "--verify-python-environment") {
    const char* home = std::getenv("PYTHONHOME");
    const char* no_user_site = std::getenv("PYTHONNOUSERSITE");
    const char* no_bytecode = std::getenv("PYTHONDONTWRITEBYTECODE");
    const char* safe_path = std::getenv("PYTHONSAFEPATH");
    const char* history = std::getenv("PYTHON_HISTORY");
    const char* utf8 = std::getenv("PYTHONUTF8");
    if (home == nullptr || std::string(home).find("/tool-runtime/python")
            == std::string::npos
        || no_user_site == nullptr || std::string(no_user_site) != "1"
        || no_bytecode == nullptr || std::string(no_bytecode) != "1"
        || safe_path == nullptr || std::string(safe_path) != "1"
        || history == nullptr || std::string(history) != "/dev/null"
        || utf8 == nullptr || std::string(utf8) != "1") {
      return 31;
    }
    std::cout << "python-environment-guarded\n";
    return 0;
  }
  if (argc == 2 && std::string(argv[1]) == "--verify-ripgrep-environment") {
    if (std::getenv("RIPGREP_CONFIG_PATH") != nullptr) {
      return 32;
    }
    std::cout << "ripgrep-environment-guarded\n";
    return 0;
  }
  std::cout << "guarded-tool-main-ran\n";
  return 0;
}
