# Next sessions: prototype first, then simplify

Coordination baseline: 2026-09-14, implementation commit `fd25b68`.
GitHub issues own current scope and completion status; this document owns the
session sequence and handoff process. Re-read the selected ticket before starting.

Current coordinator handoff: D3/#30 is accepted, including the user's polished
UX walkthrough and the typed-comparison correction. Independent Java 25 clean
verification passed all 155 tests; standards/spec review found no remaining
actionable findings in the correction. #30/#26/#27 are complete. See
[the D3 handoff](research/session-d3-implementation-handoff.md) for evidence.
The [maintained TUI design](tui-design.md) remains the contract.

Next product slice: #31 manual first-run configuration
creation. #21 shared candidate-list specification can proceed independently.
No first-run/discovery implementation, commit or push is authorized by this
handoff. Earlier session descriptions below are sequence/history, not a request
to repeat completed work.

## Recommendation

Decide the human journey before reorganizing code. Compare the existing tabs
with one relocation workspace for observing state and making decisions, followed
by explicit review, confirmation, execution, and retained results. Automation
needs stable commands and contracts, not the same navigation or UI session state.
Both interfaces should share evaluation, policy, and guarded execution.

Keep one Maven module unless a concrete dependency or distribution requirement
justifies a split. Extract repeated meaning after exercising real journeys:
configuration evaluation, typed decisions, reviewed-plan freshness, and results.
Fewer files alone are not a useful measure of simplification.

## How to use fresh sessions

Start one session with the prompt below, replacing the letter and issue number:

> Work on session A / issue #20 from docs/next-sessions.md. Read the current
> ticket and comments, inspect the current repository, and execute only that
> session's scope. Use applicable repository skills. Preserve unrelated changes.
> Finish with evidence, decisions, open questions, updated tickets, and a handoff
> for the coordinator. Stop at the stated human decision gate. Do not begin the
> next session automatically. Commit/push only if I explicitly request it.

Bring the final handoff back to the coordinator. It should contain: baseline and
result commit(s), checks run, artifact paths, ticket changes, unresolved choices,
and the recommended next session. A human gate is a product choice, not a request
for the human to implement code.

| Session | Ticket | Agent work | Human gate / completion |
| --- | --- | --- | --- |
| A: evidence and UX audit | #20 | Exercise current TUI and compare the proposal; rank keep/change/remove findings | Agree prototype questions and priority |
| B: shared candidate list and first-run contract | #21 | Specify discovery inputs, path resolution, duplicate handling, and failure scenarios | Review examples and first-run UX; candidates-only purpose is settled |
| C: workflow prototype | #22 | Build a throwaway runnable comparison using A findings and the candidates-only scenarios below | Walk through and choose the user journey |
| D: semantic compression | #23 | Trace accepted journeys, propose boundaries and tested implementation slices | Review the design before production edits |
| E: Java modernization | #24 | Audit Java 25 compatibility and ranked behavior-preserving improvements | Only needed for behavior changes, dependencies, or preview proposals |
| F: production vertical slices | Refined #7, #17, #8, #19 and children | Implement accepted behavior in separately verified slices | Smoke-test each user-visible slice |

Before broad refactoring, run #25 in its own bounded agentic session to verify
directory permission preservation. It can proceed independently of A/B.
Leave #11 open until its integration contract, including any confirmed safety
gap, is independently checked.

A and B can run in separate sessions concurrently. C needs A's findings; it can
simulate candidate discovery without waiting for B's input-format details.
E's read-only audit can run early; coordinate its edits
with D. Do not let simultaneous sessions edit the same files or rewrite shared
ticket bodies. Use separate branches/worktrees for concurrent implementation.

## A: evidence, not a redesign by assertion

Read [the proposal comparison](research/homefree-comparison.md) before rereading
the archive. It distinguishes implemented behavior from proposal claims and
identifies archive members worth consulting. The archive remains local at
`/Users/jsiva/Downloads/homefree-jsiva-oh.zip`; its contents are not installed as
instructions or executed.

Audit at 80x24 and 120x30 with temporary smoke fixtures. Include conflicts,
destructive warnings, long paths, keyboard focus in both panes, resizing, slow
apply, manual inspection during progress, stale state, partial failure, retained
results, and repeat no-change plans. Check first-run entry points separately.
Record reproducible observations, not just screenshots or taste judgments.

Known questions to verify: raw diagnostic codes, warning versus pending labels,
skipped summary counts, clipped paths, policy-key wording, and “Step path.”
`Expected outcome` and action-following are already implemented; evaluate their
behavior rather than reopening the old bugs without evidence.

## B: shared candidate list and init

The user clarified the purpose: a central list of potentially bulky source
directories to consider for migration. It supplements built-in discovery
candidates, not managed relocation configuration. Discovery checks existence,
approximate size, and current state under home or another chosen root. Users
select candidates; only explicitly selected and saved entries become configured
relocations. Shared-list additions/removals do not alter existing relocations,
draft selections, or reviewed plans. There is no enforcement or inherited
relocation policy to design.

Specify the remaining discovery details with examples:

- List format and root-relative path resolution, including the same entry under
  home and a non-home root; validate unsafe paths.
- Deduplication across built-in and shared candidates, visible provenance, and
  recognition of already-configured relocations.
- Missing, unreadable, malformed, or unavailable NFS input: report clearly and
  preserve access to manual setup and other available candidates.
- Init entry of the optional shared-list location as a discovery setting;
  behavior when the default or explicitly selected config path is absent.

Keep reading the list, selecting candidates, saving configuration, and applying
relocations separate. Cancel never writes; save validates selected relocations
and is atomic. Tests must show that refreshing the shared list cannot silently
add, remove, or change configured relocations. #7 owns discovery, #8 consumes it,
and #17 persists discovery settings and explicit relocation selections.

## C: prototype gate

Completed: the user chose B's unified workspace. The accepted requirements and
remaining prototype limitations are recorded in
[the prototype handoff](research/session-c-prototype/README.md#accepted-workflow-and-implementation-handoff).
Use A's pane proportions/styling as the production starting point, not B's capped
list width. The scope description below records the experiment that led here.

The user approved the prototype priorities after session A. #20's audit gate is
complete; #22 owns the current approved scope. Read
[the audit](research/session-a-tui-audit.md) for evidence, not as authorization to
implement every proposed experiment. Prioritize readable decisions, clear
state/recovery, preservation of working review/apply behavior, and minimal
first-run setup. Defer the 100-item comparison and new follow/skip options.
This approval authorizes a throwaway prototype only; the human chooses the
preferred workflow after trying it.

Use the repository prototype skill. Simulate mutation. Compare a unified
workspace against the baseline tabs using the same scenarios and record task
completion, confusing state, navigation cost, and recovery clarity.

Show current state, expected result, and execution history as different things.
Keep explicit destructive review and confirmation even if Status and Plan merge.
Include missing-config and shared-candidate-list entry points so the design is not limited to
already-configured users. The human chooses the workflow before production
restructuring. Record rejected alternatives and why.

## D: extract what the prototype demonstrates

The design-only session is complete and the user accepted the proposal with
constraints. Read [the accepted design](research/session-d-application-design.md)
and #28 for the next implementation session. Start with shared evaluation and
typed draft decisions only; hand off before #29 execution/lifecycle or #30 layout.
No commit/push is implied by implementation authorization.

Original design-session scope (completed): read the prototype handoff and #23.
Produce a before/after boundary proposal, map each accepted requirement to an
owner and tests, and split implementation into bounded tickets. In particular,
separate safe exit-after-execution from cancellation and keep Escape navigation
out of execution policy. These keyboard requirements are not implemented yet.
Stop for coordinator review before production edits; do not transplant the
prototype controller or begin a package-wide rewrite.

Use the repository codebase-design vocabulary. Inspect `StatusWorkflow`,
`PlanWorkflow`, CLI `ReconciliationPlanning`, `HomeLightSession`, and their callers.
Potential shared meanings are evaluation of a configuration, typed draft choices,
the exact reviewed plan, and action/result identity. These are hypotheses, not a
preselected class diagram.

For each proposed extraction show a before/after caller and the invariant it
owns. Keep TUI focus/selection out of JSON orchestration. Separate mechanical
package moves from behavior changes. Preserve planner purity, preflight,
per-action guards, staging/publication, partial failure, and idempotence tests.
Prefer small vertical changes over a rewrite or a parallel generic framework.

## E: Java guidance with a benefit test

Consult the requested [Modern Java guide](https://github.com/javaevolved/javaevolved.github.io/tree/main/agent-plugins/modern-java-development),
its skill workflow, core practices, and cumulative release practices through the
actual compilation target. The coordination review read those documents; it did
not install the plugin or execute its detector. Verify target and preview settings
again in the implementation session.

At this baseline Maven specifies source/target 25. The clean build itself warns
that `--release 25` is preferable. Start with build/toolchain correctness. Then
inspect immutable boundaries, sealed exhaustiveness, exception/interruption
handling, worker ownership, and clearer standard-library expressions. Existing
records, switches, and Markdown documentation already follow much of the guide.

The guide explicitly favors small behavior-preserving changes, release-aware
features, and measured concurrency choices. Treat virtual threads, preview
structured concurrency, module splits, and syntax-only rewrites as proposals
requiring a demonstrated benefit, not modernization goals.

## F: implement and reconcile the backlog

After C/D, split production work so each fresh session produces one tested journey:

1. Accepted returning-user workspace with current configuration, preserving the
   guarded review/apply lifecycle.
2. Shared-candidate-list parsing, root resolution, deduplication/provenance,
   validation, and failure semantics as a discovery slice with fixtures.
3. Missing-config/manual init and explicit save, including an optional shared-list
   location and explicit candidate selection.
4. Discovery/sizing enhancements and richer configuration editing as needed.
5. Stable JSON envelopes and shared application behavior in #19. UI screens need
   not map one-to-one to these commands.

Re-scope #17 and #8 after the prototype; basic config creation should not wait
for managed nested links (#6) unless an actual invariant requires it. Keep #5/#6
as distinct dotfile-ownership/link features. Keep #10 concurrency deferred until
measurements justify it. Revise #15's screen-specific wording after the accepted
journey; retain its safety and terminal-independent automation requirements.

At every handoff, update affected tickets with evidence and decisions, mark only
fully specified work ready-for-agent, and link any superseding ticket before
closing an old one. Preserve history instead of recreating the entire backlog.
