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
printf 'Top-level command (opens Status):\n'
printf '  ./homelight --config %q\n' "$config_path"
printf '\nIndividual TUI commands (apply opens Plan for review):\n'
printf '  ./homelight status --config %q\n' "$config_path"
printf '  ./homelight plan --config %q\n' "$config_path"
printf '  ./homelight apply --config %q\n' "$config_path"
printf '\nSlow execution for inspecting spinners, action following, and progress:\n'
printf '  ./homelight --debug-step-delay-ms 3000 --config %q\n' "$config_path"
printf '  ./homelight status --debug-step-delay-ms 3000 --config %q\n' "$config_path"
printf '  ./homelight plan --debug-step-delay-ms 3000 --config %q\n' "$config_path"
printf '  ./homelight apply --debug-step-delay-ms 3000 --config %q\n' "$config_path"
printf '  The delay applies to mutating actions after confirmation, not startup.\n'
printf '\nWalkthrough:\n'
printf '  1. From Status, press 2 to open Plan.\n'
printf '  2. Select conflict-cache; press Right or Tab to enter its choices.\n'
printf '     Choose "Adopt target and discard source" with Space or Enter.\n'
printf '  3. Press 3 from either Plan pane to open Apply confirmation.\n'
printf '     Inspect actions with Up/Down. Press n or Esc to cancel, or y to apply.\n'
printf '  4. The cursor and details follow running actions. Leaving is disabled during execution.\n'
printf '  5. Results stay visible. Press Enter for refreshed Status, or r to re-plan.\n'
printf '     An unchanged plan shows "No changes to apply" without another confirmation.\n'
printf '\nJSON automation (no TUI or visual delay):\n'
printf '  ./homelight status --config %q --json\n' "$config_path"
printf '  ./homelight plan --config %q --json\n' "$config_path"
printf '  ./homelight apply --config %q --json --yes\n' "$config_path"
printf '  The fresh fixture deliberately has an unresolved conflict; JSON apply refuses it.\n'
printf '  --yes does not resolve decisions. TUI choices are session-local, not saved to YAML.\n'
printf '\nAfter apply, verify converged staged publication:\n'
printf '  test -d %q && test -f %q && test -L %q && test "$(readlink %q)" = %q\n' \
  "$target_root/stage-cache" "$target_root/stage-cache/entry" "$home_root/stage-cache" \
  "$home_root/stage-cache" "$target_root/stage-cache"
printf '  test -z "$(find %q -mindepth 1 -print -quit)"\n' "$target_root/.homelight-staging"
printf '\nAfter completing the walkthrough, reopen Apply to inspect the unchanged plan:\n'
printf '  ./homelight apply --config %q\n' "$config_path"
printf '  If you left conflict-cache unchanged instead of adopting it, a new session asks again.\n'
printf '\nRun this script again to create a fresh fixture for another full walkthrough.\n'
