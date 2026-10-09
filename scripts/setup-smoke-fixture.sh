#!/usr/bin/env bash
set -euo pipefail

fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/lighten-smoke.XXXXXX")"
fixture_root="$(cd "$fixture_root" && pwd -P)"
home_root="$fixture_root/home"
target_root="$fixture_root/local"
config_path="$fixture_root/config.json"
first_run_root="$fixture_root/first-run"
first_run_config="$first_run_root/new/config.json"
first_run_source="$first_run_root/home/manual-cache"
first_run_target="$first_run_root/local/manual-cache"
candidate_list="$first_run_root/shared.json"

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
mkdir -p "$first_run_source"
printf 'manual setup entry\n' > "$first_run_source/entry"
mkdir -p "$first_run_root/home/.m2" "$first_run_root/home/.cache/uv" \
  "$first_run_root/home/.local/share/uv/tools" "$first_run_root/home/team-cache" \
  "$first_run_root/home/quiet-cache"
cat > "$candidate_list" <<'EOF'
{
  "apps": [
    {
      "name": "Team build tools",
      "directories": [
        {"path": ".m2", "advice": "usually-unnecessary", "reason": "Fixture advice conflicts with the bundled recommendation."},
        {"path": ".cache/uv", "reason": "Omitted advice remains distinct from the bundled recommendation."}
      ]
    }
  ],
  "directories": [
    {"path": "team-cache", "advice": "consider", "reason": "Select this fixture directory, edit its target, then refresh."},
    {"path": "quiet-cache", "advice": "usually-unnecessary", "reason": "Reveal this directory explicitly; advice does not establish safety."}
  ]
}
EOF
printf '%s\n' '{"directories": [{"path": "new-cache"}]}' > "$first_run_root/shared-refreshed.json"

cat > "$config_path" <<EOF
{
  "lighten": {
    "target-root": "$target_root",
    "staging-root": "$target_root/.lighten-staging",
    "relocations": [
      {"source-path": "$home_root/converged-cache", "target-path": "$target_root/converged-cache"},
      {"source-path": "$home_root/stage-cache", "target-path": "$target_root/stage-cache"},
      {
        "source-path": "$home_root/adopt-cache",
        "target-path": "$target_root/adopt-cache",
        "when-source-and-target-directories-exist": "adopt",
        "when-adopting-target": "discard-source"
      },
      {
        "source-path": "$home_root/leave-unchanged-cache",
        "target-path": "$target_root/leave-unchanged-cache",
        "when-source-and-target-directories-exist": "leave-unchanged"
      },
      {
        "source-path": "$home_root/discard-cache",
        "target-path": "$target_root/discard-cache",
        "when-source-and-target-directories-exist": "discard"
      },
      {
        "source-path": "$home_root/conflict-cache",
        "target-path": "$target_root/conflict-cache",
        "when-source-and-target-directories-exist": "prompt"
      }
    ]
  }
}
EOF

printf 'Smoke fixture: %s\n' "$fixture_root"
printf 'Configuration: %s\n\n' "$config_path"
printf 'First-run configuration path (intentionally absent): %s\n' "$first_run_config"
printf 'First-run source: %s\nFirst-run target: %s\n\n' "$first_run_source" "$first_run_target"
printf 'First run:\n'
printf '  ./lighten --config %q\n' "$first_run_config"
printf '  The Workspace says there is no configuration file yet. Press i to open Configuration.\n'
printf '  (./lighten config --config %q opens it directly.)\n' "$first_run_config"
printf '  Select Storage locations and press Enter. Clear Source root with Ctrl-U, then enter:\n'
printf '    Source root: %s\n    Target root: %s\n' "$first_run_root/home" "$first_run_root/local"
printf '  Leave Suggestion list empty for the built-in list only, or use:\n    %s\n' "$candidate_list"
printf '  Esc returns to the list. a adds a relocation you type; enter: %s\n' "$first_run_source"
printf '  b opens Browse. Space adds the selected directory (○ becomes ●) or takes it out again.\n'
printf '  Space on a category or app name adds every directory under it. Enter shows which lists suggest it.\n'
printf '  u shows directories marked usually not needed; f shows only those found on this machine.\n'
printf '  Add team-cache, press e to change its target, then replace your list and press r in Browse:\n'
printf '    cp %q %q\n' "$first_run_root/shared-refreshed.json" "$candidate_list"
printf '  i shows each list in full. Esc returns to Configuration. s saves, after asking for an existing file.\n'
printf '  Saving writes only %s, then shows the Workspace and checks again.\n' "$first_run_config"
printf '  Nothing on disk changes until you press a to review and y to apply. q quits without applying.\n'
printf '  To check that quitting saves nothing: ./lighten config --config %q, then q (and y if it asks);\n' "$first_run_root/cancel.json"
printf '  test ! -e %q\n\n' "$first_run_root/cancel.json"
printf 'Every command without --json opens the same Workspace:\n'
printf '  ./lighten --config %q\n' "$config_path"
printf '  ./lighten status --config %q\n' "$config_path"
printf '  ./lighten plan --config %q\n' "$config_path"
printf '  ./lighten apply --config %q\n' "$config_path"
printf '\nSlow steps, to watch the spinner and each step as it runs:\n'
printf '  ./lighten --debug-step-delay-ms 3000 --config %q\n' "$config_path"
printf '  The delay applies to each change after you press y, not to start-up.\n'
printf '\nWalkthrough:\n'
printf '  1. The Workspace lists conflict-cache first, marked [Choose]. Select it and press Tab.\n'
printf '     Pick "Keep target, delete source" and press Enter: it is marked ●.\n'
printf '  2. Press a to review every step. Up/Down selects a relocation or a step; Details say what it does.\n'
printf '     Press n to go back, or y to apply.\n'
printf '  3. Each step shows as it runs. q during the run finishes the changes first, then exits.\n'
printf '  4. Results show whether each step worked. Press r to check again: the Workspace then shows\n'
printf '     each relocation as it is now, normally [In sync].\n'
printf '  The choice is for this apply only. To always do it, press s on the row to save it as its rule.\n'
printf '\nJSON for scripts (no screen, no delay):\n'
printf '  ./lighten status --config %q --json\n' "$config_path"
printf '  ./lighten plan --config %q --json\n' "$config_path"
printf '  ./lighten apply --config %q --json --yes\n' "$config_path"
printf '  The fresh fixture has a choice to make (conflict-cache), so apply --json prints the plan and changes nothing.\n'
printf '  --yes confirms the plan; it does not make choices. Choices on screen are not saved unless you press s.\n'
printf '\nAfter applying, check the moved directory:\n'
printf '  test -d %q && test -f %q && test -L %q && test "$(readlink %q)" = %q\n' \
  "$target_root/stage-cache" "$target_root/stage-cache/entry" "$home_root/stage-cache" \
  "$home_root/stage-cache" "$target_root/stage-cache"
printf '  test -z "$(find %q -mindepth 1 -print -quit)"\n' "$target_root/.lighten-staging"
printf '\nThen open Lighten again: every relocation is [In sync] or [Left as is].\n'
printf '  ./lighten --config %q\n' "$config_path"
printf '  If you left conflict-cache as it was, it is [Choose] again.\n'
printf '\nRun this script again for a fresh fixture.\n'
