# LLVM runtime notices

The SHA-256-pinned Termux libc++ 29 DEB declares the copyright link
../../LICENSES/NCSA.txt. The APK build verifies that link and copies the
unchanged common-license source to assets/third-party/libcxx/DISTRIBUTOR-LICENSE.
The checked-in DISTRIBUTOR-LICENSE comes from:
https://github.com/termux/termux-packages/blob/b6af76b353140fe17f299248fca1ac13ea91c5c5/packages/termux-licenses/LICENSES/NCSA.txt
It is the distributor's generic NCSA template. Keeping its source here avoids
adding another downloaded build input merely to dereference that notice.
A regular copyright file, if supplied instead, must match these same bytes.

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
