#!/usr/bin/env bash
# Tests lighten update against a local server laid out as GitHub Releases (serve-releases.py),
# each release with the repository's install.sh, so the native binary's downloads and its run of
# the script need no internet.
#
#   ci/native/update.sh <binary> [results-dir]
#
# The published release is 9.9.9, whose "binary" is a shell script that reports it; 9.9.8's
# SHA256SUMS does not match its binary.
# - A development build (CI's): update --check reports 9.9.9; update refuses; without curl or
#   wget it says so.
# - A release build (RELEASE_VERSION in build.sh): also a checksum mismatch that replaces
#   nothing, an update to 9.9.9, and an update through a symlink that replaces its target.
# Needs python3, curl or wget, and sha256sum. Exit status is non-zero when any check fails.
set -u

binary=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
here=$(cd "$(dirname "$0")" && pwd)
results=${2:-$(mktemp -d "${TMPDIR:-/tmp}/lighten-update.XXXXXX")}/update
rm -rf "$results"
mkdir -p "$results"
failed=0

pass() { echo "PASS update: $*"; }
fail() { echo "FAIL update: $*"; failed=1; }

case $(uname -m) in
  x86_64) platform=linux-x86_64-musl ;;
  aarch64) platform=linux-aarch64-gnu ;;
  *) echo "no release asset for $(uname -m)" >&2; exit 2 ;;
esac
sha256() { sha256sum "$1" | cut -d ' ' -f 1; }

publish() { # <version> [sha256 override]
  local dir=$results/releases/download/v$1 asset=lighten-$1-$platform
  mkdir -p "$dir"
  printf '#!/bin/sh\necho "lighten %s"\n' "$1" > "$dir/$asset"
  echo "${2:-$(sha256 "$dir/$asset")}  $asset" > "$dir/SHA256SUMS"
  cp "$here/../../install.sh" "$dir/install.sh"
}
publish 9.9.9
publish 9.9.8 "$(printf '0%.0s' {1..64})"

python3 "$here/serve-releases.py" "$results/releases" 9.9.9 "$results/port" 2> "$results/server.log" &
server=$!
trap 'kill $server 2> /dev/null' EXIT
for _ in $(seq 50); do [ -f "$results/port" ] && break; sleep 0.1; done
base=http://127.0.0.1:$(cat "$results/port")

lighten() { # <binary> <args...>: runs it against the server, output in $results/out, exit code in $status
  local bin=$1; shift
  echo "\$ lighten $*" >> "$results/transcript.txt"
  LIGHTEN_INSTALL_BASE_URL=$base "$bin" "$@" > "$results/out" 2>&1 < /dev/null
  status=$?
  cat "$results/out" >> "$results/transcript.txt"
  echo "(exit $status)" >> "$results/transcript.txt"
}

version=$("$binary" --version)
version=${version#lighten }
mkdir -p "$results/bin"
cp "$binary" "$results/bin/lighten"
copy=$results/bin/lighten

lighten "$copy" update --check
if [ $status -eq 0 ] && grep -qx 'Latest:    lighten 9.9.9' "$results/out"; then
  pass "--check: $(tr '\n' ' ' < "$results/out")"
else
  fail "--check, exit $status: $(cat "$results/out")"
fi

status=0
LIGHTEN_INSTALL_BASE_URL=$base PATH=/nonexistent "$copy" update --check > "$results/out" 2>&1 || status=$?
if [ $status -eq 1 ] && grep -q 'needs curl or wget' "$results/out"; then
  pass "without curl or wget"
else
  fail "without curl or wget, exit $status: $(cat "$results/out")"
fi

if [[ $version == *-SNAPSHOT ]]; then
  lighten "$copy" update
  if [ $status -eq 1 ] && grep -q 'development build' "$results/out" && cmp -s "$binary" "$copy"; then
    pass "a development build is not replaced"
  else
    fail "development build, exit $status: $(cat "$results/out")"
  fi
  exit $failed
fi

lighten "$copy" update --version 9.9.8
if [ $status -eq 1 ] && grep -q 'does not match SHA256SUMS' "$results/out" && cmp -s "$binary" "$copy" &&
    [ "$(ls -A "$results/bin")" = lighten ]; then
  pass "a checksum mismatch replaces nothing"
else
  fail "checksum mismatch, exit $status: $(cat "$results/out")"
fi

lighten "$copy" update
if [ $status -eq 0 ] && grep -qx "Updated lighten from $version to 9.9.9." "$results/out" &&
    [ "$("$copy" --version)" = "lighten 9.9.9" ] && [ "$(ls -A "$results/bin")" = lighten ]; then
  pass "update replaces $version with 9.9.9"
else
  fail "update, exit $status: $(cat "$results/out")"
fi

# Through a symlink in another directory: the script runs on the real directory, so the link stays.
mkdir -p "$results/real" "$results/links"
cp "$binary" "$results/real/lighten"
ln -s "$results/real/lighten" "$results/links/lighten"
lighten "$results/links/lighten" update
if [ $status -eq 0 ] && [ -L "$results/links/lighten" ] && [ "$("$results/links/lighten" --version)" = "lighten 9.9.9" ]; then
  pass "update through a symlink replaces its target"
else
  fail "update through a symlink, exit $status: $(cat "$results/out")"
fi
exit $failed
