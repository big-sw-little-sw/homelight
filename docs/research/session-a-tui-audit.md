# Session A: TUI and proposal audit

Date: 2026-09-14. Owner ticket: [#20](https://github.com/big-sw-little-sw/homelight/issues/20).
Decision gate: agree prototype questions and priority. No redesign implemented.

Coordinator update: the user has approved the narrowed prototype priorities in
#22: readable decisions at 80x24, distinct state and recovery explanations,
preservation of working review/apply behavior, and minimal simulated first-run
setup. The 100-item comparison and new follow/skip options are deferred. #20's
priority gate is complete. The audit below records the original findings and
recommendations; its pending-gate references are historical. Approval of the
resulting workflow remains a separate human gate after the #22 walkthrough.

## Recommendation for the coordinator

Prioritize readable decisions and recovery over visual polish. Compare a combined
observation/decision workspace with the existing tabs in #22. Keep explicit review,
confirmation, guarded execution and retained results in both variants. The audit
supports testing the combined workspace; it does not establish that users prefer it.

The highest-impact defect is an invisible keyboard choice: at 80x24, moving from
the first to the second conflict resolution moves focus below the detail viewport.
At 120x30 the third choice remains below the viewport. Resizing does not lose the
choice cursor, but there is no detail scrolling to expose it. Long paths and
failure messages also prevent complete review at these sizes.

## Baseline and evidence method

- HEAD: `99bb49d2a6d6201c6b5e100f07c0f33e0b2559dc`. `git diff fd25b68 -- src
  pom.xml homelight scripts/setup-smoke-fixture.sh` is empty. The newer commit is
  documentation; the audited production implementation is still `fd25b68`.
- Existing changes in `docs/next-sessions.md`, `docs/research/homefree-comparison.md`
  and untracked agent/editor setup were preserved. No production files changed;
  no commit or push was requested or made.
- Read #20/#21/#22 and affected #15/#8/#17, including comments. Read the current
  comparison before the archive. Source checks covered the three TUI views,
  `HomeLightApp`, `TuiLauncher`, session/workflows/summaries/decisions, CLI entry
  points, planner/executor, and the existing TUI/session tests.
- Real process walkthroughs used a macOS PTY, `TERM=xterm-256color`, at 80x24 and
  120x30, including a live resize in both directions. These are agent-operated
  terminal walkthroughs, not human usability results or an SSH terminal matrix.
- Fixtures came from `scripts/setup-smoke-fixture.sh` in fresh temporary roots.
  Only those fixtures were mutated. Slow apply used the existing debug delay.
  The partial-failure fixture adds absent `first` and `third` relocations around
  `stage-cache`; a staging-root file is inserted after review.
- Java: Temurin 25.0.3; Maven 3.9.16. Maven otherwise defaults to Java 26 on this
  machine, so all verification explicitly sets `JAVA_HOME` to Java 25.
- `mvn -o clean test`: 116 tests, zero failures/errors/skips. See
  [build log](session-a/maven-test.txt). Existing tests supplement terminal
  observations; they do not prove readable layouts at narrow dimensions.

Reproduce from the repository root:

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home mvn -o clean test
python3 docs/research/session-a/terminal-audit.py
python3 docs/research/session-a/terminal-audit.py --extra
```

The [driver](session-a/terminal-audit.py) launches the actual `./homelight` command,
sends keys, resizes the PTY and creates temporary fixtures. Its dependencies are
Python's standard library and the already-installed project toolchain. Its Java,
Maven-cache and default-home paths are specific to this audit machine. Fixtures
are retained for inspection. It is an audit utility, not a production UI or a
general terminal emulator.

[Main capture](session-a/terminal-capture.txt) and
[supplemental capture](session-a/terminal-extra.txt) contain labelled observations.
The lightweight ANSI reconstruction is approximate around wide Unicode glyphs
and differential redraws; stray spacing/glyph remnants are **not** product bug
evidence. Text, clipping and focus findings were cross-checked against direct PTY
output and view/key-handler source. One supplemental 120x45 view exposes a raw
diagnostic below the supported-height viewport; it is not counted as passing
120x30 usability. `TERMINAL_RESTORED` checks emitted cursor/alternate-screen restore
sequences, not every termios flag or crash mode.

## Ranked keep/change/remove findings

Priority is audit priority, not authorization to start implementation.

| ID | Priority / treatment | Reproduction and observation | Acceptance / destination |
| --- | --- | --- | --- |
| A1 | High / change | Fresh Plan, `l`, `j` on conflict-cache. At 80x24 only the first choice row fits; the cursor disappears on the second. At 120x30 the third option is below the pane. Resize 120x30 → 80x24 → 120x30 retains focus but cannot expose it at the smaller size. | All choices and consequences keyboard-accessible; focused choice visible through navigation and resize. [#26](https://github.com/big-sw-little-sw/homelight/issues/26); layout experiment #22. |
| A2 | High / change | Status/Plan details clip full paths at both dimensions. At 80x24 list truncation is clipped again, losing the useful suffix. Plan summary becomes fragments such as `⚡` without its count/category, and the master footer ends at `a: App`. Apply migration paths fit this fixture, but select its destructive replacement with `3`, `j`, `j`: at 80x24 only the first line of `Link to` fits above the warning. The same target is readable in the Target field, so this is incomplete action detail, not proof that the destination is wholly unknowable. Partial-failure message cuts off before the staging-root suffix at 120x30. | Full affected/destination paths, choices, warnings, failure causes and essential shortcuts must remain inspectable. #26. |
| A3 | Medium / change | Fresh Status says 6 total, 1 in sync, 2 pending, 1 conflict, 1 warning. The skipped row is omitted from totals, and discard-cache's warning replaces pending. Successful apply says 6 total, 5 in sync, omitting the unchanged item. Plan includes skipped and independent destructive counts, but its warning count remains badge-dependent and omits the discard warning. | Account for all primary states; warning/risk is independent metadata. Fresh fixture has 3 mutation-bearing relocations, 1 conflict, 1 in sync, 1 unchanged. [#27](https://github.com/big-sw-little-sw/homelight/issues/27). |
| A4 | High / change entry guidance | Missing default config shows Not Configured and recommends `homelight init`; running it returns `Unmatched argument ... 'init'`. Missing explicit config shows Configuration Error with refresh guidance, rather than setup. `--help` lists only status/plan/apply. | A usable next step for both absent default and explicit paths; never recommend an unavailable command. Specify in #21/#8; simulate in #22. Full init/save remains #8/#17, not an audit implementation. |
| A5 | Medium / prototype | Resolve conflict in Plan, press `1`: Status still labels it Conflict with Expected outcome Not determined. Return with `2`: draft choice persists, but selection resets to first row. Review → `n` likewise returns to the first Plan row. | Explain saved policy vs draft intent, retain relocation context on cancel/navigation, keep current observation distinct from expected result. #22; production work deferred until the human chooses a workflow. |
| A6 | Medium / change recovery wording | Insert a file at the configured staging root after reviewing the three-relocation fixture. First relocation completes (4 mutating actions), migration fails, third stays pending. Headline is `Plan stale` even though changes already occurred; count is 4/10. | Clearly distinguish zero-mutation preflight rejection from execution stopped after partial mutation; retain completed/failed/not-run identities. #22 scenario; clipping tracked in #26. |
| A7 | Low / remove primary jargon | Status prefixes diagnostics with `[DIRECTORIES_DISCARDED]` (expanded-height capture), and shows `when-source-and-target-directories-exist` before the expected outcome. Plan diagnostics already use human messages. Apply shows generic `Step path`, which often repeats source but sometimes identifies a different affected path. | Plain-language primary explanation; codes/keys only optional secondary detail. Action-specific affected-path labels without losing destinations. #27. |
| K1 | Keep | Status explicitly says `Expected outcome`, separate from Source/Target states; a pending migration can correctly expect In sync. Plan shows action intent rather than observations, so deciding requires referring back to Status. | Preserve the implemented distinction; #22 tests making both available together. Do not reopen the fixed expected-outcome bug. |
| K2 | Keep | `3` reaches review from either Plan pane when resolved. Enter on confirmation does not apply; `n` cancels; `y` applies. Unresolved/blocked guard coverage passes. | Keep exact reviewed plan and explicit destructive confirmation through #22/#15. |
| K3 | Keep / evaluate preference | During delayed execution, `k` lets the user inspect a previous action while the active spinner continues. Selection follows the next action once it starts. `q/r/1/2/3/y` are consumed during execution. | Preserve working action following and manual inspection. #22 may ask whether users need an explicit pause-follow mode; no regression claimed. |
| K4 | Keep | Completion remains visible; `1` returns to refreshed Status and `3` revisits the result. `r` creates a fresh plan; `3` then says No changes to apply, has no progress count, and ignores `y`. | Preserve retained results and no-change behavior. Result retention is session-local and ends on explicit replanning, not durable history. |
| K5 | Keep | Creating stage-cache target after review produces a stale preflight result with 0/10 completed; `r` exposes the new conflict. Partial failure retains 4 completed, 1 failed, 5 pending mutating actions; `first` is a symlink, `third` absent. `y/3` cannot rerun that result. | Keep preflight, per-action guards and explicit replanning. Existing session tests also exercise recovery with a new plan. |
| K6 | Keep with bounded evidence | Normal quit after success, stale and failed results emits cursor/alternate-screen restoration. Ctrl-C from idle Plan does too. Root opens Status; `plan` and human `apply` open Plan for review. | Preserve lifecycle/deep-link behavior. Real SSH, terminal disconnect and fatal signals remain explicit verification deferrals under #15; no claim of universal restoration. |

### Why the duplicate screens matter

This is more than duplicate rendering. `HomeLightSession.reloadModels()` loads
Status from saved configuration and Plan with session overrides. Status includes
its own reconciliation prediction but does not label that prediction as excluding
draft choices. `HomeLightApp.switchScreen()` resets selection and pane focus.
Consequently “inspect current state of the item I just resolved” changes both the
selected context and the policy perspective. The choice itself is retained.

Baseline scripted task cost: from the initially selected conflict in Status,
`2 → l → Space → 3` reaches confirmation in four keys; Enter is a separate
non-confirming action, `y` is the explicit mutation gate. Cancelling after the
choice returns to the first sorted item, requiring another selection to resume
that conflict. A combined workspace should be compared against this concrete
task, not assumed better from a screenshot.

## Proposal comparison: workflow and safety are separate

The current [comparison](homefree-comparison.md) is supported by archive spot
checks. No external archive code or instruction files were executed/installed.
Read archive members relative to `homefree-jsiva-oh/`:
`docs/adr/0004-safety-check-scope.md`, `0005-corner-case-resolution-policy.md`,
`0006-tui-structure.md`, `.oh/proto-tui/README.md`, and relevant portions of
`.oh/proto-tui/src/main.rs`.

| Dimension | Verified proposal evidence | Audit disposition |
| --- | --- | --- |
| Combined workspace | ADR 0006 combines review/decisions; choices use a popup. README and main.rs have separate Confirm, Applying and Applied states. | Test combined observation/decisions plus explicit review. “Single screen” does not mean eliminating lifecycle stages. |
| Progress | main.rs uses fake per-item latency and fake log entries; applying ignores input. | It demonstrates presentation, not real execution safety. HomeLight already supports manual inspection during real progress. |
| Correct items and Skip | ADR 0006 keeps correct items last and requires Skip wherever choices exist. | Compare against current collapse toggle with 6 and 100 items. Specify run-only Skip versus durable leave-unchanged before adopting copy. Large-list usability is deferred to #22. |
| Safety | ADRs propose permission mirroring, automatic empty-target recreation for broken links, fill-the-gaps merge, and dropping capacity checks on performance grounds. | None is validated by the fake executor. Do not copy automatic recreation or merge policy. Keep these out of #22 mutation logic. Permission verification stays #25; no performance conclusion without measurement. |
| Existing HomeLight safeguards | Real session/executor tests and fixture runs verify exact-plan preflight, staged publication, stop-on-failure and replan. Executor source explicitly notes no locking; copy verification uses structure/link values/file sizes. | Keep safeguards and their limits. This audit does not certify byte equality, external-writer exclusion, permission preservation or #11 completion. |

No new safety behavior is authorized by the proposal comparison. Archive
destination collision/preview experiments, general Skip, and automatic repair
remain deferred to explicit policy work or the #22 product choice. Shared lists
remain discovery candidates only; do not reopen that settled requirement.

## Proposed prototype questions, in priority order

1. Can a user inspect complete current state, choose a consequence, review and
   cancel at 80x24 without losing the item or seeing an invisible cursor?
   Compare existing tabs with one workspace and a choice popup or accessible
   detail view. Include source/target paths sharing long prefixes.
2. Can users distinguish saved policy, unsaved/run-only choice, expected outcome,
   and completed history? Include warning plus pending, intentionally unchanged,
   stale before mutation, and failure after 4 completed actions. Do not equate
   “stale” with “nothing changed.”
3. Does the existing action-follow rule support inspection during slow apply?
   Preserve result inspection, explicit replanning, and no-change display. Test
   one long failure reason and one archive/publication destination.
4. Can a first-time user start with missing default or explicit config, including
   unavailable optional NFS candidate input, reach manual setup, select and save
   deliberately, or cancel without saving? Simulate candidates; #21 owns format.
5. With 6 then 100 items, compare correct-items-last versus collapse. Count keys,
   lost-context events, task completion and recovery explanation accuracy.

These are recommended questions, not an accepted design. Human agreement on
priority ends session A. Session C's implementation and subsequent human journey
choice are separate gates. Session B / #21 can proceed independently; #25 can
also proceed before broad refactoring. Do not begin C or D automatically.

## Coordinator handoff and ticket disposition

- Baseline/result commits: `99bb49d`; no result commit, audit files are uncommitted.
- New #26: readability/focus/full-path/failure-detail contract, `needs-triage`.
  New #27: state/warning/unchanged accounting and primary wording, `needs-triage`.
  Both await #22's accepted journey before production changes.
- #20: evidence and recommendations posted; leave open at the human priority gate.
- #22: prioritized prototype scenarios, acceptance measures and explicit deferrals
  posted. No prototype built and no human acceptance recorded.
- #8: missing-default/explicit-path observations and non-working init guidance
  posted, linked to #21/#17. #15 receives the coordinator summary.
- #11 and #25 unchanged: audit does not independently settle their safety scope.
- Unresolved: workspace versus tabs, choice presentation, run-only skip semantics,
  follow preference and large-list default. Next recommended session is B if
  proceeding before the human gate; otherwise C after agreement on these questions.

The first follow-up issue creation was rejected by automatic approval review as
an export to an unverified repository. A read-only check established the target
as the same private `big-sw-little-sw/homelight` origin with ADMIN access and #20's
explicit ticket requirement. The subsequent same-destination creations succeeded.

Verified tracker receipts: [#20 audit](https://github.com/big-sw-little-sw/homelight/issues/20#issuecomment-5660554619),
[#22 prototype input](https://github.com/big-sw-little-sw/homelight/issues/22#issuecomment-5660555772),
[#8 entry evidence](https://github.com/big-sw-little-sw/homelight/issues/8#issuecomment-5660556694),
[#15 coordinator handoff](https://github.com/big-sw-little-sw/homelight/issues/15#issuecomment-5660557594).
Read-back confirmed #20 is open/needs-triage and #26/#27 are open/needs-triage.
