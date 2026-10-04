#!/system/bin/sh
set -eu
prefix=/data/data/de.agentcodi.pkg/files/usr
mkdir -p /data/data/de.agentcodi.pkg/files
export PREFIX="$prefix" TERMUX_PREFIX="$prefix"
export HOME=/data/data/de.agentcodi.pkg/files/agentcodi/home
export PATH="$prefix/bin:/system/bin" LD_LIBRARY_PATH="$prefix/lib" TMPDIR="$prefix/tmp"
mkdir -p "$HOME" "$TMPDIR"
"$prefix/bin/dpkg" --configure -a
"$prefix/bin/dpkg" --audit
"$prefix/bin/apt" --version
"$prefix/bin/gpgv" --version
"$prefix/bin/sh" -c 'test "$PREFIX" = /data/data/de.agentcodi.pkg/files/usr'
test -s "$prefix/etc/tls/cert.pem"
# Exercise the local package lifecycle without any unsigned repository or network.
"$prefix/bin/dpkg" -i /audit/bootstrap-fixture.deb
test "$("$prefix/bin/bootstrap-fixture")" = bootstrap-ok
"$prefix/bin/dpkg" --configure -a
"$prefix/bin/dpkg" -r bootstrap-fixture
test ! -e "$prefix/bin/bootstrap-fixture"
"$prefix/bin/dpkg" --audit
"$prefix/bin/dpkg-query" -W > /audit/installed-packages.txt
echo "ARM64 Bionic shell/APT/dpkg/certificates and local package lifecycle passed"
