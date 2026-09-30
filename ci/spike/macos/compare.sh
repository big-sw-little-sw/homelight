#!/bin/bash
# Runs the same command set against the JVM and native builds on separate but
# identically shaped fixtures, normalizes fixture roots, and diffs results.
S=/private/tmp/claude-501/-Users-jsiva-sw-code-homelight/785981ef-3d18-4954-b06f-24e1a7f65e8a/scratchpad
WT=/Users/jsiva/sw/code/homelight/.claude/worktrees/agent-a7aa57028951b9cca
JAVA=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home/bin/java
CP="$WT/target/classes:$(cat $S/cp.txt)"
MAIN=io.github.bigswlittlesw.homelight.cli.HomeLightCommand

setup_fixture() {
  local R=$1
  rm -rf "$R"; mkdir -p "$R/home/cache-a/sub" "$R/home/cache-b" "$R/local/cache-b" "$R/local/cache-c" "$R/archive" "$R/local/.staging"
  echo a > "$R/home/cache-a/file.txt"; echo nested > "$R/home/cache-a/sub/n.txt"
  echo b-src > "$R/home/cache-b/src.txt"; echo b-tgt > "$R/local/cache-b/tgt.txt"
  ln -s "$R/local/cache-c" "$R/home/cache-c"
  cat > "$R/config.yaml" <<EOF
homelight:
  target-root: $R/local
  staging-root: $R/local/.staging
  relocations:
    - source-path: $R/home/cache-a
      target-path: $R/local/cache-a
    - source-path: $R/home/cache-b
      target-path: $R/local/cache-b
      when-source-and-target-directories-exist: adopt
      when-adopting-target: archive-source
      source-archive-root: $R/archive
    - source-path: $R/home/cache-c
      target-path: $R/local/cache-c
EOF
  cat > "$R/conflict.yaml" <<EOF
homelight:
  target-root: $R/local
  relocations:
    - source-path: $R/home/cache-b
      target-path: $R/local/cache-b
EOF
  printf 'homelight:\n  target-root: [unclosed\n' > "$R/malformed.yaml"
  cat > "$R/removed.yaml" <<EOF
homelight:
  target-root: $R/local
  relocations:
    - source-path: $R/home/cache-a
      target-path: $R/local/cache-a
      existing: move
EOF
  cat > "$R/badenum.yaml" <<EOF
homelight:
  target-root: $R/local
  relocations:
    - source-path: $R/home/cache-a
      target-path: $R/local/cache-a
      when-only-target-exists: sometimes
EOF
}

run_all() {
  local R=$1; shift
  local OUT=$1; shift
  local -a CMD=("$@")
  : > "$OUT"
  step() {
    local name=$1; shift
    echo "### $name" >> "$OUT"
    "${CMD[@]}" "$@" > "$OUT.o" 2> "$OUT.e" < /dev/null
    echo "exit=$?" >> "$OUT"
    echo "--- stdout" >> "$OUT"; cat "$OUT.o" >> "$OUT"
    echo "--- stderr" >> "$OUT"; head -c 2000 "$OUT.e" >> "$OUT"
  }
  envstep() {
    local name=$1; shift
    echo "### $name" >> "$OUT"
    env HOMELIGHT_TARGET_ROOT="$R/local" "${CMD[@]}" "$@" > "$OUT.o" 2> "$OUT.e" < /dev/null
    echo "exit=$?" >> "$OUT"
    echo "--- stdout" >> "$OUT"; cat "$OUT.o" >> "$OUT"
    echo "--- stderr" >> "$OUT"; head -c 2000 "$OUT.e" >> "$OUT"
  }
  step help --help
  step version --version
  step status-help status --help
  step plan-help plan --help
  step apply-help apply --help
  step init-help init --help
  step bogus-option --bogus
  step missing-config -c "$R/nope.yaml" status --json
  step malformed -c "$R/malformed.yaml" plan --json
  step removed-setting -c "$R/removed.yaml" plan --json
  step bad-enum -c "$R/badenum.yaml" plan --json
  step status-json -c "$R/config.yaml" status --json
  step plan-json -c "$R/config.yaml" plan --json
  step plan-override -c "$R/config.yaml" plan --json --source-path "$R/home/other" --target-path "$R/local/other"
  envstep plan-env-override -c "$R/conflict.yaml" plan --json
  step conflict-apply -c "$R/conflict.yaml" apply --json --yes
  step apply-no-yes -c "$R/config.yaml" apply --json
  step tui-non-tty -c "$R/config.yaml" status
  step init-existing -c "$R/config.yaml" init
  step apply-yes -c "$R/config.yaml" apply --json --yes
  step status-after -c "$R/config.yaml" status --json
  step plan-after -c "$R/config.yaml" plan --json
  step apply-again -c "$R/config.yaml" apply --json --yes
  echo "### filesystem" >> "$OUT"
  (cd "$R" && find . -print0 | sort -z | xargs -0 ls -ld | awk '{print $1, $NF, $(NF-1), $(NF-2)}' | sed 's/[0-9][0-9]*:[0-9][0-9]//') >> "$OUT"
  sed -i '' "s#$R#ROOT#g" "$OUT"
  rm -f "$OUT.o" "$OUT.e"
}

setup_fixture "$S/fx-jvm"
run_all "$S/fx-jvm" "$S/out-jvm.txt" "$JAVA" -cp "$CP" "$MAIN"
setup_fixture "$S/fx-native"
run_all "$S/fx-native" "$S/out-native.txt" "$WT/target/homelight"
diff "$S/out-jvm.txt" "$S/out-native.txt" && echo "IDENTICAL"
