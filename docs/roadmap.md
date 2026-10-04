# Roadmap

Agreed 2026-09-30. This file owns the step sequence and status. GitHub issues own
ticket scope. Decisions are recorded in `docs/decisions.md`; the evidence for the
Java and distribution decisions is in `research/native-image-spike.md`.

Notes under `docs/research/` predate the Kotlin migration (step 4c); their Java,
Maven and YAML references are historical.

Resume any step from this file alone: read the step, its done-when, and the status
line, then continue. Update the status line when a step's state changes.

## Goals

1. Every PR runs CI that proves the JVM build, native Linux binaries and the TUI work.
2. The remaining roadmap runs through a cloud coordinator that assigns work to worker
   agents and stops only for human decisions.
3. A TUI design pass happens before TUI code is simplified or extended.
4. Simplify: idiomatic Kotlin (data classes, sealed types, null safety), immutable
   data, small readable modules (see `CLAUDE.md`).

## Decided

- Move to Kotlin and kotlinx.serialization, built with Gradle Kotlin DSL; no
  dependency on GraalVM internals (2026-10-01, supersedes "Stay on Java 25").
  GraalVM Native Image works on Linux x86_64 and arm64.
- Configuration and candidate lists move from YAML to JSON (2026-10-01).
- Release targets: Linux x86_64 (static musl) and Linux arm64 (`--static-nolibc`,
  built on Oracle Linux 8, glibc 2.17+). macOS is a development platform only.
- Replace smallrye-config with snakeyaml (replaced by kotlinx.serialization JSON in K6b). Drop environment and system-property
  config overrides; they were an unused SmallRye side effect. Keep `${USER}` expansion.
- Native builds always use JLine's exec terminal provider; `-Dorg.jline.terminal.provider` does not override it (#78).
- #25: preserve the nine POSIX permission bits on every published directory; refuse
  publication where the filesystem cannot represent them. Ownership, ACLs,
  timestamps and xattrs stay out of scope.
- Agents: a Claude Code routine is the coordinator; worker agents are cloud sessions.
  GitHub Actions is used only for CI. GitHub labels and comments carry all state.
- Human gates: product and UX decisions, hands-on acceptance of user-visible PRs,
  and every merge to `main`.

## Steps

Work runs from a local Claude Code session acting as orchestrator. Agents do the work in worktrees, and the orchestrator merges each PR into `main` once all 7 required checks pass and it has reviewed the change. It stops only for product or UX questions.

### 1. Checkpoint

- Commit the #25 characterization tests (`StagedPermissionTest`) and
  `research/session-permissions-implementation-handoff.md`.
- Record the decisions above in `docs/decisions.md`.
- Replace the running log in `docs/next-sessions.md` with a short process note
  pointing here (git keeps the history).
- Comment the policy decision on #25 and label it `ready-for-agent`.

Done when: merged to `main`; #25 carries the decision.

Status: done (PR #33).

### 2. JVM CI

- GitHub Actions workflow: `mvn verify` (`./gradlew build` since K1) on Temurin 25 for every PR and push to `main`.
- Branch protection on `main` requiring that check.
- CI must resolve TamboUI: from Maven Central since #88, from the Sonatype snapshot repository before.

Done when: a PR shows the check and `main` cannot merge without it.

Status: done (PR #34). The repo is public, and `main` is protected: PRs are required, force-pushes are blocked, and 7 checks are required (`JVM verify` plus the six native jobs from step 4).

### 3. Remove native-image blockers

- Replace SmallRye with snakeyaml in `config/ConfigurationLoader` (about 150–250
  lines). Walk nodes into records; reject unknown keys and missing required keys;
  parse the three policy enums; report line and column like `CandidateParser`.
- Replace indexed string overrides (`homelight.relocations[0].source-path` in
  `PlanCommand` and tests) with a typed override.
- Add tests for missing keys, wrong types and unknown keys (`ConfigurationLoaderTest`
  has three today).
- Default JLine to exec in native builds (spike change).
- Refuse a dumb terminal with a clear error instead of hanging.

Done when: SmallRye dependencies are gone, tests cover the new cases, CI is green.

Status: done (PR #36). The snakeyaml loader was later replaced by JSON configuration in step 4c (K6b).

### 4. Native Linux CI

- Jobs on native runners (GitHub `ubuntu-24.04` and `ubuntu-24.04-arm`; confirm
  arm runners are available for this private repo):
  - x86_64: static musl build (Oracle Linux 7 image + Oracle musl toolchain).
  - arm64: `--static-nolibc` build on `oraclelinux:8` with `gcc-toolset-12`.
  - Builds need at least 3 GB RAM.
- Tests per binary: CLI comparison, TUI checks (resize, `TERM` values, noexec
  `/tmp`, terminal restore), and runs on Oracle Linux 7/8, Ubuntu 24.04, Debian 13,
  Fedora (latest).
- Upload both binaries as artifacts. Write rendered TUI screens (80x24, 120x30) to
  the job summary (no PR comment, so CI needs no write permission).
- Release publishing: on a version tag, attach both Linux binaries to a GitHub Release. PR artifacts expire after 3 days.
- `ci/try-pr <n>`: download a PR's arm64 binary and run it in an OrbStack Linux
  container with a disposable home and fixture directories.
- Move the `native` profile and trimmed metadata from `ci/spike/patch/` into the
  build; delete `ci/spike/` once replaced.

Done when: every PR produces both binaries and the checks pass; `ci/try-pr` works
from the user's Mac.

Status: done on `step4/native-ci` (PR #37). Jobs `Native build`, `Native test` and
`Native distros` for x86_64 and arm64; about 6 minutes wall clock after `JVM verify`
starts in parallel. Scripts in `ci/native/`, `ci/try-pr` for local tries. Distros:
x86_64 on Oracle Linux 7 (full), Oracle Linux 8, Debian 13, Fedora (CLI), Ubuntu 24.04
with noexec `/tmp` (full), Alpine (smoke); arm64 on Oracle Linux 8 and Ubuntu 24.04
noexec (full), Fedora (CLI); both runners also run the full suite on Ubuntu 24.04.
All six native jobs are required checks on `main`.

### 4b. Adopt Jackson databind for YAML and JSON (superseded)

Superseded by the Kotlin migration (step 4c). PR #39 closed without merging: the
native binary grew by 54% because Jackson pulls the JDK XML stack into the image,
and YAML binding needed a hand-written pre-pass. Original plan kept for history.

Jackson is the de facto standard; the intent is to adopt it unless the trial
shows a concrete blocker.

- Bind configuration and candidate lists into records with jackson-databind and
  jackson-dataformat-yaml: kebab-case naming, unknown and duplicate keys rejected,
  required components, policy enums via `@JsonValue`. Candidate lists keep size,
  depth, alias and string-length limits through Jackson and snakeyaml settings.
- Replace the hand-written jackson-core JSON writers (`PlanRenderer`,
  `ApplyRenderer`, `StatusRenderer`, `ActionJson`) with response records, keeping
  the versioned JSON contracts byte-for-byte or recording any change.
- Translate Jackson exceptions into short messages with line, column and key path.
- Add reflection metadata for the bound records; native CI (step 4) must pass.
- Remove `YamlMapping` and the hand-written walking if nothing still needs them.

Done when: merged with CI green on JVM and native, and less code than before; or a
recorded reason in `docs/decisions.md` for not adopting it.

Status: superseded (PR #39 closed).

### 4c. Migrate to Kotlin and kotlinx.serialization

Epic: #40. Each phase is a PR into the `kotlin-migration` branch (created from
`main` at `3ca5481`); the orchestrator merges those after the 7 CI checks pass. The
user merges `kotlin-migration` into `main`. Mechanical phases (K1–K5) do not change
behavior; the Java tests guard behavior until K5.

- K0: tickets and docs. Done (PR #50).
- K1 (#41): Maven to Gradle Kotlin DSL, Java sources unchanged; CI and `ci/native`
  scripts updated. Native CI jobs run only on pushes, PRs into `main` and PRs
  labeled `native`. Done (PR #51).
- K2 (#42): add Kotlin; convert `domain`, `fs`, `config` main code. Done (PR #52).
- K3 (#43): convert `reconcile`, `discovery`, `application` main code. Done (PR #53).
- K4 (#44): convert `cli`, `tui` main code; picocli metadata generated from the
  compiled classes. Done (PR #54).
- K5 (#45): convert tests to Kotlin. Done (PRs #55, #56).
- K6 (#46): Kotlin conventions in `AGENTS.md` (PR #57); idiomatic Kotlin pass
  (PRs #58, #59, #60); JSON output via kotlinx.serialization, byte-identical except
  lower-case control-character escapes (PR #61). Done.
- K6b (#49): configuration and candidate lists from YAML to JSON; snakeyaml removed.
  Done (PR #62).
- K7 (#47): final review and cleanup (PR #67); `kotlin-migration` → `main` PR open.

Done when: the user merges `kotlin-migration` into `main` with all 7 checks green
and both native binaries working.

Status: done (PR #68, merged 2026-10-02). All later work is on the Kotlin codebase: conventions in `AGENTS.md`, `./gradlew build`, and configuration in `~/.homelight.json`.

### 4d. Post-migration cleanup and #25

These are behavior-neutral follow-ups from the K7 review, plus the #25 bug fix. The user has decided every question they raise (see each issue). Order:

1. #63: remove unused and test-only code. Done (PR #69).
2. #65: one wording for policy labels in the TUI (the configuration words). Done (PR #72).
3. #64: consolidate duplicated rules and types, and use `toList()` for defensive copies. Done (PR #74).
4. #71: audit native-image reachability metadata. Native CI decides; an entry goes only when CI exercises its path. Done (PR #76).
5. #25: preserve directory permission bits during staged relocation, per the 2026-09-30 decision. It runs after #64 because both touch the reconcile code, and before #66 because it is a real bug. Done (PR #75).
6. #66: Kotlin idiom leftovers, including the `@field:` check. Done (PRs #77, #80, #85).

Follow-ups found during 4d, all done:

- #78: native builds always use the exec terminal provider (PR #80).
- #79: kotlinx.serialization owns the configuration and candidate-list format (PR #86).
- #82: executor tree walks and staging delete via `kotlin.io.path` (PR #89).
- #87: stale staging cleanup removes leftovers with read-only directories (PR #90).
- #81: configuration errors print as one line (PR #91).
- #88: TamboUI 0.5.0 release (PR #92).
- #84: discovery on virtual threads with a bounded sliding window (PR #93).
- #83: TamboUI key bindings, scrollbar and terminal check (PR #94).
- #10: independent relocations run concurrently (PR #95).
- #96: existing parent directories no longer make relocations dependent (PR #97).
- #98: relocations that share a staging root run concurrently.

Not scheduled: #70 (Clikt instead of picocli), for the user's own simplification pass.

Done when: all six are merged with all 7 checks green.

Status: done. Steps 5–7 stay deferred. Step 8 is done; next is step 9.

### 5–7. Cloud setup, pilot worker, coordinator routine (deferred)

Cloud setup did not work (2026-10-02). Orchestration continues from a local session, as described under Steps. #25, the planned pilot, moved into step 4d. Revisit cloud sessions and a scheduled coordinator if unattended runs become necessary; the earlier plan is in git history.

Status: deferred.

### 8. TUI design pass (with the user)

- Start from `docs/tui-design.md`. Audit at 80x24 and 120x30, prototype
  the changes, decide.
- Candidate list provenance: Browse should show which lists were loaded (bundled,
  shared), each list's resolved location, read status and freshness, without
  opening source diagnostics. Today the location shows only in Storage locations
  and per-candidate attribution only in details.
- Output: updated `tui-design.md`; #8, #15, #17 rewritten as small agent-ready tickets.

Done when: the user accepts the design and the tickets are `ready-for-agent`.

Status: done (2026-10-04, docs PR for branch `docs/tui-design-pass`). Ten decisions dated 2026-10-04 in `decisions.md`; `tui-design.md` rewritten as current rules. Umbrellas #7, #8, #15 and #17 are closed in favor of #102–#117. The `Policy<C>` question is settled (not built) and the `CandidateDiscovery` cuts are #105. Prototype evidence: branch `prototype/tamboui-focus` (never merge).

### 9. Orchestrator works the backlog

Order (each issue lists its blockers):

1. Independent, any order or in parallel: #102 (application cleanup), #103 (missing rule means prompt), #104 (one-time choices), #105 (discovery cuts), #106 (standard keys), #107 (palette), #112 (`source-root`).
2. #108 TamboUI focus foundation, after #106 and #107. `HomeLightApp.kt` and `WorkspaceView.kt` are conflict hotspots: avoid running several tickets that touch them at once.
3. #109 wording, then #110 Workspace details and #111 apply progress.
4. #113 safe replace, #114 configuration editor, #115 Browse in the editor, #116 `s: Always do this`.
5. #11 integration verification once the above settle.
6. Separate passes: the executor simplification after #102–#105, as one rung-tagged proposal for the user to decide; the Clikt spike (#70) after #114, so it ports the final command set.
7. Remaining features: #5, #6, #19, #117 (ownership explanation, after #5).

User-visible tickets (#106–#111, #114–#116) need the user's walkthrough before merge. Work follows the ladder and `[skipped: …]` rule in `decisions.md` (2026-10-04), and never files upstream issues.

Status: ready.
