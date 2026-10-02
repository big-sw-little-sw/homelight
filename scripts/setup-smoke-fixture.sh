#!/usr/bin/env bash
set -euo pipefail

fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/homelight-smoke.XXXXXX")"
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
  "homelight": {
    "target-root": "$target_root",
    "staging-root": "$target_root/.homelight-staging",
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
printf 'First-run walkthrough:\n'
printf '  ./homelight init --config %q\n' "$first_run_config"
printf '  Source root defaults to your home directory. Clear it with Ctrl-U, then enter:\n'
printf '    Source root: %s\n    Target root: %s\n' "$first_run_root/home" "$first_run_root/local"
printf '  Leave Shared candidate list blank for bundled-only discovery, or use:\n    %s\n' "$candidate_list"
printf '  Press Enter for Relocations. a adds a manual row; enter: manual-cache\n'
printf '  Esc returns to the table. b browses candidates; Enter expands an app or inspects a directory.\n'
printf '  Space or a adds the focused [ ] directory directly in the list; [x] means already in the draft.\n'
printf '  e edits an existing draft row. Enter still inspects; app headings never add children.\n'
printf '  u reveals/hides usually-unnecessary directories. In-draft rows remain visible.\n'
printf '  Add team-cache, edit its target, then replace the temporary list and press r in the browser:\n'
printf '    cp %q %q\n' "$first_run_root/shared-refreshed.json" "$candidate_list"
printf '  The selected row and edits remain, with historical attribution. i shows full source diagnostics.\n'
printf '  Esc steps back to the table. e edits locations; q confirms discard. v validates; s saves.\n'
printf '  Save creates only %s and opens the workspace. Press 2 to review, Esc to cancel;\n' "$first_run_config"
printf '  no relocation is applied until lowercase y confirms a reviewed plan.\n'
printf '  For this candidate walkthrough, exit with q from Workspace without applying.\n'
printf '  To verify cancellation instead, run ./homelight status --config %q, press i, then Esc;\n' "$first_run_root/cancel.json"
printf '  test ! -e %q\n\n' "$first_run_root/cancel.json"
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
printf '  1. From Workspace, press 2 to open Review.\n'
printf '  2. Select conflict-cache; press Right or Tab to enter its choices.\n'
printf '     Choose "Adopt target and discard source" with Space or Enter.\n'
printf '  3. Press 2 or a from either Workspace pane to open Review confirmation.\n'
printf '     Inspect actions with Up/Down. Press n or Esc to cancel, or y to apply.\n'
printf '  4. The cursor and details follow running actions. Leaving is disabled during execution.\n'
printf '  5. Results stay visible. Press Enter for refreshed Workspace, or r to re-plan.\n'
printf '     An unchanged plan shows "No changes to apply" without another confirmation.\n'
printf '\nJSON automation (no TUI or visual delay):\n'
printf '  ./homelight status --config %q --json\n' "$config_path"
printf '  ./homelight plan --config %q --json\n' "$config_path"
printf '  ./homelight apply --config %q --json --yes\n' "$config_path"
printf '  The fresh fixture deliberately has an unresolved conflict; JSON apply refuses it.\n'
printf '  --yes does not resolve decisions. TUI choices are session-local, not saved to the configuration.\n'
printf '\nAfter apply, verify converged staged publication:\n'
printf '  test -d %q && test -f %q && test -L %q && test "$(readlink %q)" = %q\n' \
  "$target_root/stage-cache" "$target_root/stage-cache/entry" "$home_root/stage-cache" \
  "$home_root/stage-cache" "$target_root/stage-cache"
printf '  test -z "$(find %q -mindepth 1 -print -quit)"\n' "$target_root/.homelight-staging"
printf '\nAfter completing the walkthrough, reopen Apply to inspect the unchanged plan:\n'
printf '  ./homelight apply --config %q\n' "$config_path"
printf '  If you left conflict-cache unchanged instead of adopting it, a new session asks again.\n'
printf '\nRun this script again to create a fresh fixture for another full walkthrough.\n'
