#!/usr/bin/env bash
set -euo pipefail

fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/homelight-smoke.XXXXXX")"
home_root="$fixture_root/home"
target_root="$fixture_root/local"
config_path="$fixture_root/config.yaml"

mkdir -p "$home_root/move-cache/nested" \
  "$home_root/adopt-cache" "$target_root/adopt-cache" \
  "$home_root/preserve-cache" \
  "$home_root/discard-cache" "$target_root/discard-cache" \
  "$fixture_root/external"
printf 'move entry\n' > "$home_root/move-cache/entry"
printf 'nested entry\n' > "$home_root/move-cache/nested/entry"
printf 'accepted target entry\n' > "$target_root/adopt-cache/entry"
printf 'preserved source entry\n' > "$home_root/preserve-cache/entry"
printf 'discarded source entry\n' > "$home_root/discard-cache/entry"
printf 'discarded target entry\n' > "$target_root/discard-cache/entry"
printf 'external entry\n' > "$fixture_root/external/entry"
ln -s "$fixture_root/external/entry" "$home_root/move-cache/external-link"

printf '%s\n' \
  'homelight:' \
  "  target-root: $target_root" \
  '  relocations:' \
  "    - source-path: $home_root/move-cache" \
  "      target-path: $target_root/move-cache" \
  '      existing: move' \
  "    - source-path: $home_root/adopt-cache" \
  "      target-path: $target_root/adopt-cache" \
  '      existing: adopt' \
  "    - source-path: $home_root/preserve-cache" \
  "      target-path: $target_root/preserve-cache" \
  '      existing: preserve' \
  "    - source-path: $home_root/discard-cache" \
  "      target-path: $target_root/discard-cache" \
  '      existing: discard' \
  > "$config_path"

printf 'Smoke fixture: %s\n' "$fixture_root"
printf 'Configuration: %s\n\n' "$config_path"
printf 'Plan:\n'
printf '  mvn -q exec:java -Dexec.args="plan --config %s"\n' "$config_path"
printf 'Apply:\n'
printf '  mvn -q exec:java -Dexec.args="apply --yes --config %s"\n' "$config_path"
printf '\nThe fixture demonstrates move-as-copy, explicit target adoption, preserve, and discard.\n'
