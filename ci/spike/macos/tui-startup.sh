#!/bin/bash
# Time from spawn to first HOMELIGHT frame, and from spawn to process exit after 'q', N runs per variant.
S=/private/tmp/claude-501/-Users-jsiva-sw-code-homelight/785981ef-3d18-4954-b06f-24e1a7f65e8a/scratchpad
WT=/Users/jsiva/sw/code/homelight/.claude/worktrees/agent-a7aa57028951b9cca
cd "$S" || exit 1
CP="$WT/target/classes:$(cat $S/cp.txt)"
CFG=$S/fx-native/conflict.yaml
cat > $S/startup.exp <<'EOF'
set timeout 20
log_user 0
set stty_init "rows 40 cols 120"
set env(TERM) xterm-256color
set start [clock milliseconds]
eval spawn -noecho $argv
expect {
  "HOMELIGHT" { set first [expr {[clock milliseconds]-$start}] }
  timeout { puts "timeout"; exit 1 }
}
send "q"
expect eof
set total [expr {[clock milliseconds]-$start}]
puts "$first $total"
EOF
variant() {
  local name=$1; shift
  local firsts=() totals=()
  for i in 1 2 3 4 5 6 7 8 9 10; do
    read f t < <(expect $S/startup.exp "$@")
    firsts+=($f); totals+=($t)
  done
  echo "$name first-frame ms: ${firsts[*]}"
  echo "$name spawn-to-exit ms: ${totals[*]}"
}
variant native-jni "$WT/target/homelight" -c $CFG status
variant native-exec "$WT/target/homelight" -Dorg.jline.terminal.provider=exec -c $CFG status
variant jvm /Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home/bin/java -cp "$CP" io.github.bigswlittlesw.homelight.cli.HomeLightCommand -c $CFG status
