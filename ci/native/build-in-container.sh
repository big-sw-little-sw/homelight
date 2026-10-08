#!/usr/bin/env bash
# Runs inside the Oracle Linux builder container started by build.sh; not meant to be run directly.
# Mounts: /src repository (ro), /dl downloads (ro), /gradle Gradle user home, /out output.
# The Gradle wrapper downloads its distribution into /gradle and verifies its checksum.
set -euo pipefail

case $ARCH in
  x86_64)
    yum install -y -q gcc glibc-devel zlib-devel tar gzip findutils binutils
    tar -xzf "/dl/$MUSL" -C /opt
    export PATH=/opt/musl-toolchain/bin:$PATH
    export NATIVE_IMAGE_OPTIONS="--static --libc=musl -march=compatibility" ;;
  arm64)
    dnf install -y -q dnf-plugins-core
    dnf config-manager --enable ol8_codeready_builder
    dnf install -y -q gcc glibc-devel zlib-devel zlib-static gcc-toolset-12-gcc tar gzip findutils binutils
    export PATH=/opt/rh/gcc-toolset-12/root/usr/bin:$PATH
    export NATIVE_IMAGE_OPTIONS="--static-nolibc -march=compatibility" ;;
esac

mkdir -p /opt/graalvm /work
tar -xzf "/dl/$GRAALVM" -C /opt/graalvm --strip-components=1
export JAVA_HOME=/opt/graalvm PATH=/opt/graalvm/bin:$PATH GRADLE_USER_HOME=/gradle

# Build from a copy so the read-only source mount and the host's build/ stay untouched.
tar -C /src --exclude=./build --exclude=./.gradle --exclude=./.git --exclude=./.idea -cf - . | tar -C /work -xf -
cd /work
gradle() { ./gradlew --no-daemon --console=plain "$@"; }

start=$(date +%s)
gradle nativeCompile installDist
echo "native build: $(( $(date +%s) - start ))s, NATIVE_IMAGE_OPTIONS=$NATIVE_IMAGE_OPTIONS"

# Fail the build when the binary needs more of the C library than the release target allows.
binary=build/native/nativeCompile/lighten
case $ARCH in
  x86_64)
    if readelf -d "$binary" | grep -q NEEDED; then
      echo "x86_64 binary is not fully static:" >&2; readelf -d "$binary" >&2; exit 1
    fi
    echo "x86_64 binary is fully static" ;;
  arm64)
    readelf -d "$binary" | grep NEEDED
    glibc=$(objdump -T "$binary" | grep -o 'GLIBC_[0-9.]*' | sort -Vu | tail -1)
    echo "arm64 binary requires $glibc"
    if [ "$(printf '%s\n' GLIBC_2.17 "$glibc" | sort -V | tail -1)" != GLIBC_2.17 ]; then
      echo "arm64 binary requires $glibc, above the GLIBC_2.17 floor" >&2; exit 1
    fi ;;
esac

# The JVM transcript every native test run is compared against.
bash ci/native/compare.sh /out/jvm-reference.txt build/install/lighten/bin/lighten

cp "$binary" /out/lighten
chown -R "$HOST_UID:$HOST_GID" /out /gradle
