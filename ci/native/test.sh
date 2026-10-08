#!/usr/bin/env bash
# Tests a Linux native lighten binary. Runs on a Linux host or inside a test container.
#
#   ci/native/test.sh <binary> <jvm-reference> <smoke|cli|full> [results-dir]
#
# smoke: --version and plan --json on a small fixture.
# cli:   smoke, then the compare.sh suite diffed against the JVM transcript from build.sh, and
#        update.sh (lighten update over local HTTPS) where python3 exists.
# full:  cli, then the TUI under expect for each TERM in $TUI_TERMS, Ctrl-C quitting cleanly,
#        TERM=dumb refused with exit 2, Configuration finding a bundled candidate in Browse (setup.exp),
#        and --debug-step-delay-ms slowing a TUI apply from either side of the command name (delay.exp).
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
results=${4:-$(mktemp -d "${TMPDIR:-/tmp}/lighten-test.XXXXXX")}
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
printf '{"lighten": {"target-root": "%s/local", "relocations": [{"source-path": "%s/home/a", "target-path": "%s/local/a"}]}}\n' \
  "$fx" "$fx" "$fx" > "$fx/config.json"
if "$binary" -c "$fx/config.json" plan --json > "$results/smoke-plan.json" 2>&1 && grep -q '"relocations"' "$results/smoke-plan.json"; then
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
# update over HTTPS from a local server. The CI hosts have python3 to serve it; most containers do not.
if command -v python3 > /dev/null; then
  bash "$here/update.sh" "$binary" "$results" || failed=1
else
  echo "SKIP update over HTTPS: no python3"
fi
[ "$level" = cli ] && exit $failed

# TUI
fx=$results/tui
mkdir -p "$fx/home/a" "$fx/home/b" "$fx/local/b"
echo a > "$fx/home/a/f"; echo b > "$fx/home/b/f"; echo t > "$fx/local/b/t"
printf '{"lighten": {"target-root": "%s/local", "relocations": [{"source-path": "%s/home/a", "target-path": "%s/local/a"}, {"source-path": "%s/home/b", "target-path": "%s/local/b"}]}}\n' \
  "$fx" "$fx" "$fx" "$fx" "$fx" > "$fx/config.json"
# The TUI captures the mouse; the last mouse-tracking switch it writes must turn tracking off again.
mouse_off() { [ "$(grep -ao $'\e\\[?1000[hl]' "$1" | tail -n 1)" = $'\e[?1000l' ]; }
for term in ${TUI_TERMS:-xterm-256color screen-256color tmux-256color linux vt100}; do
  log=$results/tui-$term.log
  line=$(TERM=$term expect "$here/tui.exp" "$log" "$binary" -c "$fx/config.json" status)
  status=$?
  if [ $status -eq 0 ] && ! grep -q 'Failed to load native library' "$log" && mouse_off "$log"; then
    pass "TUI TERM=$term: $line"
  else
    fail "TUI TERM=$term: $line"
  fi
done
line=$(TERM=xterm-256color TUI_QUIT=ctrl-c expect "$here/tui.exp" "$results/tui-ctrl-c.log" "$binary" -c "$fx/config.json" status)
if [ $? -eq 0 ] && mouse_off "$results/tui-ctrl-c.log"; then
  pass "TUI Ctrl-C quits: $line"
else
  fail "TUI Ctrl-C quits: $line"
fi
line=$(TERM=dumb expect "$here/tui.exp" "$results/tui-dumb.log" "$binary" -c "$fx/config.json" status)
if [ $? -eq 3 ] && [[ $line == *exit=2* ]] && grep -q 'does not support a dumb terminal' "$results/tui-dumb.log"; then
  pass "TUI TERM=dumb refused: $line"
else
  fail "TUI TERM=dumb not refused: $line"
fi
fx=$results/setup
mkdir -p "$fx/home/.m2" "$fx/local"
line=$(TERM=xterm-256color expect "$here/setup.exp" "$results/setup.log" "$fx/home" "$fx/local" \
  "$binary" -c "$fx/new.json" init)
if [ $? -eq 0 ] && [ ! -e "$fx/new.json" ]; then
  pass "TUI Configuration browses bundled candidates: $line"
else
  fail "TUI Configuration: $line"
fi
# The hidden --debug-step-delay-ms holds each apply step, before or after the command name. Without it,
# the same apply finishes well inside one delay.
delay=1000
for placement in none before after; do
  fx=$results/delay-$placement
  mkdir -p "$fx/home/a" "$fx/local"
  echo a > "$fx/home/a/f"
  printf '{"lighten": {"target-root": "%s/local", "relocations": [{"source-path": "%s/home/a", "target-path": "%s/local/a"}]}}\n' \
    "$fx" "$fx" "$fx" > "$fx/config.json"
  case $placement in
    none)   args=(-c "$fx/config.json" apply) ;;
    before) args=(--debug-step-delay-ms $delay -c "$fx/config.json" apply) ;;
    after)  args=(-c "$fx/config.json" apply --debug-step-delay-ms $delay) ;;
  esac
  line=$(TERM=xterm-256color expect "$here/delay.exp" "$results/delay-$placement.log" "$fx/home/a" "$binary" "${args[@]}")
  status=$?
  ms=${line#applied=}
  if [ $status -eq 0 ] && { { [ $placement = none ] && [ "$ms" -lt $delay ]; } ||
      { [ $placement != none ] && [ "$ms" -ge $delay ]; }; }; then
    pass "TUI apply, delay $placement: $line"
  else
    fail "TUI apply, delay $placement: $line"
  fi
done
exit $failed
