#!/usr/bin/env bash
# Tests a Linux native homelight binary. Runs on a Linux host or inside a test container.
#
#   ci/native/test.sh <binary> <jvm-reference> <smoke|cli|full> [results-dir]
#
# smoke: --version and plan --json on a small fixture.
# cli:   smoke, then the compare.sh suite diffed against the JVM transcript from build.sh.
# full:  cli, then the TUI under expect for each TERM in $TUI_TERMS, TERM=dumb refused with exit 2,
#        and the setup flow finding a bundled candidate (setup.exp).
#        Needs expect and the terminfo entries for those TERMs (ncurses-term on Debian and Fedora).
#
# Logs go to results-dir (default: a new temporary directory). Every command gets an explicit
# config path under results-dir, so nothing in the user's home is read or written.
# Exit status is non-zero when any check fails.
set -u

binary=$(cd "$(dirname "$1")" && pwd)/$(basename "$1")
reference=$2
level=$3
here=$(cd "$(dirname "$0")" && pwd)
results=${4:-$(mktemp -d "${TMPDIR:-/tmp}/homelight-test.XXXXXX")}
mkdir -p "$results"
results=$(cd "$results" && pwd)
failed=0

pass() { echo "PASS $*"; }
fail() { echo "FAIL $*"; failed=1; }

(. /etc/os-release 2>/dev/null && echo "distro: ${PRETTY_NAME:-unknown}")
echo "arch: $(uname -m), libc: $(getconf GNU_LIBC_VERSION 2>/dev/null || echo non-glibc), /tmp: $(awk '$2 == "/tmp" {print $4}' /proc/mounts)"

# Smoke
version=$("$binary" --version 2>&1) && pass "--version: $version" || fail "--version: $version"
fx=$results/smoke
mkdir -p "$fx/home/a" "$fx/local"
echo x > "$fx/home/a/f"
printf 'homelight:\n  target-root: %s/local\n  relocations:\n    - source-path: %s/home/a\n      target-path: %s/local/a\n' \
  "$fx" "$fx" "$fx" > "$fx/config.yaml"
if "$binary" -c "$fx/config.yaml" plan --json > "$results/smoke-plan.json" 2>&1 && grep -q '"relocations"' "$results/smoke-plan.json"; then
  pass "plan --json"
else
  fail "plan --json: $(head -c 500 "$results/smoke-plan.json")"
fi
[ "$level" = smoke ] && exit $failed

# CLI comparison against the JVM
bash "$here/compare.sh" "$results/native-transcript.txt" "$binary"
# Plain string comparison: minimal images (Fedora, Oracle Linux) ship without diff.
if [ "$(cat "$reference")" == "$(cat "$results/native-transcript.txt")" ]; then
  pass "CLI comparison matches the JVM"
else
  fail "CLI comparison differs from the JVM (see $results/native-transcript.txt)"
  command -v diff > /dev/null && diff -u "$reference" "$results/native-transcript.txt" | head -100
fi
[ "$level" = cli ] && exit $failed

# TUI
fx=$results/tui
mkdir -p "$fx/home/a" "$fx/home/b" "$fx/local/b"
echo a > "$fx/home/a/f"; echo b > "$fx/home/b/f"; echo t > "$fx/local/b/t"
printf 'homelight:\n  target-root: %s/local\n  relocations:\n    - source-path: %s/home/a\n      target-path: %s/local/a\n    - source-path: %s/home/b\n      target-path: %s/local/b\n' \
  "$fx" "$fx" "$fx" "$fx" "$fx" > "$fx/config.yaml"
for term in ${TUI_TERMS:-xterm-256color screen-256color tmux-256color linux vt100}; do
  log=$results/tui-$term.log
  line=$(TERM=$term expect "$here/tui.exp" "$log" "$binary" -c "$fx/config.yaml" status)
  status=$?
  if [ $status -eq 0 ] && ! grep -q 'Failed to load native library' "$log"; then
    pass "TUI TERM=$term: $line"
  else
    fail "TUI TERM=$term: $line"
  fi
done
line=$(TERM=dumb expect "$here/tui.exp" "$results/tui-dumb.log" "$binary" -c "$fx/config.yaml" status)
if [ $? -eq 3 ] && [[ $line == *exit=2* ]] && grep -q 'does not support a dumb terminal' "$results/tui-dumb.log"; then
  pass "TUI TERM=dumb refused: $line"
else
  fail "TUI TERM=dumb not refused: $line"
fi
fx=$results/setup
mkdir -p "$fx/home/.m2" "$fx/local"
line=$(TERM=xterm-256color expect "$here/setup.exp" "$results/setup.log" "$fx/home" "$fx/local" \
  "$binary" -c "$fx/new.yaml" init)
if [ $? -eq 0 ] && [ ! -e "$fx/new.yaml" ]; then
  pass "TUI setup discovers bundled candidates: $line"
else
  fail "TUI setup: $line"
fi
exit $failed
