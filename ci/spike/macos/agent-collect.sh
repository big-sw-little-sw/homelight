#!/bin/bash
# Runs representative non-TUI commands under the native-image tracing agent.
S=/private/tmp/claude-501/-Users-jsiva-sw-code-homelight/785981ef-3d18-4954-b06f-24e1a7f65e8a/scratchpad
WT=/Users/jsiva/sw/code/homelight/.claude/worktrees/agent-a7aa57028951b9cca
CP="$WT/target/classes:$(cat $S/cp.txt)"
J=(/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home/bin/java "-agentlib:native-image-agent=config-merge-dir=$S/agent-config,experimental-class-define-support" -cp "$CP" io.github.bigswlittlesw.homelight.cli.HomeLightCommand)
# Reuse compare.sh's fixture builder.
eval "$(sed -n '/^setup_fixture()/,/^}/p' $S/compare.sh)"
R=$S/fx-agent
setup_fixture "$R"
run() { "${J[@]}" "$@" > /dev/null 2>&1 < /dev/null; echo "exit=$? :: $*"; }
run --help
run --version
run status --help
run -c "$R/nope.yaml" status --json
run -c "$R/malformed.yaml" plan --json
run -c "$R/removed.yaml" plan --json
run -c "$R/badenum.yaml" plan --json
run -c "$R/config.yaml" status --json
run -c "$R/config.yaml" plan --json
run -c "$R/config.yaml" plan --json --source-path "$R/home/x" --target-path "$R/local/x"
HOMELIGHT_TARGET_ROOT=$R/local run -c "$R/conflict.yaml" plan --json
run -c "$R/conflict.yaml" apply --json --yes
run -c "$R/config.yaml" apply --json --yes
run -c "$R/config.yaml" plan --json
