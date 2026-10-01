#!/usr/bin/env bash
# Builds a Linux native binary of homelight in a container. Needs docker; runs on Linux or macOS.
#
#   ci/native/build.sh <x86_64|arm64> [out-dir]
#
# x86_64: fully static musl binary, built on Oracle Linux 7 with Oracle's musl toolchain.
# arm64:  --static-nolibc binary (glibc 2.17+), built on Oracle Linux 8 with gcc-toolset-12.
#         OL7's gcc 4.8 libgcc lacks the aarch64 outline atomics GraalVM's static libraries need.
#
# Writes <out-dir>/homelight (default target/native-<arch>) and <out-dir>/jvm-reference.txt,
# the CLI comparison transcript from the same build run on the JVM (see compare.sh).
# Downloads and the Maven repository are kept in $HOMELIGHT_CI_CACHE (default ~/.cache/homelight-ci).
set -euo pipefail

arch=${1:?usage: build.sh <x86_64|arm64> [out-dir]}
repo=$(cd "$(dirname "$0")/../.." && pwd)
out=${2:-$repo/target/native-$arch}
cache=${HOMELIGHT_CI_CACHE:-$HOME/.cache/homelight-ci}

graalvm_version=25.0.3
maven_version=3.9.16
musl_toolchain=musl-toolchain-1.2.5-oracle-00001-linux-amd64

case $arch in
  x86_64) platform=linux/amd64; image=oraclelinux:7; graalvm_arch=x64
          graalvm_sha=1b5296613c3d12521d594e1c99302df88d8e1f07d74ab7983dbf572000b92c7c ;;
  arm64)  platform=linux/arm64; image=oraclelinux:8; graalvm_arch=aarch64
          graalvm_sha=2c6e5ef63084c5f39c67cbf5c33d23c852df848d10cd50d76e667fff9c9cf2bc ;;
  *) echo "unknown arch: $arch" >&2; exit 2 ;;
esac

fetch() { # <url> <sha256>: downloads into $cache/dl once, verifying the checksum
  local file=$cache/dl/${1##*/}
  if [ ! -f "$file" ]; then
    curl -fsSL --retry 3 -o "$file.part" "$1"
    mv "$file.part" "$file"
  fi
  local sum
  sum=$( (sha256sum "$file" 2>/dev/null || shasum -a 256 "$file") | cut -d' ' -f1)
  [ "$sum" = "$2" ] || { rm -f "$file"; echo "checksum mismatch: $1" >&2; exit 1; }
}

mkdir -p "$cache/dl" "$cache/m2" "$out"
out=$(cd "$out" && pwd)  # docker treats a relative -v source as a volume name
fetch "https://download.oracle.com/graalvm/25/archive/graalvm-jdk-${graalvm_version}_linux-${graalvm_arch}_bin.tar.gz" "$graalvm_sha"
fetch "https://archive.apache.org/dist/maven/maven-3/${maven_version}/binaries/apache-maven-${maven_version}-bin.tar.gz" \
  80ffca22aed9e8b9713a232f3394fd81d7f20322df75efdb2b047dbd3e3a23bb
if [ "$arch" = x86_64 ]; then
  fetch "https://gds.oracle.com/download/bfs/archive/${musl_toolchain}.tar.gz" \
    77a60e1d31303f214e1c9d8e5843abcce2743a0c6f6b436e251f69a3161801ad
fi

docker run --rm --platform "$platform" \
  -v "$repo:/src:ro" -v "$cache/dl:/dl:ro" -v "$cache/m2:/m2" -v "$out:/out" \
  -e ARCH="$arch" -e HOST_UID="$(id -u)" -e HOST_GID="$(id -g)" \
  -e GRAALVM="graalvm-jdk-${graalvm_version}_linux-${graalvm_arch}_bin.tar.gz" \
  -e MAVEN="apache-maven-${maven_version}-bin.tar.gz" -e MUSL="${musl_toolchain}.tar.gz" \
  "$image" bash /src/ci/native/build-in-container.sh

ls -l "$out/homelight"
