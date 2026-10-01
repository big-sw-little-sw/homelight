#!/usr/bin/env bash
# Runs inside the Oracle Linux builder container started by build.sh; not meant to be run directly.
# Mounts: /src repository (ro), /dl downloads (ro), /m2 Maven repository, /out output.
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

mkdir -p /opt/graalvm /opt/maven /work
tar -xzf "/dl/$GRAALVM" -C /opt/graalvm --strip-components=1
tar -xzf "/dl/$MAVEN" -C /opt/maven --strip-components=1
export JAVA_HOME=/opt/graalvm PATH=/opt/graalvm/bin:/opt/maven/bin:$PATH

# Build from a copy so the read-only source mount and the host's target/ stay untouched.
tar -C /src --exclude=./target --exclude=./.git --exclude=./.idea -cf - . | tar -C /work -xf -
cd /work
mvn() { command mvn -B -ntp -Dmaven.repo.local=/m2 "$@"; }

start=$(date +%s)
mvn -Pnative -DskipTests package
echo "native build: $(( $(date +%s) - start ))s, NATIVE_IMAGE_OPTIONS=$NATIVE_IMAGE_OPTIONS"

# Fail the build when the binary needs more of the C library than the release target allows.
binary=target/homelight
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
mvn -q dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
bash ci/native/compare.sh /out/jvm-reference.txt \
  java -cp "target/classes:$(cat target/classpath.txt)" io.github.bigswlittlesw.homelight.cli.HomeLightCommand

cp "$binary" /out/homelight
chown -R "$HOST_UID:$HOST_GID" /out /m2
