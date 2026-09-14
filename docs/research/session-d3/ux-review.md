# D3 user review: coordinator follow-up

Final disposition, 2026-09-14: polish implemented and UX accepted by the user.
The review and scheduling notes below are historical. The bounded technical
follow-up is also accepted; see
[the coordinator handoff](../session-d3-implementation-handoff.md).

2026-09-14. Review of the user's workspace/review captures and current source,
with an independently delegated UX design specialist. Recommendations below are
proposed corrections, not implemented or newly accepted product decisions.
The previous passing tests establish functional coverage, not UX acceptance.

Coordinator disposition: incorporated into `docs/tui-design.md` and remaining
#30 acceptance. One polish session will align navigation/help, then concise
details, then summary glyphs/overflow scrollbars. Navigation uses 1: Workspace
and 2: Review/Results with a: Review & apply when ready. The user-selected
scrollbar treatment is required. The optional first-mutating-action initial
selection change is deferred. #26/#27 remain open until the polished user
walkthrough; no UI implementation or acceptance is claimed by this update.

User decision during review: use visible scrollbars instead of numeric line
counters. This overrides the specialist's optional labelled-line-counter proposal.
Show the scrollbar when content overflows, update its thumb/track during resize,
and keep keyboard scrolling and focused-choice visibility. Do not implement
`Lines X–Y of Z` or retain `1–25/25` as an alternative.

## Findings and recommended corrections

| Finding | Evidence and recommendation |
| --- | --- |
| Inconsistent tab numbering | `WorkspaceView.render` and `ApplyView.render` show an unnumbered Workspace beside legacy `3: Review`. `HomeLightApp` only handles `1` on results, not as a general tab binding. Use a consistent `[1: Workspace] [2: Review]` header, with Results occupying the second slot after execution. Match displayed keys, enabled states and handlers. Returning from review cancels confirmation and preserves the draft; execution cannot be left through a tab. Do not imply that a stage label is an available navigation action. |
| Apply is not discoverable | `HomeLightApp.handleKeyEvent` already routes `a` from either workspace pane to review, but the footer omits it. Advertise `a: Review & apply` when ready, and explain the actual blocker when unavailable. Keep review and lowercase `y` confirmation; pressing `a` must never start filesystem mutation. After execution, advertise Results and explicit replanning, not another apply. |
| Observation details are too busy | `WorkspaceView.details` prints states, repeated full paths, config path and all policy branches ahead of the choices. Lead with a concise fact, e.g. `Current: Source and target are directories.` Then show the relevant saved policy, draft and expected outcome separately. Move full source/target paths into one clearly labelled Paths section later in the same keyboard-accessible reader. Show config metadata once per screen/session, not once per relocation and action. Full paths must remain accessible; abbreviations cannot be the only representation. |
| Empty Planned actions heading | The workspace always emits this heading, even for an unresolved plan with no actions. Distinguish `Choose a decision to see planned changes`, `No changes needed; already in sync`, `No changes; left unmanaged by choice`, and a blocked explanation. An empty action list does not establish that nothing is necessary. |
| Planned actions repeat low-level data | The workspace emits each action label, affected path and destination, repeating paths already above. Use one concise consequence summary such as `Keep target contents; delete the source directory and replace it with a link.` Keep exact action/path inspection in review. In review, show each required affected path/destination once; omit Source/Unchanged path duplication for NoOp. Keep source/target identification for destructive operations unambiguous. |
| Unexplained `1–25/25` | `DetailViewport.render` counts visible wrapped display lines, including blanks, out of total wrapped lines. It is neither actions nor relocations, and changes with terminal width. The user chose visible scrollbars instead of these counters. Show a scrollbar when content overflows; hide unnecessary overflow chrome when everything fits. Preserve keyboard scrolling, resize updates and focused-choice visibility, with the scroll instruction listed once in contextual help. |
| Summary glyphs disappeared | `WorkspaceView.summary` renders plain strings. `docs/tui-design.md` explicitly shows `⚡`, `✔` and neutral summary markers in its recalculation examples. Restore consistent semantic glyphs alongside text/counts, including actionable, conflict, blocked, in-sync and unchanged states. Preserve complete primary accounting and readability at 80 columns; wrap into deliberate rows rather than clipping. Glyphs/color supplement words, never replace them. |
| `Overlapping risks` is jargon | These counts are additional properties of relocations already counted above. One relocation can have both a warning and destructive actions. Suggested compact copy: `Of these: 1 with warnings · 4 with destructive changes`. Explain shared membership in contextual help if needed. Keep units explicit: the workspace counts relocations; review counts actions, which is why the supplied captures show 4 versus 5. |
| `never quit` and duplicated help | Escape never exiting is a behavior contract, not a footer sentence. Show `Esc: Back` where it backs out, `Esc: Cancel review` in confirmation, and omit it where it does nothing. Review currently prints both `Esc: Back` and `n/Esc: Cancel`; the handler actually cancels confirmation from either pane. It also repeats arrow/inspection and scroll help. Produce one state- and focus-specific help area with each binding described once. Two deliberately wrapped rows are acceptable at 80 columns. |

## Suggested information order

Workspace details should answer, in order: what is here now, what policy applies,
what draft choice is selected, what will change, and which other choices exist.
Exact paths remain available in one labelled section. Keep meaningful current
exceptions, destructive consequences and selected-choice markers prominent.
Use word wrapping for prose; reserve character wrapping for unbreakable paths.

Review should answer what will change and what could be removed before asking
for confirmation. Prefer `10 planned changes · 5 destructive actions` over a
zero-filled progress bar and completed/failed/running counters before execution.
Show live progress during execution and completed/failed/not-run evidence in
results. Preserve no-change entries, but consider initially selecting the first
mutating action rather than an already-in-sync entry. This is a proposed review
selection refinement, not permission to remove unchanged entries.

## Coordinator actions

1. Keep #30 in review and carry these findings into its acceptance. Link #26 for
   complete-path/focus/resize requirements and #27 for state counts and wording.
   The previous completion comment needs a follow-up noting this user review;
   do not close the UX issues based only on passing tests. No new tracker writes
   were made during this review turn.
2. Reconcile the existing `docs/tui-design.md` before further UI tickets. Use it
   as the maintained design-language document rather than adding a competing
   specification. Replace the four-screen navigation, disabled-quit claims,
   automatic setup claims, quick-cycle Space behavior and fixed single-row
   footer rule with the accepted journey and actual safety contracts. Mark
   first-run/configuration concepts as future work. Resolve its title/focus
   styling rules against the accessible focus treatment deliberately.
3. Add explicit design rules for information hierarchy, conditional metadata,
   outcome versus current state, empty/blocked/unresolved content, count units,
   semantic glyphs, visible scrollbars on overflow (the user's chosen design),
   and deduplicated keyboard help.
   Record a key/state matrix for workspace master/detail, review master/detail,
   execution, results and quit dialogs. Decide tab numbering and the exact apply
   entry wording once, then use them in the UI, help and tests.
4. Schedule one bounded D3 polish ticket (or reopen #30's remaining acceptance):
   first align navigation/help/apply discoverability; then implement concise
   details and planned-change summaries; finally restore summary styling and
   conditional overflow indicators. These are related presentation corrections,
   not executor, JSON, first-run or discovery work.
5. Require a design-document update and state-specific captures with future UI
   tickets. Test 80×24, 120×30, a wider terminal matching the supplied captures,
   and resize both ways. Check no duplicate/contradictory help, no empty headings,
   no numeric line counters or useless scroll hints when content fits, visible
   scrollbars when content overflows, all full paths reachable, visible
   choice focus, all count units clear, and discoverable review from both panes.
   Preserve draft/source context, in-sync hiding/c, explicit `y`, action following,
   manual inspection, retained results, Escape semantics and deferred exit.
6. Repeat the user's conflict → choose → review → cancel → confirm → result →
   revisit/replan walkthrough and obtain human UX review before closing the
   presentation work. Automated string visibility is necessary but insufficient.

## Evidence and scope

Inspected `WorkspaceView.java`, `ApplyView.java`, `DetailViewport.java`,
`HomeLightApp.java`, `docs/tui-design.md`, the accepted prototype README, the
session-A audit and the D3 handoff, alongside the user's captures. The specialist
review independently checked the same presentation and event-routing concerns.
No Java changes, new test runs, commit or push occurred in this review. Existing
implementation/test evidence remains in the D3 handoff and is not represented
as verification of these proposed corrections.
