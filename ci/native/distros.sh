#!/usr/bin/env bash
# Runs test.sh on a native binary inside containers of several Linux distributions.
#
#   ci/native/distros.sh <x86_64|arm64> <binary-dir> [results-dir]
#
# <binary-dir> holds lighten and jvm-reference.txt as written by build.sh. Each distro runs at
# the level listed below (see test.sh); "noexec" mounts /tmp noexec, which the JNI terminal
# provider cannot load its library from. Needs docker on a host of the same architecture
# (or an emulating one such as OrbStack). Exit status is non-zero when any distro fails.
set -u

arch=${1:?usage: distros.sh <x86_64|arm64> <binary-dir> [results-dir]}
bin_dir=$(cd "${2:?binary dir}" && pwd)
here=$(cd "$(dirname "$0")" && pwd)
results=${3:-$bin_dir/distros}
mkdir -p "$results"
results=$(cd "$results" && pwd)

# image level [noexec]. The host runner (Ubuntu 24.04) runs the full suite separately.
case $arch in
  x86_64) platform=linux/amd64; distros=(
    "oraclelinux:7 full"
    "oraclelinux:8 cli"
    "ubuntu:24.04 full noexec"
    "debian:13 cli"
    "fedora:latest cli"
    "alpine:latest smoke") ;;
  arm64) platform=linux/arm64; distros=(
    "oraclelinux:8 full"
    "ubuntu:24.04 full noexec"
    "fedora:latest cli") ;;
  *) echo "unknown arch: $arch" >&2; exit 2 ;;
esac

failed=()
for entry in "${distros[@]}"; do
  read -r image level noexec <<< "$entry"
  name=${image//[:\/]/-}${noexec:+-noexec}
  tmpfs=()
  [ -n "$noexec" ] && tmpfs=(--tmpfs /tmp:rw,noexec,nosuid)
  echo "::group::$image ($level${noexec:+, noexec /tmp})"
  start=$(date +%s)
  docker run --rm --platform "$platform" ${tmpfs[@]+"${tmpfs[@]}"} \
    -v "$here:/ci:ro" -v "$bin_dir:/hl:ro" -v "$results:/results" \
    "$image" sh /ci/test-in-container.sh "$level" "$name"
  status=$?
  echo "::endgroup::"
  echo "$image ($level${noexec:+, noexec}): $([ $status -eq 0 ] && echo pass || echo FAIL) in $(( $(date +%s) - start ))s"
  [ $status -eq 0 ] || failed+=("$image")
done

if [ ${#failed[@]} -gt 0 ]; then
  echo "failed: ${failed[*]}" >&2
  exit 1
fi
