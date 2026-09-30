#!/bin/bash
# Runs inside a test container. Usage: run-tests.sh <binary-path> <label> [full|tui|smoke]
# Mounts: /scripts (ro), /results (rw). Writes /results/<label>.*
BIN=$1; LABEL=$2; LEVEL=${3:-full}
R=/results/$LABEL
mkdir -p $R
{
  echo "distro=$( . /etc/os-release; echo "$PRETTY_NAME")"
  echo "arch=$(uname -m) kernel=$(uname -r)"
  echo "glibc=$(getconf GNU_LIBC_VERSION 2>/dev/null || ldd --version 2>&1 | head -1)"
  echo "tmp_mount=$(grep ' /tmp ' /proc/mounts || echo 'none (rootfs)')"
  "$BIN" --version > /dev/null 2> $R/version.err; echo "version_exit=$? $("$BIN" --version 2>&1 | head -1)"
  "$BIN" --help > /dev/null 2>&1; echo "help_exit=$?"
  mkdir -p /work/fxq
  printf 'homelight:\n  target-root: /work/fxq/local\n  relocations:\n    - source-path: /work/fxq/home/a\n      target-path: /work/fxq/local/a\n' > /work/fxq/c.yaml
  mkdir -p /work/fxq/home/a /work/fxq/local; echo x > /work/fxq/home/a/f
  "$BIN" -c /work/fxq/c.yaml plan --json > $R/plan.json 2>&1; echo "plan_exit=$? plan_bytes=$(wc -c < $R/plan.json)"
} > $R/smoke.txt 2>&1
cat $R/smoke.txt
[ "$LEVEL" = smoke ] && exit 0
if [ "$LEVEL" = full ]; then
  WORK=/work bash /scripts/compare-linux.sh native "$BIN" > /dev/null 2>&1
  cp /work/out-native.txt $R/out-native.txt
  echo "compare: $(grep -c '^### ' $R/out-native.txt) steps; exits: $(grep '^exit=' $R/out-native.txt | tr '\n' ' ')"
fi
export HOME=/work
for prov in default exec; do
  for term in xterm-256color screen-256color tmux-256color linux vt100 dumb; do
    if [ $prov = exec ]; then P="-Dorg.jline.terminal.provider=exec"; else P=""; fi
    [ -n "${ONLY_PROV:-}" ] && [ "$prov" != "$ONLY_PROV" ] && continue
    [ -n "${ONLY_TERM:-}" ] && [ "$term" != "$ONLY_TERM" ] && continue
    F=/work/tui-$prov-$term; rm -rf $F; mkdir -p $F/home/a $F/home/b $F/local/b; echo a > $F/home/a/f; echo b > $F/home/b/f; echo t > $F/local/b/t
    printf 'homelight:\n  target-root: %s/local\n  relocations:\n    - source-path: %s/home/a\n      target-path: %s/local/a\n    - source-path: %s/home/b\n      target-path: %s/local/b\n' $F $F $F $F $F > $F/c.yaml
    out=$(TERM=$term expect /scripts/tui-linux.exp $R/tui-$prov-$term.log "$BIN $P -c $F/c.yaml status" 2>&1 | tail -1)
    echo "tui provider=$prov TERM=$term $out"
  done
done
