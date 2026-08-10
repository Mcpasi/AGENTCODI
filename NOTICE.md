# Provenance

AGENTCODI is a clean Java/C++ Android reimplementation of the functionality found in the MIT-licensed `codex-mobile-main` / Codex Works project supplied beside this directory.

The upstream copyright and project-specific contribution notices are preserved in `LICENSE`. No upstream credential, user data, generated web bundle, Node.js runtime, or JavaScript/TypeScript source is copied into the APK.

AGENTCODI 0.3.2 packages the separately licensed Android ARM64 Codex CLI/app-server community build `@mmmbuto/codex-cli-termux` 0.147.2, including its matching `codex-code-mode-host`. It is an Android/Termux distribution fork of OpenAI Codex, not an official OpenAI Android APK. OpenAI Codex is Copyright 2025 OpenAI; the Android/Termux compatibility work is Copyright 2026 Davide A. Guglielmi. The artifact is distributed under Apache License 2.0. Its complete `LICENSE` and `NOTICE` files are copied verbatim into `assets/third-party/codex/` in every APK.

The Codex artifact is downloaded from the npm registry and accepted only when its SHA-256 is `4b70bca7004402cf445670efe43775e76ac598f719c72a8d6c83ac8494bb2b5c`. Android installs executable APK payloads from the native-library directory only under `lib<name>.so` names, while this exact Codex version hard-codes the sibling name `codex-code-mode-host`. AGENTCODI therefore makes one disclosed, equal-length binary data-field substitution in the already verified app-server: `codex-code-mode-host` becomes `libcodex-codehost.so`. The untouched source app-server, untouched host, and resulting Android app-server are accepted only at SHA-256 `c95b61282ed0086b9895b8d401fda274ef9ddf1a80fe808f3fad93f4444d8dc4`, `aa90fc2ce11bc309a08ea25836019fda6c7ff7edc9eaa35f8f3746a37979fc18`, and `11db4fdd763e21fa81f4fb47d61c4bcbea145e817364eaa35f6e75146f85beee`, respectively. No runtime code, authorization behavior, or license text is otherwise changed. Original AGENTCODI Java/C++ code remains distinct from this compiled third-party runtime.

The debug APK contains the LLVM libc++ shared runtime from the pinned Termux libc++ package. The distributor-supplied NCSA license text is included verbatim as an Android raw resource. A readable copy of its terms follows.

Copyright holders and contributor names are those of the LLVM/libc++ upstream project.

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal with the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

- Redistributions of source code must retain the copyright notice, conditions, and disclaimers.
- Redistributions in binary form must reproduce the copyright notice, conditions, and disclaimers in the documentation or other supplied materials.
- The names of the copyright holders, project, or contributors may not be used to endorse or promote derived products without specific prior written permission.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE CONTRIBUTORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
