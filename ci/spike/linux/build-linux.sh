#!/bin/bash
# Builds homelight Linux native binaries in containers. Output: $S/linux/out/<name>.
# Usage: build-linux.sh <amd64-glibc|arm64-glibc|amd64-musl>
# Prereqs in $S/linux/dl: graalvm-jdk-25.0.3_linux-{x64,aarch64}_bin.tar.gz (download.oracle.com/graalvm/25/archive/),
# apache-maven-3.9.16-bin.tar.gz, musl-toolchain-1.2.5-oracle-00001-linux-amd64.tar.gz (musl only).
# Host ~/.m2/repository is mounted read-only as a Maven tail repository (tamboui SNAPSHOTs are only there).
set -eu
S=/private/tmp/claude-501/-Users-jsiva-sw-code-homelight/785981ef-3d18-4954-b06f-24e1a7f65e8a/scratchpad
WT=/Users/jsiva/sw/code/homelight/.claude/worktrees/agent-a7aa57028951b9cca
L=$S/linux
case ${1:?target} in
  amd64-glibc) plat=linux/amd64; df=Dockerfile.ol7; ctx=ctx-x64;     tag=homelight-spike-ol7:amd64; opts="--static-nolibc -march=compatibility" ;;
  arm64-glibc) plat=linux/arm64; df=Dockerfile.ol8; ctx=ctx-aarch64; tag=homelight-spike-ol8:arm64; opts="--static-nolibc -march=compatibility" ;;
  amd64-musl)  plat=linux/amd64; df=Dockerfile.musl; ctx=ctx-musl;   tag=homelight-spike-musl:amd64; opts="--static --libc=musl -march=compatibility"
               docker build --platform linux/amd64 -t homelight-spike-ol7:amd64 -f $L/Dockerfile.ol7 $L/ctx-x64 ;;
  *) echo "unknown target"; exit 2 ;;
esac
docker build --platform $plat -t $tag -f $L/$df $L/$ctx
docker volume create homelight-spike-m2 > /dev/null
docker run --rm --platform $plat -v $WT:/src:ro -v $HOME/.m2/repository:/m2ro:ro -v homelight-spike-m2:/m2 \
  -v $L:/scripts:ro -v $L/out:/out -e OUTNAME=homelight-$1 -e "NATIVE_IMAGE_OPTIONS=$opts" $tag /scripts/build-in-container.sh
