# Homefree proposal comparison

Reviewed 2026-09-14 against the HomeLight working tree and the local archive
`/Users/jsiva/Downloads/homefree-jsiva-oh.zip`. Archive references below are
relative to its `homefree-jsiva-oh/` directory. No external code was executed.
This compares implemented HomeLight behavior with a proposal and fixture-only
prototype, not two production implementations.

## Recommendation

Borrow the proposal's focused design experiment: one relocation list that
combines observation and decision-making, followed by explicit confirmation
and retained results. Test that against HomeLight's current Status/Plan split.
Keep HomeLight's reviewed-plan and execution safety boundaries. A simpler
human journey does not require weaker plans or identical TUI and JSON flows.

The proposal's “single screen” means combined review and resolution, not one
unchanging screen for the entire lifecycle: its prototype also has separate
confirmation and applied views. Sources: archive `docs/adr/0006-tui-structure.md`
and `.oh/proto-tui/README.md`.

## What HomeLight demonstrably has beyond this proposal

- **Real reviewed-plan execution.** HomeLight retains the exact plan, checks
  its observations before mutation, retains action results, and requires
  explicit replanning after stale state or failure. The other prototype uses
  fixture observations and fake apply logs. See
  [HomeLightSession](../../src/main/java/io/github/bigswlittlesw/homelight/application/HomeLightSession.java)
  and its [journey tests](../../src/test/java/io/github/bigswlittlesw/homelight/application/HomeLightSessionTest.java);
  archive `.oh/proto-tui/README.md`.
- **Specified and implemented staged publication.** A source-only directory
  is copied into operation-owned target-local staging, checked, and atomically
  published before source replacement. The proposal describes copying and
  partial-move choices but does not implement filesystem execution. See
  [ReconciliationExecutor](../../src/main/java/io/github/bigswlittlesw/homelight/reconcile/ReconciliationExecutor.java)
  and [executor tests](../../src/test/java/io/github/bigswlittlesw/homelight/reconcile/ReconciliationExecutorTest.java);
  archive `docs/adr/0005-corner-case-resolution-policy.md`.
- **More precise existing-target semantics.** “Adopt target” means the target
  is authoritative, with an independent source disposition. The proposal's
  “adopt” combines several source-side operations, while its “partial move”
  label asserts history that an observation of two directories cannot prove.
  See [domain vocabulary](../../CONTEXT.md) and
  [planner](../../src/main/java/io/github/bigswlittlesw/homelight/reconcile/ReconciliationPlanner.java);
  archive `CONTEXT.md` and `.oh/proto-tui/src/model.rs`.
- **Existing outside-home support.** An explicit target allows a source
  outside `$HOME`; the proposal's config is home-relative only. This is useful
  groundwork, but is not yet root-independent shared-candidate discovery. See
  [ConfigurationLoader](../../src/main/java/io/github/bigswlittlesw/homelight/config/ConfigurationLoader.java);
  archive `docs/adr/0001-config-format-and-location.md`.

These are bounded advantages, not a claim of complete safety. HomeLight's
preflight compares observations, not file contents or identities, and does not
lock out external writers. Copy verification checks structure, symlink values,
and regular-file sizes, not byte equality. Versioned JSON and durable TUI
configuration remain future work despite appearing in the product specification.

## Ideas worth adopting or testing

| Idea | Proposed treatment |
| --- | --- |
| Combined overview and decisions, choices in a popup | Prototype against the current detail-pane controls. Compare context retention, keystrokes, and narrow-terminal readability, not just appearance. |
| One-line state and choice explanations | Adopt the principle; show current state, intended result, and destructive consequences separately. Avoid raw enum names and policy-property keys as the primary explanation. |
| A visible leave-untouched choice wherever decisions exist | Test across all ambiguous states. Specify whether it is run-only or durable; the proposal's `Skip` is run-only, unlike configured `leave-unchanged`. |
| Correct items shown last instead of collapsed | Compare with HomeLight's existing collapse toggle using small and large lists. Neither default is universally better. |
| Rename/archive destination preview and collision handling | Preview the exact destination before confirmation. Decide whether to block or allocate a numbered name during planning; never choose a different name silently during apply. |
| Source permission mirroring | Investigate as a safety task before cosmetic refactoring. The proposal explicitly requires it; HomeLight's directory copy creates directories with defaults and does not explicitly mirror directory permissions. |

Evidence: archive `docs/adr/0004-safety-check-scope.md`,
`docs/adr/0006-tui-structure.md`, and `.oh/proto-tui/src/model.rs`; HomeLight
[TUI design](../tui-design.md) and `ReconciliationExecutor.CopyVisitor`.
Permission handling needs a focused filesystem test, including restrictive
source-directory modes and supported-platform behavior; this comparison did
not execute such a test.

## Policy differences to resolve, not copy blindly

- **Broken link with absent target:** the proposal recreates an empty target
  automatically; HomeLight blocks. A missing target can represent an
  unavailable mount, not disposable lost cache. Decide what evidence and
  policy permit recreation before offering it as an automatic repair.
- **Source-only contents:** the proposal offers move, rename, or delete;
  HomeLight currently stages migration. Cache reset without copying could be
  useful, but needs an explicit source-content policy and destructive review.
- **Both directories exist:** the proposal offers a fill-the-gaps merge.
  Do not infer “partial move” or merge safety from that state. Specify file,
  directory, and symlink collision behavior before considering implementation.
- **Permissions and capacity:** borrow the explicit permission checklist, but
  do not adopt the assertion that free-space checking is too expensive without
  measurements. Distinguish querying capacity from estimating source size.
- **Shared candidate list:** the proposal explicitly excludes shared config and
  multi-user concerns. HomeLight's requested feature is narrower: a central list
  of potentially bulky directories used only as discovery candidates. Users
  explicitly select what becomes configured; shared-list changes never change
  existing managed relocations. Root mapping, duplicate handling, and unavailable
  NFS behavior still need specifying, not enforcement or policy precedence.

Sources: archive `.oh/requirements-rough.md`,
`docs/adr/0004-safety-check-scope.md`, and
`docs/adr/0005-corner-case-resolution-policy.md`; HomeLight planner above.

## Concrete next prototype scenarios

1. Mixed list: in-sync, pending migration, destructive warning, unresolved
   conflict, and intentionally unchanged. Explain the current and expected
   state, decide one item, review, cancel, and return without losing context.
2. Long paths at 80×24, then 100 relocations: find a conflict, inspect its
   complete paths, change a choice, and reach confirmation from any pane.
3. Slow serial apply, failure partway through, stale reviewed plan, and a
   no-change replan: distinguish execution history from the next plan.
4. Missing config plus an optional shared candidate list on NFS: show candidate
   provenance, sizes and states, an outside-home root, a duplicate built-in entry,
   and unavailable NFS. Select candidates and save relocations explicitly before
   considering mutation. Refreshing the list must not change those selections or
   existing configured relocations.

Use fixture-only UI experiments for navigation questions. Preserve the real
executor and use temporary filesystem integration tests for safety questions.
Record human observations and rejected alternatives before extracting shared
state or reorganizing packages. Do not transplant the Rust prototype's types
merely because its screens are simpler.
