## Step 3 complete locally: Escape navigation and safe deferred exit

Implemented over the existing uncommitted #28 and #29 steps 1–2, preserving prior
edits. Baseline HEAD remains `99bb49d2a6d6201c6b5e100f07c0f33e0b2559dc`.

- Escape cancels apply/quit confirmation or returns from Plan detail to master;
  at top level, including execution/results and missing-config screens, it does
  nothing. Escape never requests exit.
- During execution, q/toolkit quit (verified with Ctrl-C) opens Keep running
  selected by default, with Exit when execution finishes as the alternative.
  The confirmation and deferred-exit reminder explain that results are
  session-local and will not remain available after exit.
- Confirmed deferred exit keeps progress/inspection alive and waits for session
  settlement, including failure and post-execution refresh. Completion cannot
  dismiss an open dialog, change its choice, or restart work. The UI thread owns
  exit; no worker interrupt, cancellation, shutdownNow, or filesystem changes.
- Launcher now owns toolkit resource lifetime and final waiting. Cleanup still
  runs when completion is exceptional. Render errors pass through that boundary
  rather than the toolkit's Escape-to-quit error screen.
- JSON production behavior, executor internals, and dependencies are unchanged.
  Read #25; it still has no findings/comments.

Verification with Java 25.0.3 at
`/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home`:

- Focused: **68 passed**, zero failures/errors/skips. Covers deterministic queued
  and rejecting workers, latch-held work without interruption, completion/dialog
  races, both dialog choices, retained exceptional results, toolkit cleanup,
  and existing reviewed JSON/CLI regressions.
- `mvn -o clean test`: **162 passed**, zero failures/errors/skips.
- **Eight real CLI/JLine PTY sessions passed at 80×24 and 120×30**. Verified
  Escape contexts, complete dialog wording, Ctrl-C/q defaults, completion while
  dialog stays open, live slow progress, success and partial-failure deferred
  exit, and filesystem evidence that settled work was not interrupted.
- Every PTY session verifies alternate-screen exit once, cursor restoration,
  terminal mode restoration, and all defined control characters. This is not a
  byte-for-byte termios claim: macOS JLine normalizes PTY speed fields and unused
  control slots; the kernel's transient PENDIN queue-state bit is excluded.
- `git diff --check` passed; final routing, lifecycle, rendered dialog sizing,
  Java version, and changed-file scope were spot-checked.

Handoff: `docs/research/session-d2-step3-implementation-handoff.md`.
Reproducible PTY driver/transcript and focused/full logs:
`docs/research/session-d2-step3/`.

#29 stays open for coordinator review. Stopping here. No #30 work, issue closure,
commit, or push.
