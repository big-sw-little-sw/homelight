#!/bin/sh
# Runs inside a distro test container started by distros.sh; not meant to be run directly.
# Mounts: /ci this directory (ro), /hl binary and JVM reference (ro), /results output.
# Usage: test-in-container.sh <smoke|cli|full> <name>
set -eu
level=$1
name=$2

# Install only what the level needs: bash everywhere, expect and terminfo for the TUI.
packages=""
[ "$level" = full ] && packages="expect"
if command -v apt-get > /dev/null; then
  [ "$level" = full ] && packages="$packages ncurses-term"
  if [ -n "$packages" ]; then
    apt-get update -qq > /dev/null
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends $packages > /dev/null
  fi
elif command -v dnf > /dev/null; then
  [ "$level" = full ] && packages="$packages ncurses-term"
  [ -n "$packages" ] && dnf install -y -q $packages > /dev/null
elif command -v yum > /dev/null; then
  [ -n "$packages" ] && yum install -y -q $packages > /dev/null
elif command -v apk > /dev/null; then
  apk add -q bash $packages
fi

exec bash /ci/test.sh /hl/lighten /hl/jvm-reference.txt "$level" "/results/$name"
