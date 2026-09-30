#!/bin/bash
# Runs inside a container with hyperfine + GNU time. Usage: bench-linux.sh <native-binary> <label> [jvm-launcher]
BIN=$1; LABEL=$2; JVM=${3:-}
mkdir -p /work/fx/home/a /work/fx/home/b /work/fx/local/b; echo a > /work/fx/home/a/f; echo b > /work/fx/home/b/f; echo t > /work/fx/local/b/t
printf 'homelight:\n  target-root: /work/fx/local\n  relocations:\n    - source-path: /work/fx/home/a\n      target-path: /work/fx/local/a\n    - source-path: /work/fx/home/b\n      target-path: /work/fx/local/b\n' > /work/fx/c.yaml
C=/work/fx/c.yaml
args=(-n "native --help" "$BIN --help" -n "native plan --json" "$BIN -c $C plan --json" -n "native status --json" "$BIN -c $C status --json")
[ -n "$JVM" ] && args+=(-n "jvm --help" "$JVM --help" -n "jvm plan --json" "$JVM -c $C plan --json")
hyperfine -N --warmup 3 --runs 30 --export-markdown /results/bench-$LABEL.md "${args[@]}" > /dev/null 2>&1
cat /results/bench-$LABEL.md
echo "peak RSS (KiB, GNU time -v):"
for c in "$BIN --help" "$BIN -c $C plan --json" ${JVM:+"$JVM --help"} ${JVM:+"$JVM -c $C plan --json"}; do
  echo "  $(/usr/bin/time -v $c 2>&1 >/dev/null | awk -F': ' '/Maximum resident/ {print $2}')  $c" | sed "s#$C#CFG#"
done
echo "binary size: $(stat -c %s $BIN) bytes"
