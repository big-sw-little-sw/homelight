#!/usr/bin/env bash
set -euo pipefail

fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/homelight-smoke.XXXXXX")"
fixture_root="$(cd "$fixture_root" && pwd -P)"
home_root="$fixture_root/home"
target_root="$fixture_root/local"
config_path="$fixture_root/config.yaml"

mkdir -p "$home_root/stage-cache/nested" \
  "$home_root/adopt-cache" "$target_root/adopt-cache" \
  "$home_root/leave-unchanged-cache" "$target_root/leave-unchanged-cache" \
  "$home_root/discard-cache" "$target_root/discard-cache" \
  "$home_root/conflict-cache" "$target_root/conflict-cache" \
  "$target_root/converged-cache" \
  "$fixture_root/external"
printf 'stage entry\n' > "$home_root/stage-cache/entry"
printf 'nested entry\n' > "$home_root/stage-cache/nested/entry"
printf 'accepted target entry\n' > "$target_root/adopt-cache/entry"
printf 'unchanged source entry\n' > "$home_root/leave-unchanged-cache/entry"
printf 'discarded source entry\n' > "$home_root/discard-cache/entry"
printf 'discarded target entry\n' > "$target_root/discard-cache/entry"
printf 'conflict source entry\n' > "$home_root/conflict-cache/entry"
printf 'conflict target entry\n' > "$target_root/conflict-cache/entry"
printf 'converged target entry\n' > "$target_root/converged-cache/entry"
ln -s "$target_root/converged-cache" "$home_root/converged-cache"
printf 'external entry\n' > "$fixture_root/external/entry"
ln -s "$fixture_root/external/entry" "$home_root/stage-cache/external-link"

printf '%s\n' \
  'homelight:' \
  "  target-root: $target_root" \
  "  staging-root: $target_root/.homelight-staging" \
  '  relocations:' \
  "    - source-path: $home_root/converged-cache" \
  "      target-path: $target_root/converged-cache" \
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
  "    - source-path: $home_root/conflict-cache" \
  "      target-path: $target_root/conflict-cache" \
  '      when-source-and-target-directories-exist: prompt' \
  > "$config_path"

printf 'Smoke fixture: %s\n' "$fixture_root"
printf 'Configuration: %s\n\n' "$config_path"
printf 'Status (Interactive TUI):\n'
printf '  ./homelight status --config %s\n' "$config_path"
printf '\nStatus (JSON):\n'
printf '  ./homelight status --config %s --json\n' "$config_path"
printf '\nPlan:\n'
printf '  ./homelight plan --config %s\n' "$config_path"
printf '\nApply:\n'
printf '  ./homelight apply --yes --config %s\n' "$config_path"
printf '\nAfter apply, verify staged publication:\n'
printf '  test -d %q && test -f %q && test -d %q\n' \
  "$target_root/stage-cache" "$target_root/stage-cache/entry" "$home_root/stage-cache"
printf '  test -z "$(find %q -mindepth 1 -print -quit)"\n' "$target_root/.homelight-staging"
printf '\n#13 publishes the target safely. #14 will replace stage-cache with its symlink.\n'
