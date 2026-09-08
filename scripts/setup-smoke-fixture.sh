#!/usr/bin/env bash
set -euo pipefail

fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/homelight-smoke.XXXXXX")"
home_root="$fixture_root/home"
target_root="$fixture_root/local"
config_path="$fixture_root/config.yaml"

mkdir -p "$home_root/cache/nested" "$home_root/tool-cache" "$fixture_root/external"
printf 'cache entry\n' > "$home_root/cache/entry"
printf 'nested entry\n' > "$home_root/cache/nested/entry"
printf 'tool cache entry\n' > "$home_root/tool-cache/entry"
printf 'external entry\n' > "$fixture_root/external/entry"
ln -s "$fixture_root/external/entry" "$home_root/cache/external-link"

printf '%s\n' \
  'homelight:' \
  "  target-root: $target_root" \
  '  relocations:' \
  "    - source-path: $home_root/cache" \
  "      target-path: $target_root/cache" \
  '      existing: move' \
  "    - source-path: $home_root/tool-cache" \
  "      target-path: $target_root/tool-cache" \
  '      existing: move' \
  > "$config_path"

printf 'Smoke fixture: %s\n' "$fixture_root"
printf 'Configuration: %s\n\n' "$config_path"
printf 'Plan:\n'
printf '  mvn -q exec:java -Dexec.args="plan --config %s"\n' "$config_path"
printf 'Apply:\n'
printf '  mvn -q exec:java -Dexec.args="apply --yes --config %s"\n' "$config_path"
