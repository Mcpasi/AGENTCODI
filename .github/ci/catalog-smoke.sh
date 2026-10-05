#!/system/bin/sh
set -eu
prefix=/data/data/de.agentcodi.pkg/files/usr
export PREFIX="$prefix" TERMUX_PREFIX="$prefix"
export HOME=/data/data/de.agentcodi.pkg/files/agentcodi/home
export PATH="$prefix/bin:/system/bin" LD_LIBRARY_PATH="$prefix/lib" TMPDIR="$prefix/tmp"
mkdir -p "$HOME" "$TMPDIR" /audit/evidence /audit/apt-empty/sources.list.d
: > /audit/apt-empty/sources.list
apt_local() {
    if [ "${AGENTCODI_CATALOG_REPOSITORY:-0}" = 1 ]; then
        apt-get -o Dir::Etc::sourcelist=/audit/repository.sources.list \
            -o Dir::Etc::sourceparts=/audit/apt-empty/sources.list.d \
            -o Acquire::https::CaInfo=/audit/fixture-ca.pem "$@"
        return
    fi
    apt-get -o Dir::Etc::sourcelist=/audit/apt-empty/sources.list \
        -o Dir::Etc::sourceparts=/audit/apt-empty/sources.list.d "$@"
}
/audit/ci-compat/bootstrap-api29-compat-test
dpkg --configure -a
# Local DEBs require no online source or repository authentication exception.
install_catalog() {
    if [ "${AGENTCODI_CATALOG_REPOSITORY:-0}" = 1 ]; then
        apt_local --no-install-recommends -y install $roots
    else
        apt_local --no-install-recommends -y install /audit/catalog/*.deb
    fi
}
case "$1" in
python) roots=python;;
node) roots="npm nodejs-lts";;
git) roots=git;;
ripgrep) roots=ripgrep;;
*) exit 64;;
esac
install_catalog
dpkg --audit
group="$1"
cd "$HOME"
case "$group" in
python)
    python --version
    python - <<'PY'
import bz2, ctypes, hashlib, lzma, multiprocessing, pathlib, sqlite3, ssl, sys, sysconfig, zlib
assert sys.prefix == "/data/data/de.agentcodi.pkg/files/usr"
assert sysconfig.get_config_var("HOST_GNU_TYPE").startswith("aarch64")
assert ssl.OPENSSL_VERSION
assert sqlite3.connect(":memory:").execute("select 6 * 7").fetchone()[0] == 42
assert zlib.decompress(zlib.compress(b"catalog")) == b"catalog"
assert lzma.decompress(lzma.compress(b"catalog")) == b"catalog"
assert bz2.decompress(bz2.compress(b"catalog")) == b"catalog"
lock = multiprocessing.Lock()
with lock:
    pass
pathlib.Path("/audit/evidence/python.json").write_text(
    __import__("json").dumps({"version": sys.version, "prefix": sys.prefix, "openssl": ssl.OPENSSL_VERSION}))
PY
    roots=python
    ;;
node)
    node --version
    node -e 'const fs=require("fs"), crypto=require("crypto"), assert=require("assert");
      assert(process.execPath.startsWith(process.env.PREFIX + "/bin/"));
      assert(crypto.createHash("sha256").update("catalog").digest("hex").length === 64);
      assert(Intl.DateTimeFormat("de-DE").resolvedOptions().locale === "de-DE");
      fs.writeFileSync("/audit/evidence/node.json", JSON.stringify(process.versions));'
    npm --version
    npx --version
    mkdir -p npm-fixture
    cd npm-fixture
    printf '{"name":"agentcodi-catalog-fixture","version":"1.0.0","scripts":{"test":"node -e \\"console.log(6*7)\\""}}\n' > package.json
    npm --offline --ignore-scripts pack
    npm --offline run test > /audit/evidence/npm-test.txt
    grep -q 42 /audit/evidence/npm-test.txt
    roots="npm nodejs-lts"
    ;;
git)
    git --version
    mkdir -p git-fixture
    cd git-fixture
    git init
    git config user.email ci@agentcodi.invalid
    git config user.name "AGENTCODI CI"
    printf 'catalog\n' > fixture.txt
    git add fixture.txt
    git commit -m fixture
    test "$(git show HEAD:fixture.txt)" = catalog
    git fsck --full
    git --no-pager log -1 > /audit/evidence/git.txt
    roots=git
    ;;
ripgrep)
    rg --version
    printf 'catalog 42\n' > rg-fixture.txt
    rg --pcre2 -o '(?<=catalog )\d+' rg-fixture.txt > /audit/evidence/ripgrep.txt
    test "$(cat /audit/evidence/ripgrep.txt)" = 42
    roots=ripgrep
    ;;
*) exit 64;;
esac
dpkg-query -W > /audit/evidence/installed-packages.txt
# Remove the roots, then reinstall through the same local dependency resolver.
apt_local -y remove $roots
for package in $roots; do
    test "$(dpkg-query -W -f='${db:Status-Status}' "$package" 2>/dev/null || true)" != installed
done
install_catalog
dpkg --audit
for package in $roots; do
    test "$(dpkg-query -W -f='${db:Status-Status}' "$package")" = installed
done
echo "$group local installation, execution, removal and reinstall passed"
