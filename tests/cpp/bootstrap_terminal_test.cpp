#include "bootstrap_terminal.h"

#include <cstdlib>
#include <iostream>
#include <string>

namespace {

using agentcodi_test::BootstrapTerminal;
using Event = agentcodi_test::BootstrapTerminalEvent;
int assertions = 0;

void expect(bool value, const char* message) {
  ++assertions;
  if (!value) {
    std::cerr << "FAILED: " << message << '\n';
    std::exit(1);
  }
}

std::string encode(const std::string& bytes) {
  const std::string alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  std::string result;
  for (std::size_t i = 0; i < bytes.size(); i += 3U) {
    const unsigned first = static_cast<unsigned char>(bytes[i]);
    const unsigned second = i + 1U < bytes.size() ? static_cast<unsigned char>(bytes[i + 1U]) : 0U;
    const unsigned third = i + 2U < bytes.size() ? static_cast<unsigned char>(bytes[i + 2U]) : 0U;
    result += alphabet[first >> 2U];
    result += alphabet[((first & 3U) << 4U) | (second >> 4U)];
    result += i + 1U < bytes.size() ? alphabet[((second & 15U) << 2U) | (third >> 6U)] : '=';
    result += i + 2U < bytes.size() ? alphabet[third & 63U] : '=';
  }
  return result;
}

std::string output(const std::string& encoded, const std::string& process = "agentcodi-build-terminal") {
  return "{\"method\":\"command/exec/outputDelta\",\"params\":{\"processId\":\"" + process
      + "\",\"stream\":\"stdout\",\"deltaBase64\":\"" + encoded + "\",\"capReached\":false}}";
}

const std::string kResize = "{\"id\":7,\"result\":{}}";
const std::string kWrite = "{\"id\":8,\"result\":{}}";
const std::string kCompleted = "{\"id\":6,\"result\":{\"exitCode\":0,\"stdout\":\"\",\"stderr\":\"\"}}";
const std::string kOutput = "terminal-protocol-smoke\nEnabled packaged Node.js 24.18.0.\n"
    "Enabled packaged ripgrep 15.2.0.\nv24.18.0\nripgrep 15.2.0\n";

}  // namespace

int main() {
  {
    const std::string rejected = "{\"id\":25,\"error\":{\"code\":-32603,\"message\":"
        "\"failed to load AGENTS.md instructions: refusing an unverifiable syscall: "
        "Invalid argument (os error 22); synthetic-private-detail\"}}";
    const std::string reason = agentcodi_test::BootstrapRpcErrorReason(rejected);
    expect(reason.find("tagged-pointer") != std::string::npos,
        "thread-start failures identify the sandbox argument error before the generic AGENTS failure");
    expect(reason.find("synthetic-private-detail") == std::string::npos,
        "sandbox diagnostics do not copy runtime text");
    expect(agentcodi_test::BootstrapSandboxFailureReason(
        "{\"id\":28,\"result\":{\"exitCode\":9,\"stdout\":\"\",\"stderr\":"
        "\"filesystem syscall interception produced no events\"}}") != nullptr,
        "nonzero command results retain the sandbox setup classification");
    expect(agentcodi_test::BootstrapSandboxFailureReason("unclassified synthetic failure") == nullptr,
        "unknown runtime text is not classified as a known sandbox cause");
  }
  {
    const std::string timed_out = "{\"id\":28,\"result\":{\"exitCode\":124,"
        "\"stdout\":\"AGENTCODI-PAYLOAD-READY\",\"stderr\":\"synthetic-private-detail\"}}";
    std::string standard_output;
    expect(!agentcodi_test::ReadBootstrapCommandOutput(timed_out, &standard_output),
        "a command that emitted the success marker but timed out still fails");
    const std::string diagnostic = agentcodi_test::BootstrapCommandFailure(timed_out);
    expect(diagnostic == "Bootstrap RPC 28 command exited with code 124. "
        "The command exceeded its execution deadline.",
        "timeout reports the correlated RPC and actual cause without runtime text");
    for (const int exit_code : {41, 42, 43, 44, 126, 127, -1}) {
      const std::string failed = "{\"id\":26,\"result\":{\"exitCode\":"
          + std::to_string(exit_code) + ",\"stdout\":\"\",\"stderr\":\"\"}}";
      expect(!agentcodi_test::ReadBootstrapCommandOutput(failed, &standard_output),
          "workspace, isolation and process failures all remain fatal");
      expect(agentcodi_test::BootstrapCommandFailure(failed).find(
          "code " + std::to_string(exit_code) + ".") != std::string::npos,
          "bounded nonzero and unknown signed exit statuses remain visible");
    }
    expect(agentcodi_test::BootstrapCommandFailure(
        "{\"id\":26,\"result\":{\"exitCode\":44}}")
        .find("allowed access to the synthetic private sibling") != std::string::npos,
        "isolation violations are distinguished from timeouts");
  }
  {
    std::string standard_output;
    for (const std::string fields : {
        "\"nested\":{\"exitCode\":0}",
        "\"exitCode\":124,\"nested\":{\"exitCode\":0}",
        "\"exitCode\":124,\"exitCode\":0",
        "\"exitCode\":0.5", "\"exitCode\":0e3", "\"exitCode\":00",
        "\"exitCode\":\"0\"", "\"exitCode\":999999999999999999"}) {
      expect(!agentcodi_test::ReadBootstrapCommandOutput(
          "{\"id\":28,\"result\":{" + fields
              + ",\"stdout\":\"AGENTCODI-PAYLOAD-READY\",\"stderr\":\"\"}}", &standard_output),
          "nested, duplicated or invalid exit statuses cannot satisfy the command contract");
    }
    expect(agentcodi_test::ReadBootstrapCommandOutput(
        "{\"result\":{\"stdout\":\"ok\",\"exitCode\":0,\"stderr\":\"\"},\"id\":28}",
        &standard_output) && standard_output == "ok",
        "command completion tolerates response field reordering");
    expect(!agentcodi_test::ReadBootstrapCommandOutput(
        "{\"id\":28,\"exitCode\":0,\"result\":{\"stdout\":\"ok\",\"stderr\":\"\"}}",
        &standard_output), "an exit code outside result cannot pass");
  }
  for (const std::size_t chunk_size : {1U, 3U, 17U, 1024U}) {
    BootstrapTerminal terminal;
    // Preserve output while waiting for resize; the old reader discarded it.
    const std::string prefix = kOutput.substr(0U, 10U);
    for (int i = 0; i < 80; ++i) {
      expect(terminal.Consume(output(encode("."))) == Event::kPending,
          "one-byte PTY output does not exhaust an arbitrary event count");
    }
    expect(terminal.Consume(output(encode(prefix))) == Event::kPending, "retain early output");
    expect(terminal.Consume(kResize) == Event::kSendInput, "input follows correlated resize once");
    for (std::size_t i = prefix.size(); i < kOutput.size(); i += chunk_size) {
      expect(terminal.Consume(output(encode(kOutput.substr(i, chunk_size)))) == Event::kPending,
          "fragmented output alone is not successful completion");
    }
    expect(terminal.Consume(kCompleted) == Event::kPending, "completion still needs input acknowledgement");
    expect(terminal.Consume(kWrite) == Event::kComplete,
        "all markers and reordered acknowledgements complete regardless of chunk boundaries");
  }
  {
    BootstrapTerminal terminal;
    expect(terminal.Consume(kResize) == Event::kSendInput, "resize accepted");
    expect(terminal.Consume(kWrite) == Event::kPending, "input acknowledgement alone is insufficient");
    expect(terminal.Consume(output(encode(kOutput))) == Event::kPending, "command completion is mandatory");
    expect(terminal.Consume(kCompleted) == Event::kComplete, "normal completion order works");
  }
  {
    BootstrapTerminal terminal;
    terminal.Consume(kResize);
    terminal.Consume(kWrite);
    terminal.Consume(output(encode(kOutput), "another-process"));
    expect(terminal.Consume(kCompleted) == Event::kPending, "unrelated process output cannot satisfy markers");
    expect(terminal.IncompleteSummary().find("expected-output=no") != std::string::npos,
        "incomplete diagnostic identifies missing output without exposing it");
  }
  {
    BootstrapTerminal terminal;
    terminal.Consume(kResize);
    terminal.Consume(output(encode(kOutput)));
    terminal.Consume("{\"id\":80,\"result\":{}}");
    expect(terminal.Consume(kCompleted) == Event::kPending, "request 80 must not acknowledge request 8");
    expect(terminal.Consume(kWrite) == Event::kComplete, "only the exact input request ID completes");
  }
  {
    BootstrapTerminal terminal;
    terminal.Consume(kResize);
    terminal.Consume(kWrite);
    terminal.Consume(output(encode("terminal-protocol-smoke\nv24.18.0\nripgrep 15.2.0")));
    expect(terminal.Consume(kCompleted) == Event::kPending, "tool activation markers remain mandatory");
  }
  {
    BootstrapTerminal terminal;
    expect(terminal.Consume("{\"id\":6,\"result\":{\"exitCode\":9,\"stdout\":\"\",\"stderr\":\"\"}}")
        == Event::kFailed, "sandbox or command failure cannot pass");
  }
  {
    BootstrapTerminal terminal;
    terminal.Consume(kResize);
    terminal.Consume(kWrite);
    terminal.Consume(output(encode(kOutput)));
    expect(terminal.Consume("{\"id\":6,\"result\":{\"exitCode\":124,\"stdout\":\"\",\"stderr\":\"\"}}")
        == Event::kFailed, "a terminal timeout cannot pass even after all output markers arrive");
    expect(std::string(terminal.failure()) == "Bootstrap RPC 6 command exited with code 124. "
        "The command exceeded its execution deadline.",
        "terminal timeout reports its actual RPC and exit status");
    expect(terminal.Consume(kCompleted) == Event::kFailed,
        "later notifications cannot undo a failed terminal deadline");
  }
  {
    BootstrapTerminal terminal;
    expect(terminal.Consume("{\"id\":6,\"error\":{\"message\":\"synthetic private detail\"}}")
        == Event::kFailed, "early command RPC errors are retained before resize");
    expect(std::string(terminal.failure()).find("synthetic private detail") == std::string::npos,
        "RPC diagnostics do not copy runtime error contents");
  }
  {
    BootstrapTerminal terminal;
    terminal.Consume(kResize);
    expect(terminal.Consume(kResize) == Event::kFailed, "duplicate resize must not resend input");
  }
  for (const std::string& non_progress : {output(""), output("YQ==", "another-process"),
      std::string("{\"method\":\"unrelated\",\"params\":{}}")}) {
    BootstrapTerminal terminal;
    for (int i = 0; i < 64; ++i) {
      expect(terminal.Consume(non_progress) == Event::kPending, "bounded non-output events tolerated");
    }
    expect(terminal.Consume(non_progress) == Event::kFailed, "empty or unrelated event flood is bounded");
  }
  for (const std::string invalid : {"***=", "YR==", "YQ=", "YQ==YQ=="}) {
    BootstrapTerminal terminal;
    expect(terminal.Consume(output(invalid)) == Event::kFailed, "malformed Base64 fails closed");
  }
  {
    BootstrapTerminal terminal;
    const std::string chunk = output(encode(std::string(4096U, 'x')));
    for (int i = 0; i < 32; ++i) {
      expect(terminal.Consume(chunk) == Event::kPending, "output within byte budget is accepted");
    }
    expect(terminal.Consume(output("eA==")) == Event::kFailed, "cumulative output bound is preserved");
  }
  {
    BootstrapTerminal terminal;
    std::string truncated = output(encode(kOutput));
    truncated.replace(truncated.find("false"), 5U, "true");
    expect(terminal.Consume(truncated) == Event::kFailed, "runtime output truncation cannot pass");
  }
  {
    std::string standard_output;
    const std::string warning = "{\"id\":9,\"result\":{\"exitCode\":0,\"stdout\":\"v24.18.0\\n\","
        "\"stderr\":\"libc: synthetic warning about \\\"vendor property\\\"\\n\"}}";
    expect(agentcodi_test::ReadBootstrapCommandOutput(warning, &standard_output)
        && standard_output == "v24.18.0\\n", "successful command may include bounded stderr warnings");
    expect(agentcodi_test::ReadBootstrapCommandOutput(
        "{\"id\":9,\"result\":{\"exitCode\":0,\"stdout\":\"\",\"stderr\":\"v24.18.0\"}}",
        &standard_output) && standard_output.empty(), "stderr cannot provide successful stdout markers");
    expect(!agentcodi_test::ReadBootstrapCommandOutput(
        "{\"id\":9,\"result\":{\"exitCode\":9,\"stdout\":\"v24.18.0\",\"stderr\":\"warning\"}}",
        &standard_output), "valid stdout cannot hide a nonzero exit status");
    expect(!agentcodi_test::ReadBootstrapCommandOutput(
        "{\"id\":9,\"result\":{\"exitCode\":0,\"stdout\":\"v24.18.0\",\"stderr\":null}}",
        &standard_output), "stderr must still be a string");
    expect(!agentcodi_test::ReadBootstrapCommandOutput(
        "{\"id\":9,\"result\":{\"exitCode\":0,\"stdout\":\"v24.18.0\",\"stderr\":\""
            + std::string(65536U * 6U + 1U, 'x') + "\"}}",
        &standard_output), "stderr remains bounded including JSON escaping overhead");
  }
  std::cout << "Terminal bootstrap: " << assertions << " assertions passed.\n";
}
