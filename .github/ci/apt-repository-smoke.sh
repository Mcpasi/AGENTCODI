#!/system/bin/sh
# Actual ARM64/Bionic APT, TLS, trusted-key and package lifecycle checks.
set -eu
prefix=/data/data/de.agentcodi.pkg/files/usr
export PREFIX="$prefix" TERMUX_PREFIX="$prefix"
export HOME=/data/data/de.agentcodi.pkg/files/agentcodi/home
export PATH="$prefix/bin:/system/bin" LD_LIBRARY_PATH="$prefix/lib" TMPDIR="$prefix/tmp"
mkdir -p "$HOME" "$TMPDIR" /audit/evidence /audit/apt-empty/sources.list.d
dpkg --configure -a
test -s "$prefix/etc/apt/keyrings/agentcodi-package.gpg"
test "$(dpkg-query -W -f='${db:Status-Status}' agentcodi-package-keyring)" = installed
apt_repo() {
    apt-get -o Dir::Etc::sourcelist=/audit/repository.sources.list \
        -o Dir::Etc::sourceparts=/audit/apt-empty/sources.list.d \
        -o Acquire::https::CaInfo=/audit/fixture-ca.pem \
        -o APT::Update::Error-Mode=any "$@"
}
use_source() {
    printf 'deb [arch=aarch64 signed-by=%s/etc/apt/keyrings/agentcodi-package.gpg] https://127.0.0.1:8443/%s stable main\n' \
        "$prefix" "$1" > /audit/repository.sources.list
    rm -rf "$prefix/var/lib/apt/lists"
    mkdir -p "$prefix/var/lib/apt/lists/partial"
}
use_source site/apt/package-edition
apt_repo update
export AGENTCODI_CATALOG_REPOSITORY=1
for group in python node git ripgrep; do
    "$prefix/bin/dash" /audit/catalog-smoke.sh "$group"
done
apt_repo -y install agentcodi-package-keyring
while IFS="$(printf '\t')" read -r name expected; do
    actual="$(dpkg-query -W -f='${Version}' "$name")"
    test "$actual" = "$expected"
done < /audit/expected-versions.tsv
use_source fixtures/v1
apt_repo update
apt_repo --no-install-recommends -y install repository-fixture
test "$(repository-fixture)" = 1.0
use_source fixtures/v2
apt_repo update
apt_repo --no-install-recommends -y upgrade
test "$(repository-fixture)" = 2.0
apt_repo -y remove repository-fixture
test ! -e "$prefix/bin/repository-fixture"
for fixture in bad-signature bad-index expired; do
    use_source "fixtures/$fixture"
    if apt_repo update > "/audit/evidence/$fixture.log" 2>&1; then
        echo "APT accepted invalid fixture $fixture" >&2
        exit 1
    fi
done
use_source fixtures/bad-deb
apt_repo update
rm -f /data/data/de.agentcodi.pkg/cache/apt/archives/*.deb
if apt_repo --no-install-recommends -y install repository-fixture > /audit/evidence/bad-deb.log 2>&1; then
    echo 'APT accepted a DEB with an invalid signed checksum' >&2
    exit 1
fi
use_source site/apt/package-edition
: > /audit/untrusted.gpg
sed -i "s|$prefix/etc/apt/keyrings/agentcodi-package.gpg|/audit/untrusted.gpg|" /audit/repository.sources.list
if apt_repo update > /audit/evidence/untrusted-key.log 2>&1; then
    echo 'APT accepted a repository without its trust key' >&2
    exit 1
fi
use_source site/apt/package-edition
apt_repo update
dpkg --audit
dpkg-query -W > /audit/evidence/repository-installed-packages.txt
echo 'Signed HTTPS APT catalog, lifecycle, key/signature/index/DEB/expiry rejection passed.' \
    > /audit/evidence/repository-result.txt
