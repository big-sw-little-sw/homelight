# #32b temporary-fixture walkthrough

Status: proposed UX, awaiting coordinator review and human acceptance.

Run `bash scripts/setup-smoke-fixture.sh`. Use its **First-run walkthrough**
and the printed paths beneath `first-run`, not the returning-user apply exercise
printed later. The script creates only a new temporary fixture. Keep it for the
walkthrough; rerun the script for another fresh, absent configuration path.

1. Set the terminal to 80×24. Launch the printed `homelight init --config ...`
   command. At Storage locations, use Ctrl-U to replace the default source root
   with the printed temporary `first-run/home`, then Tab to enter the printed
   `first-run/local` target. Leave the optional shared list blank initially.
2. Enter opens Relocations. `a` opens the existing manual Row details. Enter
   `manual-cache`, then Escape returns to the table. `b` opens Browse candidates.
   Maven's `.m2` and absent candidates should offer `[ ]` and Add, without a
   routine metadata label in the list. Absent paths say “Not created yet” in
   details. Inaccessible/unknown paths remain labelled and ineligible.
3. Enter on an app expands/collapses it without adding anything. Arrow keys or
   j/k move focus. Space or `a` adds an eligible `[ ]` directory directly in the
   list; `[x]` means it is already in the draft. Focus stays on that row. `e`
   opens its existing editor; pressing Add again does not duplicate or remove it.
   Enter on a path remains available for full inspection. Edit a target and
   policy, then Escape returns to Relocations. Nothing has been saved or applied.
   Also try a missing candidate such as `.vscode-server`: inspect its creation
   explanation, add it directly, edit its target, then Refresh. Its checkmark
   and edits should remain. In Row details, “When only target exists” defaults
   to Prompt; Adopt target is an explicit choice, not inferred by discovery.
4. From the table use `e` for Locations. Enter the printed `first-run/shared.yaml`
   in the third field. Return to the table and browser. Inspect `.m2` to see
   conflicting advice with both app/source attributions; `.cache/uv` includes
   omitted advice. Find ungrouped `team-cache` and add it. Use `u` to reveal
   `quiet-cache`; add it, then hide the remaining usually-unnecessary entries.
   The selected entry stays visible. Focus, app expansion and the draft checkmark are distinct.
5. Add `.local/share/uv`, then try adding `.local/share/uv/tools`. The error names
   the overlap and keeps previous choices. Inspect all reasons, paths and source
   diagnostics with the indicated scrolling keys. Resize to 120×30 and back,
   checking focus, wrapping, scrollbars and contextual shortcuts.
6. Edit `team-cache`'s target. Run the printed `cp` command to replace only the
   temporary shared file with `shared-refreshed.yaml`. Press `r` in the browser.
   The selected row and target survive; details label former advice as historical.
   `new-cache` remains unselected. Editing roots explicitly re-resolves relative
   rows; clearing the shared field returns to bundled-only discovery.
7. For an input failure, enter an absent YAML path under the temporary fixture.
   Browse, then `i` opens source diagnostics. Manual Add, Validate and Save remain
   available. A syntactically valid unavailable list location may still be saved.
8. `q` in the table/browser opens discard confirmation. Escape keeps the draft;
   Enter discards it. Reopen with `i` and confirm the old roots, list and rows are
   gone. Escape from Locations cancels setup; it never exits the application.
9. On a fresh draft, save one manual and one candidate row with `s` from the
   table. Workspace opens. Exit with `q` without applying. Inspect the YAML:
   only chosen rows and the optional shared location persist. Source directories
   and payloads remain in place; target relocations were not created. Selected
   missing source paths must still be absent. Do not Apply during this walkthrough.

Repeat at 120×30, resizing down and back. The human acceptance gate includes the
wording, navigation, focus, discoverability and readability, not just saved data.
New guidance belongs in `docs/tui-design.md` only after that acceptance.

## Reproduce automated terminal evidence

With Java 25 selected:

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 25)" # macOS; use your Java 25 installation elsewhere
export PATH="$JAVA_HOME/bin:$PATH"
mvn -o clean verify
python3 docs/research/session-b32b/pty-check.py
```

Use the same Java 25 environment when launching `./homelight` for the manual
walkthrough. An installed Java 25 does not guarantee Maven selects it by default.

The production CLI journey uses the real bundled resource. Additional journeys
use `CandidateSetupPty`, a test-only launcher for the same application and JLine
renderer with the adopted nested fixtures and a controlled non-interruptible
shared reader. This exercises stalled-input editing, save, discard, reopening and
late completion without mounting or touching NFS. The test seam is absent from
the production CLI. PTY fixtures are created under temporary roots and removed
by the harness after each successful journey.

The harness records rendered terminal cells and checks exit/cursor/alternate-screen
and supported termios restoration. It is not a screenshot of a particular terminal
emulator or a visual contrast/font assessment. JLine normalizes speed fields and
unused control slots on macOS, as in the accepted D4 harness.
