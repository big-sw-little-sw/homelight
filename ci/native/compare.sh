#!/usr/bin/env bash
# Runs the CLI comparison suite against one lighten command and writes a transcript.
#
#   ci/native/compare.sh <transcript> <command...>
#
# Each step records exit code, stdout and stderr; the run ends with a listing of the resulting
# fixture tree. The fixture path is replaced by ROOT, so transcripts from the JVM and native
# builds, on any machine, compare as plain text. The path is fixed so the transcript never depends on
# where it ran.
# Needs bash and GNU find.
set -u

transcript=$1; shift
cmd=("$@")
root=/tmp/lighten-compare
R=$root/fx
rm -rf "$root"

mkdir -p "$R/home/cache-a/sub" "$R/home/cache-b" "$R/local/cache-b" "$R/local/cache-c" "$R/archive" "$R/local/.staging"
echo a > "$R/home/cache-a/file.txt"
echo nested > "$R/home/cache-a/sub/n.txt"
echo b-src > "$R/home/cache-b/src.txt"
echo b-tgt > "$R/local/cache-b/tgt.txt"
ln -s "$R/local/cache-c" "$R/home/cache-c"
cat > "$R/config.json" <<EOF
{
  "lighten": {
    "target-root": "$R/local",
    "staging-root": "$R/local/.staging",
    "relocations": [
      {"source-path": "$R/home/cache-a", "target-path": "$R/local/cache-a"},
      {
        "source-path": "$R/home/cache-b",
        "target-path": "$R/local/cache-b",
        "when-source-and-target-directories-exist": "adopt",
        "when-adopting-target": "archive-source",
        "archive-root": "$R/archive"
      },
      {"source-path": "$R/home/cache-c", "target-path": "$R/local/cache-c"}
    ]
  }
}
EOF
cat > "$R/conflict.json" <<EOF
{"lighten": {"target-root": "$R/local", "relocations": [
  {"source-path": "$R/home/cache-b", "target-path": "$R/local/cache-b"}
]}}
EOF
printf '{"lighten": {"target-root": "unclosed\n' > "$R/malformed.json"
cat > "$R/unknown-key.json" <<EOF
{"lighten": {"target-root": "$R/local", "relocations": [
  {"source-path": "$R/home/cache-a", "target-path": "$R/local/cache-a", "existing": "move"}
]}}
EOF
cat > "$R/bad-enum.json" <<EOF
{"lighten": {"target-root": "$R/local", "relocations": [
  {"source-path": "$R/home/cache-a", "target-path": "$R/local/cache-a", "when-only-target-exists": "sometimes"}
]}}
EOF
# The suggestion list sits directly under lighten; the old discovery object is an unknown key.
cat > "$R/suggestion-list.json" <<EOF
{"lighten": {"target-root": "$R/local", "suggestion-list": "$R/suggestions.json", "relocations": [
  {"source-path": "$R/home/cache-a", "target-path": "$R/local/cache-a"}
]}}
EOF
cat > "$R/old-discovery.json" <<EOF
{"lighten": {"target-root": "$R/local", "discovery": {"suggestion-list": "$R/suggestions.json"}}}
EOF
cat > "$R/relative-path.json" <<EOF
{"lighten": {"target-root": "local", "relocations": [{"source-path": "$R/home/cache-a"}]}}
EOF
cat > "$R/missing-key.json" <<EOF
{"lighten": {"relocations": [{"source-path": "$R/home/cache-a"}]}}
EOF

out=$root/transcript
: > "$out"
step() {
  local name=$1; shift
  echo "### $name" >> "$out"
  "${cmd[@]}" "$@" > "$root/stdout" 2> "$root/stderr" < /dev/null
  echo "exit=$?" >> "$out"
  echo "--- stdout" >> "$out"; cat "$root/stdout" >> "$out"
  # A configuration error prints its message; a file Lighten cannot read adds how to fix it. Stack
  # frames from an unexpected failure differ between the JVM and native builds, so they are dropped
  # and only the exception line is compared.
  echo "--- stderr" >> "$out"; grep -v -E $'^\t(at |\\.\\.\\. [0-9]+ more)' "$root/stderr" | head -c 2000 >> "$out"
}

step help --help
step version --version
step status-help status --help
step plan-help plan --help
step apply-help apply --help
step init-help init --help
# The guide is a resource; a native build without it registered would print nothing.
step guide guide
step bogus-option --bogus
step bad-delay --debug-step-delay-ms 60001 status --json
step missing-config -c "$R/nope.json" status --json
step malformed -c "$R/malformed.json" plan --json
step unknown-key -c "$R/unknown-key.json" plan --json
step bad-enum -c "$R/bad-enum.json" plan --json
step missing-key -c "$R/missing-key.json" plan --json
step suggestion-list -c "$R/suggestion-list.json" status --json
step old-discovery -c "$R/old-discovery.json" plan --json
step relative-path -c "$R/relative-path.json" plan --json
# --config and the hidden --debug-step-delay-ms work on either side of the command name.
step config-after status --json --config "$R/config.json"
step config-after-plan plan --json -c "$R/nope.json"
step config-after-apply apply --json --yes -c "$R/conflict.json"
step config-after-init init -c "$R/malformed.json"
step config-both-sides -c "$R/malformed.json" status --json -c "$R/config.json"
step delay-before --debug-step-delay-ms 5 -c "$R/config.json" status --json
step delay-after -c "$R/config.json" status --json --debug-step-delay-ms 5
step bad-delay-after status --json --debug-step-delay-ms -1
step bad-delay-text --debug-step-delay-ms abc status --json
step status-json -c "$R/config.json" status --json
step plan-json -c "$R/config.json" plan --json
step plan-override -c "$R/config.json" plan --json --source-path "$R/home/other" --target-path "$R/local/other"
step conflict-apply -c "$R/conflict.json" apply --json --yes
step apply-no-yes -c "$R/config.json" apply --json
step tui-non-tty -c "$R/config.json" status
step init-existing -c "$R/config.json" init
step config-existing -c "$R/config.json" config
step config-malformed -c "$R/malformed.json" config
step apply-yes -c "$R/config.json" apply --json --yes
step status-after -c "$R/config.json" status --json
step plan-after -c "$R/config.json" plan --json
step apply-again -c "$R/config.json" apply --json --yes

echo "### filesystem" >> "$out"
(cd "$R" && find . \( -type l -printf '%M %p -> %l\n' \) -o -printf '%M %p\n' | LC_ALL=C sort) >> "$out"

sed "s#$R#ROOT#g" "$out" > "$transcript"
rm -rf "$root"
echo "compare: $(grep -c '^### ' "$transcript") sections, exits: $(grep '^exit=' "$transcript" | cut -d= -f2 | tr '\n' ' ')"
