**  CHANGELOG.md is being introduced starting with version 0.6.10. ** 

#  Internal versions

## AGENTCODI 0.6.10 

- Enhanced security in "danger-full-access" mode: after activation, you can now enable approval for file or command changes.

230 Java and 297 C++ tests passed

SHA-256: 1624a919c6bcb7358bc59e582002847982059920913943f90445a764f463e4a8

## AGENTCODI 0.6.11 

Fixed terminal backspace handling for emojis and other Unicode characters, preventing corrupted characters in shell output.

231 java and 297 C++ tests passed 

SHA-256: 4e3d825c54ab29389e5989b819a087efb1baa2dbb3daaee76a2f60614088dc39

## AGENTCODI 0.6.12 

- Refined the shared UI theme with softer corners, subtle elevation, and improved button styling.

- Unified card, status banner, thread list, and icon button styling across the app.

- Improved system bar and accent colors for a more consistent Android UI.
231 Java and 297 C++ tests passed

SHA-256: a9be3287860eb91962a9ed565fae76140657a7d0319933de8c054c423d33b0e3

## AGENTCODI 0.6.13 

- Refined the native interface with redesigned tool cards, clearer status badges, improved command and diff presentation, and better visual separation of tool details.

- Improved the thread list with more spacious cards, two-line titles, and clearer highlighting of the currently opened chat.

- Adjusted interface colors and badge contrast for improved readability in both light and dark themes.

236 Java and 297 C++ tests passed

SHA-256: bc8f66420f8b6376a22eef13dd483d9d67de0dd5c1e77d0c192ae54d07d1da9b

## AGENTCODI 0.7.0 

-  The pinned app server has been upgraded to version 0.153.2; the new app server includes support for GPT6-Astra.

258 Java and 302 C++ tests passed

The release will follow shortly after extensive device testing.

Update: Device tests are considered to have failed due to a missing Android backend in the codex app server's sandbox for this reason, I have written a sandbox for the codex app server; this is integrated into a separate fork (0.153.3-agentcodi.1) and is included in version 0.7.1

SHA-256: 22447a04daca6622199143bd785ed62346c817d31d2d144c8b85c8214ad26349

## AGENTCODI 0.7.2

- Just-in-Time permissions have been added and implemented in the new app server (0.153.3-agentcodi.2); consequently, the permission profile is enforced directly within the app server.

- A stop function for the runtime was added via a button.

The tests and the SHA-256 will only be included in the final release.

278 Java and 323 c++ tests passed

SHA-256: 5d16ec3b89a3b9e89c1e95cb8d43afe83d6105e67f876bad502a5838c93ef725

# RELEASE_APK

## AGENTCODI 0.6.11 - RELEASE - 2026-09-04

- Enhanced security in "danger-full-access" mode: after activation, you can now enable approval for file or command changes.

- Fixed terminal backspace handling for emojis and other Unicode characters, preventing corrupted characters in shell output.

231 Java and 297 C++ tests passed

SHA-256: 279d86e0b418a9d7cfa611a3d45b10b85eee8e6cfe767048fa9fd400007e67c1

## AGENTCODI 0.7.1 - RELEASE - 2026-09-09

After testing on several devices, this artifact (version 0.7.1) passed; testing was conducted on the Samsung Galaxy 05s, Redmi Pad 2, Redmi 14c, and Redmi Note 15.

I need your help here: if you are having problems with the new sandbox—for example, if you cannot access it—please open an issue and specify your model and Android version.

- Added an Android sandbox backend through the custom Codex app-server fork "0.153.3-agentcodi.1", restoring effective filesystem isolation for Protected mode on Android.

- Sandboxed commands are enforced through a seccomp and ptrace supervisor, allowing Codex to work inside the permitted workspace while blocking filesystem access outside the granted boundary.

- Added runtime verification for syscall interception. If sandbox enforcement cannot be verified on the device, command execution is refused instead of silently falling back to unrestricted execution.

- Improved sandbox compatibility across Android devices and expanded regression coverage for sandbox initialization, syscall interception, filesystem boundaries and platform-specific failure cases.

265 java tests and 321 C++ tests passed

SHA256: b7a5c3e1a26378510d42f360fc28b6412c6267efaa5a72ca8d9b56eb490f268e

## AGENTCODI 0.7.2 - RELEASE - 2026-09-11

This version was also tested on real devices, including the Samsung Galaxy A05s, Samsung Galaxy A07, Redmi 14c, Redmi Note 15, and the Redmi Pad 2.

- Just-in-Time permissions have been added and implemented in the new app server (0.153.3-agentcodi.2) conseqently, the permission Profile is enforced directly within the app-server

- A stop function for the runtime was added via a button.

Update: This version has been submitted to APKPure and is currently awaiting approval.

278 Java and 325 C++ tests passed

sha256:867afc6e2f8acb39be6b71d190bfff4a4a2e3d74a4eef2bb54a3d5daf557551e