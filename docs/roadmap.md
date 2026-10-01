# Roadmap

Agreed 2026-09-30. This file owns the step sequence and status. GitHub issues own
ticket scope. Decisions are recorded in `docs/decisions.md`; the evidence for the
Java and distribution decisions is in `research/native-image-spike.md`.

Resume any step from this file alone: read the step, its done-when, and the status
line, then continue. Update the status line when a step's state changes.

## Goals

1. Every PR runs CI that proves the JVM build, native Linux binaries and the TUI work.
2. The remaining roadmap runs through a cloud coordinator that assigns work to worker
   agents and stops only for human decisions.
3. A TUI design pass happens before TUI code is simplified or extended.
4. Simplify: Java 25 idioms, records and sealed interfaces, immutable data,
   small readable modules (see `CLAUDE.md`).

## Decided

- Stay on Java 25. GraalVM Native Image works on Linux x86_64 and arm64.
- Release targets: Linux x86_64 (static musl) and Linux arm64 (`--static-nolibc`,
  built on Oracle Linux 8, glibc 2.17+). macOS is a development platform only.
- Replace smallrye-config with snakeyaml. Drop environment and system-property
  config overrides; they were an unused SmallRye side effect. Keep `${USER}` expansion.
- Native builds default JLine to the exec terminal provider.
- #25: preserve the nine POSIX permission bits on every published directory; refuse
  publication where the filesystem cannot represent them. Ownership, ACLs,
  timestamps and xattrs stay out of scope.
- Agents: a Claude Code routine is the coordinator; worker agents are cloud sessions.
  GitHub Actions is used only for CI. GitHub labels and comments carry all state.
- Human gates: product and UX decisions, hands-on acceptance of user-visible PRs,
  and every merge to `main`.

## Steps

Steps 1–4 run locally with the user. Step 8 runs in parallel with steps 6–7.

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

- GitHub Actions workflow: `mvn verify` on Temurin 25 for every PR and push to `main`.
- Branch protection on `main` requiring that check.
- CI must resolve the TamboUI snapshot from `central.sonatype.com`.

Done when: a PR shows the check and `main` cannot merge without it.

Status: CI green on PR #34 (first Linux run exposed a wrap-dependent assertion in
`CandidateSetupTest`, fixed). Repo made public so branch protection is available.
Remaining: user merges #34 and applies branch protection (required check `JVM verify`,
PRs required, no force-push).

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

Status: done on `step3/native-blockers`. snakeyaml loader with line/column errors
(SmallRye and jboss-logging gone from the dependency tree), typed
`ConfigurationLoader.PathOverride`, exec provider default in native builds, dumb
terminal refused with exit 2. 252 tests pass on macOS and Linux.
Remaining: user merges PR.

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
Remaining: user merges PR; add the new jobs as required checks.

### 4b. Adopt Jackson databind for YAML and JSON

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

Status: not started. Runs after step 4.

### 5. Cloud setup (user performs account steps)

- Install the Claude GitHub App on the repo.
- Create a cloud environment: setup script installs Java 25 and Maven; network
  allowlist includes `central.sonatype.com`.
- Create labels: `in-progress`, `needs-human`, `ready-for-review` (the canonical
  triage labels already exist, see `docs/agents/triage-labels.md`).

Done when: a manual cloud session can build, test, push a `claude/` branch and open a PR.

Status: not started.

### 6. Pilot worker: #25

- Start one cloud session on #25: directory permission preservation per the decision.
- CI verifies on Linux; the user reviews and merges.

Done when: #25 is merged and closed; any friction in the worker path is fixed.

Status: not started.

### 7. Coordinator routine

- Instructions in `docs/agents/coordinator.md` so they change through PRs.
- Triggers: PR events (opened, labeled, synchronized) and the shortest schedule
  routines allow for issue changes. Exit immediately when nothing changed.
- The coordinator never edits code. It triages and splits issues, starts one worker
  per `ready-for-agent` issue, reviews PRs (`code-review` and `simplify` skills),
  moves labels, and posts `needs-human` questions with a checklist.
- Workers: one issue, one branch, one PR; `mvn verify` before review.
- Roll out in dry-run mode (comment intended actions only), then live.

Done when: the coordinator has taken one issue from `ready-for-agent` to a
merged PR without local involvement.

Status: not started. Verify routine limits (minimum schedule interval, GitHub event
caps) before writing the prompt.

### 8. TUI design pass (with the user, parallel to 6–7)

- Start from `docs/tui-design.md`. Audit at 80x24 and 120x30, prototype
  the changes, decide.
- Candidate list provenance: Browse should show which lists were loaded (bundled,
  shared), each list's resolved location, read status and freshness, without
  opening source diagnostics. Today the location shows only in Storage locations
  and per-candidate attribution only in details.
- Output: updated `tui-design.md`; #8, #15, #17 rewritten as small agent-ready tickets.

Done when: the user accepts the design and the tickets are `ready-for-agent`.

Status: not started.

### 9. Coordinator works the roadmap

Order:

1. Split umbrella tickets (#7, #8, #15, #17); the user checks each split's scope.
2. Simplification outside the TUI: #24 audit, then behavior-preserving PRs.
3. #11 integration verification.
4. TUI changes from the accepted design.
5. Remaining features (#5, #6, #10, #19, and what the splits produce).

Status: not started.
