> **Package Edition / Power-User-Version:** Dieser Branch bietet ausschließlich Full access. Programme mit den Rechten dieser App können auch Dateien außerhalb des Workspace und Codex-Kontodaten erreichen. Das ist Teil des dokumentierten Modells dieser Edition. Bestätigungsdialoge bilden keine Dateisystem-Sandbox. Siehe [README](README.md) und [Roadmap](ROADMAP-package-edition.md).

# Security Policy

AGENTCODI runs a local Codex app-server, development tools, and approved commands
on an Android device. Security reports are especially important when they concern
the boundaries between the private workspace, account data, Android storage,
packaged runtimes, and hosted services. The Package Edition APK contains the native
Codex app-server/code-mode host, the app engine and shell bridge, libc++ and zlib,
plus the minimal package bootstrap. Node.js, npm, Python and ripgrep are installed
separately through the signed edition APT repository. Retired APK activation
aliases are excluded from PATH; updates preserve user-installed packages.

## Browser and export areas

The graphical browser exposes only three explicit roots: workspace, the managed
package prefix and the user package prefix `$HOME/.local`. It does not expose
the entire home or Codex account directory. Package views reuse bounded,
descriptor-relative no-follow access, reject hard links, and exclude known
credential paths, including Codex auth files, SSH directories, `.npmrc`/`.pypirc` credential
configuration and APT auth configuration. Excluded entries are unavailable for
preview and export, including when selected directly as an archive root.

Imports always create a new, owner-only file below `workspace/imports`; they
never write into a package prefix, extract an archive or execute a package.
Exports preserve their selected area across the document picker and roll back
failed destinations. ZIPs omit links and permissions and are not installation
backups. Filename checks cannot identify credentials copied under arbitrary
names; users must review content before sharing it. These UI checks do not
change the documented Full-access reach of installed programs.

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

Install released APKs only from the official
[AGENTCODI GitHub Releases](https://github.com/Mcpasi/AGENTCODI/releases) page and
update to the latest release before reporting an issue that may already be fixed.
The Package Edition is still an unreleased development branch: its separate
debug APKs are official branch CI artifacts, not final releases. When testing
one, verify its `Mcpasi/package-edition` commit and successful build run and
include both in a report.

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
- whether the Package Edition was used, including the commit and approval setting; and
- any suggested remediation or planned disclosure date.

Send only the minimum evidence needed. Redact personal data, workspace content,
OAuth URL query parameters, and device identifiers. Never attach `auth.json`, API
keys, access tokens, passwords, signing material, or other live credentials. If a
secret was exposed while testing, revoke it before continuing.

Ordinary bugs and feature requests that have no security impact belong in
[GitHub Issues](https://github.com/Mcpasi/AGENTCODI/issues).

## Scope

Examples of issues that should be reported to AGENTCODI include:

- bypassing, misrepresenting, or reusing a command or file-change approval;
- exposing credentials, private content, or transient authentication data through
  the UI, logs, diagnostics, exports, or saved state;
- path traversal, link-following, race, or archive issues in import, browsing,
  preview, and export flows;
- unsafe handling of app-server messages or hosted-app metadata that crosses an
  enforced trust boundary;
- accepting a tampered or unexpected bundled runtime or toolchain artifact; and
- presenting an isolated workspace or a protected mode while Full access is active.

The following are generally outside this project's scope:

- vulnerabilities solely in Android, OpenAI services, Codex, Gmail, GitHub, or
  another upstream dependency, unless AGENTCODI's integration creates or worsens
  the issue;
- incorrect, insecure, or unwanted model-generated content that does not bypass an
  enforced AGENTCODI boundary;
- actions accurately shown to and explicitly approved by the user;
- access that depends on a rooted or already compromised device, a modified APK,
  or a compromised build environment; and
- the documented filesystem reach of Full access and user-installed programs in
  Package Edition.

An approval bypass, misleading approval scope, credential disclosure through the
UI or exports, or access beyond the edition's documented boundary remains in scope.
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
