# LLVM runtime notices

The APK copies the exact distributor copyright file from the SHA-256-pinned
Termux libc++ 29 DEB to assets/third-party/libcxx/DISTRIBUTOR-LICENSE.
That file supplies a generic NCSA template; it is retained unchanged.

LLVM-LICENSES supplements it with verbatim libc++, libc++abi and libunwind
LICENSE.TXT files from llvm/llvm-project tag llvmorg-21.1.8, source commit
2078da43e25a4623cab2d0d60decddf709aaea28:

- https://github.com/llvm/llvm-project/blob/2078da43e25a4623cab2d0d60decddf709aaea28/libcxx/LICENSE.TXT
- https://github.com/llvm/llvm-project/blob/2078da43e25a4623cab2d0d60decddf709aaea28/libcxxabi/LICENSE.TXT
- https://github.com/llvm/llvm-project/blob/2078da43e25a4623cab2d0d60decddf709aaea28/libunwind/LICENSE.TXT

They include Apache-2.0 with LLVM exceptions, the legacy NCSA/MIT terms and
upstream attribution. This is a source pin for legal text, not a claim that
the downloaded NDK/Termux binary was independently rebuilt from that commit.
The APK verifier compares the shipped combined text with this checked-in file.
The bootstrap's separate source-built libc++ retains its own package notice.
