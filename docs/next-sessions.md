# Next sessions: prototype first, then simplify

Coordination baseline: 2026-09-14, implementation commit `fd25b68`.
GitHub issues own current scope and completion status; this document owns the
session sequence and handoff process. Re-read the selected ticket before starting.

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
| B: catalog and first-run contract | #21 | Prepare concrete local/shared/effective config examples and failure scenarios | Choose catalog authority, root mapping, merge and offline behavior |
| C: workflow prototype | #22 | Build a throwaway runnable comparison using A and B scenarios | Walk through and choose the user journey |
| D: semantic compression | #23 | Trace accepted journeys, propose and implement bounded extractions | Approve materially changed boundaries; verify preserved behavior |
| E: Java modernization | #24 | Audit Java 25 compatibility and ranked behavior-preserving improvements | Only needed for behavior changes, dependencies, or preview proposals |
| F: production vertical slices | Refined #7, #17, #8, #19 and children | Implement accepted behavior in separately verified slices | Smoke-test each user-visible slice |

Before broad refactoring, run #25 in its own bounded agentic session to verify
directory permission preservation. It can proceed independently of A/B.
Leave #11 open until its integration contract, including any confirmed safety
gap, is independently checked.

A and B can run in separate sessions concurrently. C needs their findings and
the human policy choices. E's read-only audit can run early; coordinate its edits
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

## B: corporate catalog and init decisions

Treat a shared catalog as configuration input, not as proof that a directory is
safe to delete or an external dotfile ownership registry. Prepare examples for
the same relative source under home and a non-home root, duplicate entries, local
overrides/exclusions, and collisions in derived target paths.

Questions requiring user decisions:

- Are entries suggestions, inherited managed defaults, or mandatory policy?
- How are relative entries bound to roots? Are absolute entries also allowed?
- Can local config override or disable shared entries? Which source wins?
- What happens on unavailable NFS, malformed data, changed/removed entries, or a
  shared list changing after review? Is cached data allowed, and how is it shown?
- Should init save a reference, a selected snapshot, or both? What happens when
  the default versus an explicitly requested config path does not exist?

Start discussion with optional catalog input, visible provenance, explicit
review of newly managed entries, and offline manual setup as a conservative
candidate, not a decided corporate policy. Use fixture examples to settle it.
Distinguish candidate discovery, configuration save, and filesystem apply.
Ensure cancel never writes, save is validated and atomic, and Apply uses the
reviewed effective configuration rather than rereading new shared instructions.

## C: prototype gate

Use the repository prototype skill. Simulate mutation. Compare a unified
workspace against the baseline tabs using the same scenarios and record task
completion, confusing state, navigation cost, and recovery clarity.

Show current state, expected result, and execution history as different things.
Keep explicit destructive review and confirmation even if Status and Plan merge.
Include missing-config and catalog entry points so the design is not limited to
already-configured users. The human chooses the workflow before production
restructuring. Record rejected alternatives and why.

## D: extract what the prototype demonstrates

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
2. Shared-catalog parsing, root resolution, merge/provenance, validation, and
   failure semantics as a presentation-independent slice with fixtures.
3. Missing-config/manual init and explicit save, including optional catalog input.
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
