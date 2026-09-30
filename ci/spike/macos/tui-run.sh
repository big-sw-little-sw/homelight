#!/bin/bash
# Usage: tui-run.sh <jvm|native|agent> <scenario: view|apply|init>
S=/private/tmp/claude-501/-Users-jsiva-sw-code-homelight/785981ef-3d18-4954-b06f-24e1a7f65e8a/scratchpad
WT=/Users/jsiva/sw/code/homelight/.claude/worktrees/agent-a7aa57028951b9cca
cd "$S" || exit 1
which=$1; mode=$2
CP="$WT/target/classes:$(cat $S/cp.txt)"
MAIN=io.github.bigswlittlesw.homelight.cli.HomeLightCommand
case $which in
  jvm) CMD=(/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home/bin/java -cp "$CP" $MAIN) ;;
  agent) CMD=(/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java "-agentlib:native-image-agent=config-merge-dir=$S/agent-config,experimental-class-define-support" -cp "$CP" $MAIN) ;;
  native) CMD=("$WT/target/homelight") ;;
  native-exec) CMD=("$WT/target/homelight" -Dorg.jline.terminal.provider=exec -Djava.io.tmpdir=$S/jtmp) ;;
  native-ffm) CMD=("$WT/target/homelight" -Dorg.jline.terminal.provider=ffm -Djava.io.tmpdir=$S/jtmp) ;;
  *) exit 2 ;;
esac
R=$S/tui-$which-$mode
rm -rf "$R"; mkdir -p "$R/home/cache-a" "$R/local/cache-b" "$R/home/cache-b"
echo a > "$R/home/cache-a/f"; echo b > "$R/home/cache-b/f"; echo t > "$R/local/cache-b/t"
cat > "$R/config.yaml" <<EOF
homelight:
  target-root: $R/local
  relocations:
    - source-path: $R/home/cache-a
      target-path: $R/local/cache-a
EOF
cat > "$R/conflict.yaml" <<EOF
homelight:
  target-root: $R/local
  relocations:
    - source-path: $R/home/cache-a
      target-path: $R/local/cache-a
    - source-path: $R/home/cache-b
      target-path: $R/local/cache-b
EOF
case $mode in
  view) ARGS=(-c "$R/conflict.yaml" status) ;;
  apply) ARGS=(-c "$R/config.yaml" apply) ;;
  init) ARGS=(-c "$R/new.yaml" init) ;;
  *) exit 2 ;;
esac
LOG=$S/tui-$which-$mode.log
expect "$S/tui.exp" "$LOG" "$mode" "${CMD[@]}" "${ARGS[@]}"
echo "--- restore sequences: 1049h=$(grep -c $'\e\\[?1049h' "$LOG") 1049l=$(grep -c $'\e\\[?1049l' "$LOG") 25h=$(grep -c $'\e\\[?25h' "$LOG")"
echo "--- fs after:"; (cd "$R" && find . | sort)
