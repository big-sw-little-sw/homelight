# HomeLight TUI Design Language

Maintained presentation contract for the accepted returning-user and setup journeys.
D3 and its follow-up polish are implemented locally; the user accepted the UX
on 2026-09-14. The coordinator also accepted the technical follow-up.
Implementation and verification: [the D3 handoff](research/session-d3-implementation-handoff.md).
Review that prompted the polish: [the D3 UX review](research/session-d3/ux-review.md). #30 owns polish; #26 owns
accessibility and #27 owns state accounting and wording.

## 1. Journey and navigation

One workspace combines observation and decisions, followed by explicit review,
confirmation, execution and retained results. JSON commands share reconciliation
behavior, not TUI screens or navigation state.

```text
⌂ HOMELIGHT   [1: Workspace]   [2: Review]
```

Use the same numbering and handlers from either pane. The second slot becomes
`[2: Results]` while results are retained. During execution show a noninteractive
stage label such as `[Applying]`, not a numbered invitation to navigate away.
The Workspace shortcut is also unavailable during execution. Show disabled
destinations distinctly and explain the relevant blocker without implying that
their keys are available.

- `1` opens Workspace when idle. From review it cancels confirmation, retaining
  the draft, selected source and prior focus. From results it retains the result.
- `2` opens Review when the plan is ready, or revisits retained Results. It never
  starts mutation. In Review/Results it remains on that stage.
- Advertise `a: Review & apply` in either workspace pane for a ready changing
  plan. If blocked or unresolved, explain the actual decision or repair needed.
- For a resolved no-change plan, advertise `2: Review`; the review says
  `No changes to apply` without confirmation or progress counters.
- With retained results advertise `2: Results` and `r: Re-plan`, not another
  apply. An existing `a` alias may revisit results but cannot start another run.
- Only lowercase `y` confirms a changing reviewed plan. Enter never confirms
  filesystem mutation. Explicit replanning creates a new plan requiring review.
- Bare startup and human status/plan/apply commands enter the unified journey.
  Do not recreate separate Status/Plan navigation or expose a stale `3: Review`.

## 2. Visual language and layout

Inherit the terminal background. Pair bold with an explicit semantic ANSI color.
Color and glyphs supplement words, never replace them; check contrast with the
terminal's palette. The house glyph is a brand cue, not a universal font-width
guarantee.

Use A's pane proportions and visual styling as the starting point: approximately
45% relocation list, remaining width for details. Do not import B's capped narrow
list. Compact rows show a useful source name/path and state; abbreviated list
paths must retain distinguishing portions. Full paths remain reachable in details.

Focus uses a cyan border and `❯` pointer, not color alone. Dim inactive framing.
Use stable, meaningful titles such as Relocations and Details; avoid changing
titles to redundant “focused” annotations when border/pointer already identify
focus. Radio choices use `(●)` for selected and `(○)` for unselected; distinguish
the focused option from the chosen option. Keep focused text and its consequence
visible while navigating and resizing.

Hide in-sync entries by default when other entries exist; preserve `c` and the
all-in-sync fallback. Show a hidden-count indicator only when entries are hidden.
Intentionally unchanged decisions remain visible. Order urgent blocked/conflict
items ahead of actionable items, then unchanged/in-sync, with stable source
identity across reordering. Warnings are additional properties, not a replacement
for primary state.

## 3. Workspace information hierarchy

The selected relocation should answer these questions without repeated metadata:

1. What is here now? Lead with a concise fact, such as
   `Current: Source and target are directories.` Keep meaningful link, inaccessible
   and missing-path exceptions explicit.
2. What saved policy applies to this observed case? Show the relevant branch in
   plain language, not all YAML keys or policy branches.
3. What draft choice is selected? Label it unsaved; show saved policy separately.
4. What is the expected outcome and consequence? State destructive effects
   explicitly, for example: `Keep target contents; delete the source directory
   and replace it with a link.`
5. Which alternative choices are available? Show the selected radio state and
   readable consequences. Choosing updates the draft only, never saves or applies.
6. Where exactly? One labelled Paths section contains full source/target paths,
   with relevant archive/link destinations. Avoid repeating identical paths.

Show configuration metadata once at screen/session level, not in every relocation
or action. Full configuration-path access must remain available if abbreviated.
Place invalidated-draft notices where users can understand them without repeating
the entire set under every relocation. Retained execution history stays distinct
from current observations and expected outcomes.

Use word wrapping for prose and character wrapping only for unbreakable paths.
Do not use internal enums, storage-property keys, or “Overlapping risks” as primary
explanations. Use Expected outcome, not a claim that planned convergence happened.

### Empty and unresolved content

Never render an empty Planned actions heading. Choose the explanation from state,
not solely from an empty action list:

- Unresolved: `Choose a decision to see planned changes.`
- In sync: `No changes needed; already in sync.`
- Intentionally unchanged: `No changes; left unmanaged by choice.`
- Blocked: explain the concrete blocker and repair needed.
- No configured relocations: say so; do not imply that all paths are in sync.

Workspace shows concise planned consequences; exact action/path inspection belongs
in Review. Do not remove important destructive details merely to shorten the view.

## 4. Review, execution and results

Review shows what will change and what may be removed before asking for confirmation.
Use a summary such as `10 planned changes · 5 destructive actions`, not a zero-filled
progress bar or running/completed/failed counts before execution.

Keep the reviewed action hierarchy, including unchanged entries. For each action
show its meaning, affected path and destination once. A NoOp needs no duplicate
Source/Unchanged path fields; destructive actions must still identify source and
target unambiguously. Preserve complete archive/publication/link destinations and
failure causes in the keyboard-accessible details.

Execution uses actual action-boundary progress, excluding NoOp/LeaveUnchanged
from mutating-action totals. Do not imply byte progress or transaction rollback.

| Meaning | Glyph and style |
| --- | --- |
| Queued | `○`, dim gray |
| Running | animated `⠋ ⠙ ⠹ ⠸ ⠼ ⠴ ⠦ ⠧ ⠇ ⠏`, cyan |
| Completed | `✔`, green |
| Failed/blocked | `✖`, red |
| Warning/destructive attention | `⚠`, yellow |
| Unchanged | `─`, gray |

Each new active action takes focus once. Manual inspection lasts until the next
action starts. On completion select the failure or last completed action once,
then retain manual selection. Do not add pause-follow controls in this polish.

Results distinguish completed, failed and not-run actions. A preflight rejection
with no attempts is different from a stale failure after some mutations. Preserve
known evidence and uncertainty; never infer zero mutation from a missing result.
Results remain available across Workspace/Results navigation until explicit
replan or exit. No-change replans have no confirmation or progress counters.

## 5. Counts, glyphs and overflow

Workspace counts **relocations**. Its primary categories partition the total:
actionable, conflict, blocked, in sync and intentionally unchanged. Warning and
destructive counts are independent and can overlap those categories and each other.

Use semantic glyphs alongside counts/text: `⚡ actionable`, `⚠ conflict`,
`✖ blocked`, `✔ in sync`, `─ unchanged`. Keep informative counts and partition
accounting even if zero-valued categories are omitted for space.

Additional-property copy: `Of these: 1 with warnings · 4 with destructive changes`.
Review counts **actions**, so a destructive-action count may exceed the workspace's
destructive-relocation count. Label units explicitly. Omit empty risk rows.
Wrap summaries into deliberate rows at 80 columns instead of clipping.

Use visible scrollbars when lists/readers overflow. The track/thumb reflects the
wrapped viewport, scroll offset and resize. Hide scrollbars when content fits.
No numeric line counters: neither `1–25/25` nor `Lines X–Y of Z`.
Preserve keyboard scrolling and automatic focused-choice visibility. Show the
scroll instruction once in contextual help only when the reader overflows.

## 6. Key/state matrix and help

Bindings below are the polish contract. Global idle `1`/`2` behavior is specified
above; dialogs and active execution consume them without navigation.

| Context | Selection/inspection | Confirm or leave |
| --- | --- | --- |
| Workspace list | ↑/↓ or j/k select; Tab/right/l opens details; c toggles in-sync | a/2 review when ready; r replan; Space/Enter must not quick-cycle or choose a policy from the list |
| Workspace details | ↑/↓ or j/k focus choices, or scroll if no choices; Space/Enter selects the focused choice; [/ ] scrolls overflowing reader | Tab/left/h/Esc returns to list; a/2 review when ready; r replan |
| Review list | ↑/↓ or j/k select actions; Tab/right/l opens details | y confirms a changing plan; n/Esc/1 cancels review |
| Review details | ↑/↓ or j/k and [/ ] scroll; Tab/left/h returns to list | y confirms; n/Esc/1 cancels review, not merely pane focus |
| Execution list/details | Preserve action following/manual inspection and pane/reader navigation | Esc returns detail to list, does nothing at list; q/toolkit quit opens quit dialog; no tab-stage navigation or replan |
| Results list/details | Select actions or scroll by focus; Tab/left/h moves between panes | 1/Enter returns to Workspace; r explicitly replans; Esc returns detail to list, does nothing at list |
| No-change review | Inspect unchanged entries | 1/n/Esc/Enter returns to Workspace; y does nothing |
| Quit dialog | ↑/↓, j/k or Tab chooses; repeated q/Ctrl-C does not confirm | Enter confirms selected option; Esc cancels dialog; default is Keep running |

Escape never exits. At top level it does nothing; omit its help there rather than
printing “never quit.” Normal idle q/toolkit quit exits. During execution or
unsettled completion, default to **Keep running**, with **Exit when execution
finishes** as the alternative. Explain that execution settles even on failure and
session-local results will not remain available after exit. Confirmation sets exit
intent only: no interruption or cancellation. Completion must not change an open
dialog's selection. Exit only after settlement, then restore terminal resources.

One state- and focus-specific help area describes each binding once. Prefer
`Esc: Back` for pane navigation, `Esc: Cancel review` for confirmation. Avoid
simultaneous contradictory Back/Cancel labels. Two intentionally wrapped help rows
are acceptable at 80 columns. Do not advertise unavailable commands or scroll
instructions when content fits.

## 7. First-run configuration creation

D4/#31 is accepted locally following the user walkthrough and coordinator review.
Evidence and publication status: [D4 handoff](research/session-d4-implementation-handoff.md).
The user also accepted #32b's candidate-browser UX. Its technical acceptance
is complete locally, including the destructive-policy copy correction recorded in the
[#32b handoff](research/session-b32b-implementation-handoff.md).

Missing default or explicit configurations prominently offer `i: Manual setup`
and `homelight init`. Existing malformed or unreadable configurations remain
errors, not replacement targets. Setup is creation-only.

- **Storage locations:** editable source root defaults to the home directory;
  target root is required. Relocation rows use paths relative to these roots.
  Optional Shared candidate list accepts an absolute path or `~/...`; blank
  uses bundled candidates only. Show its resolved location. First Browse starts
  discovery; manual setup does not wait for a shared source.
- **Relocations:** a compact Source, Target, Policies table. From the table,
  `a` adds an expanded row, Enter opens row details, `d` removes the selected
  draft row, and `e` edits locations while preserving rows.
- **Row details:** source/target-relative paths, three state-specific optional
  policies, and optional absolute archive root. Initially the target mirrors the
  source. Focused help explains the field's consequence. Complete entries remain
  accessible despite ellipsized table cells.
- Omitted policies mean the existing per-relocation Prompt behavior, not a new
  global defaults layer. Collapsed rows show `Default (prompt)` or explicit
  policy overrides. Discard when both directories exist must disclose deletion
  of both trees, empty-target creation and the source link, not source migration.
- Every edit resets validation. Validate and Save remain explicit; both reject
  blank row paths and normalized paths equal to or outside the chosen root.
  Nonblank archive roots must be absolute. Failures retain the editable draft.
- Escape returns from details to table and from table to locations; at locations
  it cancels setup without writing. Escape never exits the app. The discard
  dialog preserves edits on cancellation; confirmed discard clears all setup
  state, and reopening starts fresh.
- Save atomically creates a new configuration without replacing an existing or
  concurrently created file, then reloads the workspace. Save never applies
  relocations. Unsupported publication capability fails safely.

Verify at 80×24 and 120×30 with resizing both ways, including root edits, row
add/remove, invalid-input correction, discard/reopen and save without execution.

### Candidate browsing

`b` opens a subordinate browser from Relocations; manual entry remains available.
Use compact grouped checklist rows with visible focus and overflow scrollbars.
Each normalized path occurs once. App headings expand/collapse on Enter but
never select a group. Other directories holds ungrouped entries; details retain
all app associations, attributed advice/reasons and full paths.

- `[ ]` means eligible, `[x]` means in the draft, `[=]` plus Configured means
  saved and inspection-only. Blocking states use a dash and a plain explanation.
- Space/`a` adds one eligible candidate directly without leaving the list;
  Enter inspects it; `e` edits a draft member in the existing Row details.
  Repeated Add neither duplicates nor removes a row. Overlap rejection preserves
  prior choices and supplies an inspectable explanation.
- Add accepts current-generation directory or confirmed-missing observations.
  This supersedes #32a's directory-only rule. Unknown, pending, inaccessible,
  blocked, file, link and earlier-generation observations cannot authorize Add.
  Routine existence labels are omitted from the list. Missing-path details say
  `Not created yet` and explain future creation without promising success or
  inferring policy. Save creates configuration only; Apply re-observes paths.
- `u` reveals/hides a counted set of unselected candidates for which every
  current definition says usually-unnecessary. Mixed/omitted or retained advice,
  manual/configured entries and draft members remain visible. App collapse also
  keeps selected rows visible. Advice never chooses a policy or selects a row.
- `r` refreshes evidence explicitly; `i` opens full source diagnostics. Keep
  metadata, advice, source freshness and historical attribution separate. Sizes
  remain `not estimated`, ownership `not evaluated`.
- Background arrival preserves pane, field text, draft edits and focused path.
  A removed focused path stays inspectable until navigation changes focus.
  Returning to Browse retains context without rereading unchanged sources.
  Root/list edits invalidate prior requests; obsolete results cannot reattach.
- Escape returns details/sources → browser → table → locations → cancel setup.
  `q` in browser/table confirms discard; cancel preserves edits, confirmed
  discard clears setup and closes discovery. Save and abnormal terminal exit
  also dispose discovery. Source failures never disable manual entry or Save.

Verify direct Add/edit, confirmed-missing Add without path creation, grouping,
advice reveal, refresh/removal, blocked-source responsiveness, discard/reopen,
save failure and create-only success at 80×24 and 120×30, resizing both ways.
Existing-configuration editing remains #17.

Shared lists supply potential discovery candidates only. Users explicitly select
and save relocations; list refreshes never change managed entries or reviewed plans.

## 8. Verification and acceptance

Every future UI change must update this maintained document when its contract
changes and supply state-specific captures. Do not create competing design rules
inside isolated tickets without reconciling this document.

For D3 polish verify 80×24, 120×30 and 200×50 plus resize both ways. Check all
choices/consequences, full affected paths/destinations/failure causes, visible
focus, overflow scrollbar appearance/disappearance, plain summary units, no empty
headings, no numeric line counters, and no duplicated/contradictory help.

Repeat conflict → choose → review → cancel → confirm → results → revisit/replan,
including preflight rejection and partial failure. Preserve in-sync hiding/c,
draft/source context, explicit y, action following/manual inspection, Escape and
safe deferred exit. Run focused render/key tests, the Java 25 full suite and real
PTY checks with the documented platform limitations.

Passing tests do not constitute UX acceptance. The user accepted the polished
walkthrough on 2026-09-14. The technical follow-up is accepted and #30/#26/#27
are complete; evidence is recorded in the D3 handoff. Changing initial review selection
to the first mutating action, new follow/skip options and larger-list experiments
remain deferred; they are not prerequisites for this polish.
