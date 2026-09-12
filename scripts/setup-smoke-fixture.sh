#!/usr/bin/env bash
set -euo pipefail

fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/homelight-smoke.XXXXXX")"
home_root="$fixture_root/home"
target_root="$fixture_root/local"
config_path="$fixture_root/config.yaml"

mkdir -p "$home_root/stage-cache/nested" \
  "$home_root/adopt-cache" "$target_root/adopt-cache" \
  "$home_root/leave-unchanged-cache" "$target_root/leave-unchanged-cache" \
  "$home_root/discard-cache" "$target_root/discard-cache" \
  "$fixture_root/external"
printf 'stage entry\n' > "$home_root/stage-cache/entry"
printf 'nested entry\n' > "$home_root/stage-cache/nested/entry"
printf 'accepted target entry\n' > "$target_root/adopt-cache/entry"
printf 'unchanged source entry\n' > "$home_root/leave-unchanged-cache/entry"
printf 'discarded source entry\n' > "$home_root/discard-cache/entry"
printf 'discarded target entry\n' > "$target_root/discard-cache/entry"
printf 'external entry\n' > "$fixture_root/external/entry"
ln -s "$fixture_root/external/entry" "$home_root/stage-cache/external-link"

printf '%s\n' \
  'homelight:' \
  "  target-root: $target_root" \
  '  relocations:' \
  "    - source-path: $home_root/stage-cache" \
  "      target-path: $target_root/stage-cache" \
  "    - source-path: $home_root/adopt-cache" \
  "      target-path: $target_root/adopt-cache" \
  '      when-source-and-target-directories-exist: adopt' \
  '      when-adopting-target: discard-source' \
  "    - source-path: $home_root/leave-unchanged-cache" \
  "      target-path: $target_root/leave-unchanged-cache" \
  '      when-source-and-target-directories-exist: leave-unchanged' \
  "    - source-path: $home_root/discard-cache" \
  "      target-path: $target_root/discard-cache" \
  '      when-source-and-target-directories-exist: discard' \
  > "$config_path"

printf 'Smoke fixture: %s\n' "$fixture_root"
printf 'Configuration: %s\n\n' "$config_path"
printf 'Plan:\n'
printf '  mvn -q exec:java -Dexec.args="plan --config %s"\n' "$config_path"
printf '\nThis fixture is for `plan`: staged publication execution arrives with ticket #13.\n'
