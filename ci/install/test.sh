#!/usr/bin/env bash
# Tests install.sh inside containers of several Linux distributions, against local release assets.
#
#   ci/install/test.sh <x86_64|arm64> <assets-dir>
#
# <assets-dir> holds lighten-<version>-linux-<arch>-<libc> and SHA256SUMS, as a release does: the
# release workflow's dry-run artifact, or a CI build renamed to the release names. A busybox httpd
# container serves them at the paths GitHub uses, plus copies whose binaries do not match
# SHA256SUMS or are missing. scenarios.sh runs install.sh against them through LIGHTEN_INSTALL_BASE_URL.
# Needs docker on a host of the given architecture (or an emulating one such as OrbStack).
# Exit status is non-zero when any distro fails.
set -u

arch=${1:?usage: test.sh <x86_64|arm64> <assets-dir>}
assets=$(cd "${2:?assets dir}" && pwd)
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)

case $arch in
  x86_64) platform=linux/amd64; images=(ubuntu:24.04 fedora:latest alpine:latest oraclelinux:7) ;;
  arm64) platform=linux/arm64; images=(ubuntu:24.04 fedora:latest alpine:latest oraclelinux:7) ;;
  *) echo "unknown arch: $arch" >&2; exit 2 ;;
esac

version=$(sed -n 's/^[0-9a-f]* [ *]\{0,1\}lighten-\(.*\)-linux-[a-z0-9_]*-[a-z]*$/\1/p' "$assets/SHA256SUMS" | head -n 1)
[ -n "$version" ] || { echo "no lighten-<version>-linux-* line in $assets/SHA256SUMS" >&2; exit 2; }

srv=$(mktemp -d)
net=lighten-install-$$
cleanup() {
  docker rm -f "$net-srv" > /dev/null 2>&1
  docker network rm "$net" > /dev/null 2>&1
  rm -rf "$srv"
}
trap cleanup EXIT

# good/: a release as GitHub serves it. bad/: the latest release with corrupted binaries.
# missing/: SHA256SUMS without the binaries, so their download fails.
for dir in good/latest/download "good/download/v$version" bad/latest/download; do
  mkdir -p "$srv/$dir"
  cp "$assets"/SHA256SUMS "$assets"/lighten-* "$srv/$dir/"
done
for binary in "$srv"/bad/latest/download/lighten-*; do printf x >> "$binary"; done
mkdir -p "$srv/missing/latest/download"
cp "$assets/SHA256SUMS" "$srv/missing/latest/download/"
chmod -R a+rX "$srv"

docker network create "$net" > /dev/null || exit 1
docker run -d --name "$net-srv" --network "$net" -v "$srv:/srv:ro" busybox:1.37 \
  httpd -f -p 8080 -h /srv > /dev/null || exit 1

failed=()
for image in "${images[@]}"; do
  echo "::group::$image ($arch)"
  start=$(date +%s)
  docker run --rm --platform "$platform" --network "$net" \
    -v "$here:/ci:ro" -v "$repo/install.sh:/install.sh:ro" \
    -e BASE="http://$net-srv:8080" -e VERSION="$version" \
    "$image" sh /ci/scenarios.sh
  status=$?
  echo "::endgroup::"
  echo "$image ($arch): $([ $status -eq 0 ] && echo pass || echo FAIL) in $(( $(date +%s) - start ))s"
  [ $status -eq 0 ] || failed+=("$image")
done

if [ ${#failed[@]} -gt 0 ]; then
  echo "failed: ${failed[*]}" >&2
  exit 1
fi
