# Issue #29, step 3: Escape navigation and deferred exit

Implemented locally on 2026-09-14 over the existing uncommitted #28 and #29
steps 1–2. Baseline HEAD remains
`99bb49d2a6d6201c6b5e100f07c0f33e0b2559dc`. No commit or push.

Read #29 and its comments, the accepted application design, the step 2 handoff
and correction, and #25. #25 still has no findings/comments. Filesystem executor
internals and JSON production code were not changed.

## Behavior and ownership

- `HomeLightApp` consumes Escape before toolkit quit bindings. Escape cancels
  apply confirmation, dismisses the quit dialog, or returns from Plan detail to
  its master pane. It does nothing at top level, including execution/results,
  unconfigured/error screens, and the configuration placeholder. It never requests exit.
- During execution, `q` and toolkit quit intent (including Ctrl-C) open a dialog
  with **Keep running** selected and **Exit when execution finishes** as the
  alternative. Up/down, j/k, and Tab choose; Enter confirms; Escape cancels.
  Repeated quit keys do not select or confirm exit.
- The dialog explains that operations finish even on failure and that results
  are session-local and will not remain available after exit. The same reminder
  remains visible after deferred exit is confirmed. Progress keeps rendering and
  inspection remains available; navigation cannot start another execution.
- Completion cannot close a dialog or change its selected option. Enter on Keep
  running after completion retains the results. Confirming Exit after completion
  exits immediately; confirming before completion waits for settlement.
- `HomeLightSession.executionSettled()` checks the existing session future,
  including post-execution refresh and exceptional completion. Result publication
  alone is not the exit condition. The UI polls settlement during rendering and
  owns exit intent; the worker never accesses terminal state.
- `TuiLauncher` owns `ToolkitRunner` in a resource scope. Its inner `finally`
  waits for execution; the outer resource cleanup still runs if waiting throws.
  `HomeLightApp.run()` delegates to that boundary instead of inheriting
  `ToolkitApp`'s cleanup-before-stop-hook ordering. Render failures propagate
  through the same boundary rather than entering the toolkit error screen,
  which otherwise handles Escape as quit before application routing.
- No cancellation, worker interruption, `shutdownNow`, virtual-thread migration,
  durable result logging, dependency change, or filesystem algorithm change.
  Existing JSON schemas, exit codes, preflight, and terminal independence remain intact.

## Verification

All Maven commands used:
`env JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home`.
Surefire reports confirm Java 25.0.3 and that JDK path.

- Focused:
  `mvn -o -Dtest=HomeLightExitTest,TuiLauncherTest,HomeLightAppTest,ApplyViewTest,HomeLightSessionTest,ReviewedExecutionTest,ReviewedJsonApplyTest,ApplyCommandTest test`
  — **68 passed**, zero failures/errors/skips.
- Java 25 full clean suite: `mvn -o clean test` — **162 passed**, zero
  failures/errors/skips. Compiled 61 production and 21 test source files.
- Seven new exit tests cover navigation without mutation; both dialog choices
  across completion; default Enter; Escape after selecting exit; toolkit Ctrl-C;
  queued/rejected scheduling; a latch-held worker with no interrupt/cancel;
  result publication before settlement; failure and exceptional completion with
  retained evidence; repeated inputs without rescheduling.
- Two launcher tests run the actual toolkit with an instrumented terminal backend:
  normal and exceptional settlement restore/close once, and a render failure
  waits for queued work to finish before terminal cleanup. Exceptional settlement
  is injected via `obtrudeException` after real execution/result publication,
  without provoking a VM failure. The result-before-settlement test uses a
  controlled session settlement signal; it does not claim to fault-inject an
  actual configuration-refresh exception.
- Existing reviewed JSON integration tests pass, including redirected I/O,
  no TamboUI initialization, retained exceptional results, stale-plan preflight,
  partial failure, and `--yes` guards.
- `git diff --check` passed. Spot-checked event routing, lifecycle resource order,
  session future semantics, both dialog layouts, filesystem assertions, and
  the changed-file list against the initial dirty worktree.

Evidence: [focused log](session-d2-step3/focused-tests.txt),
[full-suite log](session-d2-step3/full-suite.txt),
[PTY driver](session-d2-step3/pty-check.py),
[PTY transcript](session-d2-step3/pty-check.txt).

## Real PTY checks and limits

Run `python3 docs/research/session-d2-step3/pty-check.py` after Maven tests.
The driver takes the Java executable and classpath from Surefire, runs the actual
CLI/JLine backend in real controlling PTYs, and uses only temporary fixtures.
It passes `--enable-native-access=ALL-UNNAMED` to suppress the JLine native-access
startup warning. No external Python packages are required.

Eight sessions cover **80×24 and 120×30**, each with:

1. Plan master/detail Escape, apply-confirmation Escape without mutation,
   running Escape, Ctrl-C/q safe defaults, selected-exit dialog cancellation,
   completion while the dialog stays open, Keep running after completion,
   and result-screen Escape.
2. Deferred exit during successful slow execution, with all operations completed.
3. Deferred exit during partial failure: the first relocation completes, the
   second fails on a target-local staging-file fixture with its source payload
   preserved, and the third is not run. Exit occurs after settlement.
4. Missing-configuration Status/Plan Escape followed by explicit quit.

Both sizes show the complete quit choices and session-local-results warning,
alongside live progress. Each session asserts exit 0 (the existing TUI contract),
alternate-screen exit exactly once, visible cursor restoration, restored terminal
mode flags, and restored defined control characters. A controlling supervisor
stays alive after Java exits so macOS does not revoke the slave before inspection.

Terminal restoration is **not a byte-for-byte termios claim**: the macOS JLine
backend changes PTY speed fields from 9600 to 0 and zeros unused control slots.
The kernel can also set transient `PENDIN` when raw mode ends; that queue-state
bit is excluded from the mode comparison. Canonical input, echo, signal handling,
all other mode bits, and every defined control character are checked. No serial
terminal or other-platform restoration claim is made.

Initial verification found test/driver issues, not an execution-policy failure:
a stale footer assertion, an extra normal toolkit cursor-hide call, a test typo,
using the master-pane Apply key while in detail, and macOS PTY revocation and
termios normalization assumptions. Corrected these and reran. The final PTY pass
also verifies the dialog's progress area uses its remaining height. Existing long
path clipping and the unavailable setup workflow remain outside this step.

## Coordinator handoff

Step 3 is complete locally. [#29 is updated with this evidence](https://github.com/big-sw-little-sw/homelight/issues/29#issuecomment-5662824236)
and left open for coordinator review. The posted text is retained in
`session-d2-step3/issue-update.md`. Stop here: no #30 work, issue closure,
commit, or push.

This step changes `HomeLightApp`, `TuiLauncher`, the running footer in `ApplyView`,
and adds the settlement query to `HomeLightSession`. Tests add `HomeLightExitTest`
and `TuiLauncherTest`, and update the existing running-footer assertion in
`ApplyViewTest`. Documentation adds this handoff and its verification artifacts,
plus a continuation pointer in the step 2 handoff. All pre-existing tracked and
untracked edits were preserved, including the earlier changes in the shared
session and TUI files. JSON, reviewed execution, and filesystem executor files
retain their pre-step-3 content.
