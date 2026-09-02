# Security Policy

AGENTCODI runs a local Codex app-server, development tools, and approved commands
on an Android device. Security reports are especially important when they concern
the boundaries between the private workspace, account data, Android storage,
packaged runtimes, and hosted services.

## Supported versions

AGENTCODI is in active early-access development. Security fixes are made on the
current development line and released in a new APK; fixes are not routinely
backported.

| Version | Security fixes |
|---|---|
| Latest release | Yes |
| Current default branch | Best effort, before the next release |
| Older releases | No |
| Modified, repackaged, or unofficial APKs | No |

Install APKs only from the official
[AGENTCODI GitHub Releases](https://github.com/Mcpasi/AGENTCODI/releases) page and
update to the latest release before reporting an issue that may already be fixed.

## Reporting a vulnerability

Please use GitHub's
[private vulnerability reporting](https://github.com/Mcpasi/AGENTCODI/security/advisories/new).
Do not disclose a suspected vulnerability in a public issue, discussion, pull
request, or social-media post.

A useful report includes:

- the affected AGENTCODI release or commit and where the APK came from;
- the Android version and device architecture;
- a clear description of the impact and required attacker capabilities;
- minimal, repeatable steps or a proof of concept;
- whether Protected Mode or Compatibility Mode was active; and
- any suggested remediation or planned disclosure date.

Send only the minimum evidence needed. Redact personal data, workspace content,
OAuth URL query parameters, and device identifiers. Never attach `auth.json`, API
keys, access tokens, passwords, signing material, or other live credentials. If a
secret was exposed while testing, revoke it before continuing.

Ordinary bugs and feature requests that have no security impact belong in
[GitHub Issues](https://github.com/Mcpasi/AGENTCODI/issues).

## Scope

Examples of issues that should be reported to AGENTCODI include:

- escaping Protected Mode or accessing files outside its intended private
  workspace;
- crossing the separation between the workspace and Codex account data;
- bypassing, misrepresenting, or reusing a command or file-change approval;
- exposing credentials, private content, or transient authentication data through
  the UI, logs, diagnostics, exports, or saved state;
- path traversal, link-following, race, or archive issues in import, browsing,
  preview, and export flows;
- unsafe handling of app-server messages or hosted-app metadata that crosses an
  enforced trust boundary;
- accepting a tampered or unexpected bundled runtime or toolchain artifact; and
- switching into Compatibility Mode without the required warning and explicit
  acknowledgement.

The following are generally outside this project's scope:

- vulnerabilities solely in Android, OpenAI services, Codex, Gmail, GitHub, or
  another upstream dependency, unless AGENTCODI's integration creates or worsens
  the issue;
- incorrect, insecure, or unwanted model-generated content that does not bypass an
  enforced AGENTCODI boundary;
- actions accurately shown to and explicitly approved by the user;
- access that depends on a rooted or already compromised device, a modified APK,
  or a compromised build environment; and
- the documented loss of effective filesystem isolation after a user explicitly
  enables experimental Compatibility Mode.

An approval bypass, misleading approval scope, failure to reset Compatibility
Mode, or access beyond the selected mode's documented boundary remains in scope.
If it is unclear whether a weakness belongs to AGENTCODI or an upstream project,
report it privately here first and explain why AGENTCODI may be involved.

## Safe research

When testing AGENTCODI:

- use only devices, accounts, and data that you own or are authorized to test;
- minimize access to personal data and stop if you encounter another person's data;
- do not perform denial-of-service, spam, social engineering, persistence, or
  destructive testing;
- do not upload, retain, or disclose data obtained beyond what is necessary to
  demonstrate the issue; and
- allow reasonable time for a fix before public disclosure.

Good-faith research that follows this policy will be treated as authorized by the
AGENTCODI project. This statement does not authorize violations of applicable law
or third-party terms and cannot bind third parties.

## Response and disclosure

The maintainer aims to acknowledge a report within 7 calendar days and provide an
initial assessment within 14 calendar days. Complex reports may take longer, but
material progress will be shared through the private advisory. Confirmed issues
will be addressed according to severity and may result in a GitHub Security
Advisory, a new release, and a CVE where appropriate.

Please coordinate publication with the maintainer. Reporter credit will be given
when requested, unless the reporter prefers to remain anonymous. AGENTCODI does
not currently operate a paid bug-bounty program.
