#!/bin/bash
S=/private/tmp/claude-501/-Users-jsiva-sw-code-homelight/785981ef-3d18-4954-b06f-24e1a7f65e8a/scratchpad
WT=/Users/jsiva/sw/code/homelight/.claude/worktrees/agent-a7aa57028951b9cca
cd "$S" || exit 1
CP="$WT/target/classes:$(cat $S/cp.txt)"
N="$WT/target/homelight"
J="/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home/bin/java -cp $CP io.github.bigswlittlesw.homelight.cli.HomeLightCommand"
CFG=$S/fx-native/conflict.yaml
hyperfine -N --warmup 3 --runs 30 --export-markdown $S/bench.md \
  -n "native --help" "$N --help" \
  -n "jvm --help" "$J --help" \
  -n "native plan --json" "$N -c $CFG plan --json" \
  -n "jvm plan --json" "$J -c $CFG plan --json" \
  -n "native status --json" "$N -c $CFG status --json" \
  -n "jvm status --json" "$J -c $CFG status --json" 2>&1 | grep -E "Benchmark|Time|Range"
cat $S/bench.md
echo "--- peak RSS (bytes)"
for v in native jvm; do
  if [ $v = native ]; then C="$N"; else C="$J"; fi
  echo "$v --help: $(/usr/bin/time -l $C --help 2>&1 >/dev/null | awk '/maximum resident/ {print $1}')"
  echo "$v plan --json: $(/usr/bin/time -l $C -c $CFG plan --json 2>&1 >/dev/null | awk '/maximum resident/ {print $1}')"
done
echo "--- binary size"; /bin/ls -l "$N"
echo "--- jar + deps size"; du -ck $WT/target/homelight-1.0-SNAPSHOT.jar $(tr ':' ' ' < $S/cp.txt) | tail -1
