# Lighten Design Decisions

This document records decisions that affect implementation direction. Add entries when a product or architecture choice is made, especially when an alternative is intentionally rejected.

## 2026-08-29: Use a library-oriented core

HomeLight will use a small presentation-independent reconciliation library with a thin Picocli CLI. It will not start as a framework-based application.

The core should remain usable by future CLI, GUI, and automation consumers.

## 2026-08-29: Keep integrations behind adapters

Configuration storage, Git, HTTP, and other external integrations belong behind narrow adapters. The reconciliation engine operates on desired state and inspected filesystem state, not on Stow, Git, HTTP, or a particular dotfile repository.

## 2026-08-29: Defer framework and plugin infrastructure

Dependency injection, runtime scanning, and a general plugin framework are deferred. They would add complexity to the safety-critical path and are not needed for the initial product.

## 2026-08-29: Design for native-image compatibility

GraalVM Native Image is a future aspiration. The project will favor explicit construction, standard Java APIs, isolated serialization, and minimal reflection. Native-image verification should be added before the architecture becomes difficult to change.

## 2026-09-07: Prefer guided CLI over desktop GUI

_Superseded by “Use a full-screen TUI as the primary human interface” below._

HomeLight will focus on a command-line interface with guided prompts rather than a desktop GUI (such as JavaFX).

HomeLight targets quota-constrained Linux workstations and remote environments where SSH and headless access are standard. Guided CLI workflows such as discovery in `init` retain a single-binary distribution with minimal GraalVM Native Image reachability friction.

Desktop GUI alternatives (JavaFX, Gluon Substrate, webview wrappers) are deferred because they introduce substantial build complexity, native graphics bindings, OS packaging overhead, and requirement for display forwarding over remote sessions.

## 2026-09-07: Combine non-interactive and guided CLI in a single binary

_Superseded by “Keep TUI and JSON automation in one binary without dual human presentation” below._

HomeLight will provide both non-interactive commands and guided prompts within the same executable.

The Picocli CLI layer is the primary entry point and routing mechanism. It supports automation through flags such as `--yes` and `--json`, and invokes prompts only for interactive workflows.

## 2026-09-07: Maintain a single Maven module with logical package boundaries

_Maven is superseded by “Build with Gradle Kotlin DSL” (2026-10-01). The single module with package boundaries stands._

The project will remain a single Maven module with clear package boundaries (`domain`, `reconcile`, `fs`, `config`, `cli`) instead of splitting into a multi-module Maven build upfront.

For a solo developer, logical package boundaries provide clean architectural separation and decoupled unit testing without the build maintenance, multi-POM configuration, and refactoring friction of multi-module builds.

## 2026-09-07: Use plain Java 25 and targeted libraries instead of application frameworks

_Java and the SnakeYAML/Jackson choice are superseded by “Move to Kotlin and kotlinx.serialization” (2026-10-01). Targeted libraries instead of a framework stand._

HomeLight will use plain Java 25 with targeted libraries (Picocli for command routing, JLine 3 / TamboUI for terminal interactions, SnakeYAML Engine / Jackson for serialization) rather than a full-stack application framework like Spring Boot 4 or Quarkus.

A filesystem utility does not require runtime dependency injection, classpath scanning, or server-oriented lifecycle management. Plain libraries ensure fast startup, low binary footprint, straightforward unit tests, and seamless GraalVM Native Image compilation.

## 2026-09-12: Use a full-screen TUI as the primary human interface

Running `homelight` starts one persistent full-screen TamboUI application. `init`, `config`, `plan`, `apply`, and `status` are screens within that application, and the corresponding command names deep-link to those screens.

The earlier guided inline CLI approach created separate short-lived terminal applications for plan display, conflict resolution, confirmation, and progress. That lifecycle made focus, rendering, and workflow continuity unreliable. A single application lifecycle and retained workflow state match the product's need to compare relocations, edit decisions, review an exact plan, and observe execution progress in place.

TamboUI remains the presentation toolkit because its high-level layout, widget, focus, and styling primitives allow a distinctive interface without rebuilding terminal infrastructure. It remains isolated behind the TUI adapter because its pre-1.0 API still changes between releases.

## 2026-09-12: Keep TUI and JSON automation in one binary without dual human presentation

The full-screen TUI is the only human-oriented presentation. Existing guided, inline, and plain-text human renderers will be removed rather than maintained as a compatibility layer.

JSON commands remain prompt-free automation interfaces. `--json` bypasses TamboUI completely; responses use versioned contracts and stable exit codes. `apply --json` requires `--yes`, which confirms an already-resolved plan but never supplies missing policy decisions. A non-interactive invocation without `--json` fails clearly.

## 2026-09-12: Apply the exact reviewed plan

The TUI retains the exact structured plan shown during review. Immediately before application, HomeLight preflights the plan's expected filesystem state. State drift marks the plan stale and requires re-planning. HomeLight never recomputes and substitutes an unreviewed plan after the user confirms application.

## 2026-09-12: Establish a HomeLight-specific visual language

The TUI must be intentionally designed around HomeLight's relocation workflows. It will not use a generic dashboard-card composition, gratuitous gradients, excessive borders, decorative clutter, or canned interface copy. Hierarchy, typography, spacing, color, keyboard behavior, empty and failure states, and narrow-terminal behavior are part of the product contract.

The complete visual language, color ergonomics, progress indicators, and screen layouts are codified in [docs/tui-design.md](tui-design.md).

## 2026-09-08: Keep initial reconciliation stateless and directory-only

The initial planner manages directories only. A configured file source is blocked until file relocation has a complete, separately designed state model.

HomeLight does not persist an ownership registry in the initial implementation. It recognizes an already-correct configured symlink structurally, creates absent targets, and requires an explicit conflict resolution before adopting or replacing unknown existing state. This avoids recovery, staleness, and lifecycle complexity while preserving fail-closed behavior.

## 2026-09-30: Stay on Java; ship Linux native binaries

_The Java part is superseded by “Move to Kotlin and kotlinx.serialization” (2026-10-01). The native Linux targets stand._

HomeLight stays on Java 25 and ships GraalVM Native Image binaries for Linux x86_64 (fully static, musl) and Linux arm64 (`--static-nolibc`, glibc 2.17+). macOS is a development platform, not a release target. A spike showed identical CLI and TUI behavior to the JVM across Oracle Linux 7 through Fedora 44, with 2–15 ms startup. See `research/native-image-spike.md`.

Rejected: Kotlin (same JVM and native-image constraints, little gain over Java 25). Rust was a viable alternative: smaller binaries, simpler cross-compilation, no native-image metadata, a mature TUI library. None of those blocked Java, and a port would cost about 13k lines including tests.

## 2026-09-30: Replace smallrye-config with snakeyaml

_snakeyaml is superseded by “Use JSON for configuration and candidate lists” (2026-10-01). Dropping environment and system-property overrides stands._

SmallRye's `@ConfigMapping` generates classes at runtime, which Native Image cannot do; the only workaround depends on SmallRye internals. Configuration is parsed with snakeyaml, which is already a dependency, behind `ConfigurationLoader`. Environment and system-property overrides are dropped: they were an unused side effect of SmallRye's default sources. `${USER}` expansion in paths remains.

## 2026-09-30: Preserve directory permission bits during staged relocation

Every published directory keeps the nine POSIX permission bits of its source. Where the target filesystem cannot represent them, publication is refused rather than silently widening access. Ownership, ACLs, timestamps, extended attributes and special mode bits remain out of scope.

Rejected: accepting filesystem defaults with a documented limitation. Relocated home directories often move to shared storage, so widening `0700` to `0755` exposes private data.

## 2026-09-30: Run agent work through a cloud coordinator

A Claude Code routine acts as coordinator; worker agents run as cloud sessions, one issue and one PR each. GitHub labels and comments carry all state, so no session depends on another's memory. GitHub Actions runs CI only. The human decides product and UX questions, accepts user-visible PRs, and performs every merge to `main`.

Rejected: running agents in GitHub Actions. Long agent sessions would consume private-repo Actions minutes and require a Claude credential stored as a repository secret.

## 2026-09-30: Keep candidate lists on the filesystem

Shared candidate lists stay filesystem paths, typically on a NAS or NFS mount the target machines already share. HomeLight does not fetch lists over HTTP or Git. A list kept in Git is cloned by the user and referenced by path.

Rejected for now: built-in HTTP or Git sources. They add network failure modes (proxies, TLS, offline), caching and staleness rules, and for Git either a large dependency or credential handling. Revisit if machines without a shared filesystem need a curated list; the parser and merge already accept any source that produces a snapshot.

## 2026-09-30: Relax candidate-list strictness

_Since the move to JSON (2026-10-01) there are no aliases to limit, and kotlinx.serialization replaced the planned Jackson binding. Values must have the declared JSON type; see “Read JSON configuration strictly”._

Candidate lists keep the protections that matter for shared, untrusted input: size, nesting depth, string length, record count and alias limits, and rejection of unknown and duplicate keys. Exact YAML tag checks, the single-document rule and format-specific error wording are no longer requirements. Scalars read as text, and null, empty or blank values count as absent.

This lets configuration and candidate lists share one reader, and allows a standard binding library (Jackson, roadmap step 4b) to replace hand-written parsing.

## 2026-10-01: Move to Kotlin and kotlinx.serialization

HomeLight moves from Java 25 to Kotlin and uses kotlinx.serialization instead of Jackson. This supersedes the Java direction in “Stay on Java” and the Jackson adoption planned as roadmap step 4b. Native Linux binaries remain the release targets.

Why:

- Readability: less ceremony for the same code.
- Null safety in the type system instead of `Optional` and conventions.
- Immutable data classes with `copy`, and sealed types with exhaustive `when`.
- kotlinx.serialization generates serializers at compile time: no reflection metadata, and no JDK XML stack in the native image. The Jackson trial (PR #39, closed) grew the binary by 54% because Jackson pulls in the XML stack, and binding YAML still needed a hand-written pre-pass.

How: a mechanical conversion first (build, then main code by package group, then tests), with a mixed Java/Kotlin build during the migration and the Java tests guarding behavior until they are converted. An idiomatic pass follows. JSON output stays byte-identical, with one recorded exception: see “Write JSON control-character escapes in lower-case hex”. Work runs on the `kotlin-migration` branch; the epic is #40.

The earlier rejection of Kotlin weighed only the shared JVM and native-image constraints. It did not weigh null safety, data classes or compile-time serialization.

## 2026-10-01: Build with Gradle Kotlin DSL

The build moves from Maven to Gradle with Kotlin DSL (`build.gradle.kts`). Native images are built with the official GraalVM `org.graalvm.buildtools.native` plugin. Kotlin's compiler plugins (kotlinx.serialization) are first-class in Gradle, and the build script uses the same language as the code.

## 2026-10-01: No dependency on GraalVM internals

HomeLight never depends on GraalVM internals: no `@Substitute`, `@TargetClass` or other svm APIs. Native-image support uses only supported mechanisms: reachability metadata, build arguments and the build plugin. Internals change between GraalVM releases and would tie upgrades to them.

## 2026-10-01: Use JSON for configuration and candidate lists

Configuration and candidate lists move from YAML to JSON, parsed with kotlinx.serialization (roadmap migration phase K6b, #49). Nothing has been released, so existing YAML files are not migrated.

Why:

- Precise parser errors and duplicate-key detection.
- No aliases or tags to guard against in shared, untrusted lists.
- First-party kotlinx.serialization support: no third-party YAML library and no hand-written reader.
- One format for input and output; `--json` responses are already JSON.

Trade-off: hand editing loses YAML's comfort. Allowing comments and trailing commas offsets part of that, and the TUI writes the configuration anyway. Curated candidate lists remain hand-edited.

## 2026-10-01: Write JSON control-character escapes in lower-case hex

JSON responses are encoded by kotlinx.serialization, which writes `\u001f` where the earlier jackson-core writers wrote `\u001F`. This affects U+000B, U+000E, U+000F and U+001A–U+001F in paths, reasons and messages (K6.4, PR #61). JSON `\uXXXX` escapes are case-insensitive, so decoded values are identical. All other escaping is unchanged.

Rejected: a custom string encoder to keep upper-case hex. It would add code for a difference no JSON parser sees.

## 2026-10-01: Read JSON configuration strictly

_Superseded in part by “Let kotlinx.serialization own the file format” (2026-10-02): repeated keys keep their last value, and kotlinx now reports missing keys and invalid policy values._

Configuration and candidate lists are decoded into `@Serializable` file-shape classes and then converted into domain types (K6b, PR #62).

- Unknown keys and values of the wrong JSON type are rejected; a number or boolean is not read as a string.
- Duplicate keys are rejected by a scan of the accepted text, because kotlinx.serialization keeps the last value.
- `//` and `/* */` comments and trailing commas are allowed.
- Decoding errors give line, column and the dotted key path in kotlinx.serialization's wording. Rules checked after decoding (missing keys, policy values, path and limit rules) give the key path without a position, because decoded classes keep no offsets.
- No separate nesting-depth limit: the fixed file shape rejects deeper values as a wrong type before reading them.

Error messages changed wording from the YAML reader; scripts must not parse them.

## 2026-10-01: Generate picocli reflection metadata from compiled classes

picocli's annotation processor cannot see Kotlin sources. A Gradle task runs picocli-codegen's `ReflectionConfigGenerator` on the compiled classes on every build and writes `reflect-config.json` where the processor did, so Native Image metadata for the commands cannot go stale.

Rejected: kapt (its Java stubs failed on the `@JvmRecord` classes during the migration, and kapt is in maintenance mode) and a hand-written metadata file (it would drift from the commands).

## 2026-10-01: Keep Java whitespace semantics for validation

Kotlin's `isBlank` and `trim` treat no-break spaces as whitespace; Java's `String.isBlank` and `strip` do not. Validation of configuration and candidate text keeps Java's semantics through the helpers in `JavaStrings.kt`, so the move to Kotlin did not change which values are accepted.

## 2026-10-01: Keep threads and locks during the Kotlin migration

The migration kept the existing platform threads, locks and executors. Coroutines are not adopted; #10 decides the concurrency mechanism later.

## 2026-10-02: Let kotlinx.serialization own the file format

One set of `@Serializable` classes defines the configuration and candidate-list formats for both reading and writing (#79). Hand-written value parsing is gone: required fields are non-null, optional ones have defaults, and policies and `advice` are `@Serializable` enums with `@SerialName` constants. kotlinx rejects unknown keys, missing required keys, wrong types and unknown enum or policy names. The loader keeps only domain rules: path expansion and blank paths, target derivation, staging-root under target-root, the path override, and the candidate limits and path validation. A blank string is a value, rejected only where a domain rule says so. Output uses `encodeDefaults = false`, so written files hold only what is set, in declaration order.

All three policies, `when-adopting-target` included, are plain strings:

```json
"when-adopting-target": "archive-source",
"archive-root": "~/archive"
```

`source-archive-root` becomes the optional relocation key `archive-root`. In the domain `Relocation.archiveRoot` is non-null and defaults to `.homelight-archive` beside the source. Archiving is an atomic rename, so the root must be on the source's filesystem, and beside the source it almost always is. With a root always present, archive-source without a root cannot happen, and review can always offer archiving when the policy prompts. The plan still blocks an archive path that overlaps the source or target. Paths stay as written in the file classes and expand only when converted to domain types, so a file read and written back keeps `~/…` and `${USER}`. A relocation using the default root is written without `archive-root`.

Considered: a sealed `when-adopting-target` (`prompt`, `discard-source`, `archive-source` with its root) in the domain and the file, written as an object with a `policy` discriminator. It needed the experimental `@JsonClassDiscriminator`, gave errors without position or path when `policy` was missing, and still needed a root on `prompt` to offer archiving at review. Also considered: a generic domain `Policy<C>` (`Prompt` or `Decided(choice)`) for all three policies; deferred to the simplification pass.

- A repeated key keeps its last value, as kotlinx does. The text scan that rejected duplicates is removed.
- Error wording is kotlinx's, with the dotted path. Each file class and enum has a `@SerialName`, so messages name `relocation` or `when-adopting-target`, not Kotlin classes. kotlinx gives no offset for missing keys or unknown enum values, so those errors have a path but no line and column.
- Writing a configuration drops comments. kotlinx can read comments but has no model that keeps them; the TUI is the primary editor (#17).

Nothing has been released, so existing files are not migrated.

Rejected: a hand-written pre-pass or validator to restore the earlier error details (closed PR #39 tried this with Jackson). It would reintroduce the parsing code this decision removes.

## 2026-10-03: Stay with TamboUI; Kotlin TUI frameworks reviewed

HomeLight stays with TamboUI, now pinned to the 0.5.0 release from Maven Central (#88). A survey of Kotlin TUI frameworks found none that meets our requirements: a full-screen alternate-screen app with layout widgets, running in a static native binary.

- **Mosaic 0.18:** no alternate screen (#455), only basic layout widgets, coroutines required, native image untested (#764). Its bundled JNI `.so` likely cannot load in a fully static musl binary. No release in 13 months.
- **Kotter 1.4:** inline only, no alternate screen (#156), GraalVM support self-described as incomplete, 4 contributors.
- **Mordant 3.1:** the best native-image story (graal-ffi), but it is an output and input library, not a full-screen app framework.
- **Lanterna:** needs reflection configuration, and LGPL-3.0 in a static binary is an open question.
- **ratatui-kotlin ports:** immature.

Issue numbers in this list are the upstream projects' own.

Revisit if Mosaic ships alternate-screen support and CI-tested native-image support, or if TamboUI stalls: no release for about 6 months, or a native regression upstream won't fix.

## 2026-10-04: How design and simplification decisions are made

Every proposal names the first rung that answers it: (1) does it need to exist at all, (2) reuse existing code, (3) Kotlin or JDK standard library, (4) a TamboUI or platform feature, (5) an existing dependency, (6) only then minimal new code. For the TUI, use TamboUI as much as possible and delete our own equivalents, even when that is an app-wide change. Anything dropped or deferred is recorded as `[skipped: X, add when Y]` in the decision and its ticket. HomeLight never files issues or pull requests on upstream projects; TamboUI gaps are worked around in our code.

## 2026-10-04: One configuration editor for creating and editing

`homelight init`, `homelight config`, `i` (no configuration) and `e` (from Workspace) open one Configuration screen. It edits the file's own shape (`HomeLightFile`/`RelocationFile`), validated by the loader's conversion, so path rules have one owner and `~`/`${USER}` survive a round trip. Layout is master-detail like Workspace; "Storage locations" is the first list entry, so there is no separate locations step. A blank Target means "derived from the target root", as a blank Archive root already means the default. Unsaved changes are the draft compared with the loaded file. Save creates a new file directly; replacing an existing file asks for `y`, notes that comments are not kept, refuses if the file's bytes changed since load, and writes atomically. Save returns to Workspace, checks again and says the next step. Saved and new relocations are the same kind of row, so Browse has two markers, `[ ]` and `[x]`, and Space toggles.

The configuration gains an optional `source-root`, default `~`. Targets derive from a source's position under it; a source outside it needs an explicit target. New rows start with the source root filled in, and Browse scans under it.

This removes setup's own relative-to-root rows and path rules, the creation-only "configured" join with its `[=]` state (never reachable in production: `SetupView` always passed an empty list), and the separate locations mode.

- `[skipped: per-row changed/new markers, add when users lose track of edits in long lists]`

Rejected: a second editor for existing files beside create-only setup (two editors for the same rows); extending setup's relative-to-root rows to existing files (needs absolute/relative mapping and makes the dead outside-root case real); deferring existing-file editing; a source root kept only in the editor (saves typing, nothing else).

## 2026-10-04: Workspace choices are for one apply; rules are saved on request

A choice made in Workspace applies to the next apply only and is cleared by any re-check, save or apply. `s: Always do this` saves the choice as the relocation's rule through the Configuration save path, after a dialog that explains it. This supersedes the product-spec line that durable decisions are written before application, and drops the logic that kept choices across a re-check (`DiscardedChoice`, `DiscardReason`, `Replanned` and the "draft choices discarded" banner).

- `[skipped: keeping one-time choices across a re-check, add when users re-check often with many open choices]`

Rejected: keeping choices across re-checks (code for a rare case); saving every choice as a rule (a one-off "delete both" would become permanent for `apply --json --yes`); a per-choice "also save" toggle.

## 2026-10-04: A missing rule means "Ask each time"

The three rule fields are non-null and default to `prompt`, which is omitted when written. The screen shows a missing and an explicit `prompt` the same way: "Ask each time". This reverses the part of #65 that showed `Default (prompt)` separately; the other #65 wording rules stand. A file that spells out `"prompt"` loses that line on its next save, with the same meaning. The editor merges `when-source-and-target-directories-exist` and `when-adopting-target` into one **Both exist** choice whose values read as outcomes (Ask each time; Keep target, delete source; Keep target, archive source; Keep target, ask about source; Leave both as they are; Delete both, start empty); the file keeps both keys. On screen a policy is a "rule".

- `[skipped: showing a missing rule apart from an explicit prompt, add when a global defaults layer exists]`
- `[skipped: generic Policy<C>, add when a fourth rule appears or code needs to treat all rules the same way]`

Rejected: `Policy<C>` (each rule would need a hand-written serializer to keep the file's plain strings, the cost the 2026-10-02 decision already rejected).

## 2026-10-04: TamboUI owns focus, fields, choices and dialogs

The TUI uses TamboUI's `FocusManager` with fixed element ids, its text inputs (`TextInputState`), `Select` for every fixed-value choice, `dialog()` for dialogs, `LineGauge` and `Spinner` for progress. One app key handler, keyed by the focused id, handles what elements leave: Esc goes back one level (field, list, screen) and never exits. While a dialog is open the screen behind renders non-focusable, because TamboUI has no inert or focus-scope option and handles Tab before any element. Key bindings switch from vim to TamboUI's `standard` set, which removes the text-field-first exception, the paging guard and the risk that a TamboUI fix for binding-aware text input would make `x` delete. Tab moves through every control in order; ↑/↓ also move between form fields. A throwaway prototype confirmed all of this (escape, typing in fields, dialogs trapping keys, testability, quit).

- `[skipped: TamboUI FormElement, add when it supports per-field key handling and a dialog on top]`
- `[skipped: TreeElement for Browse, add when its selection can follow an item rather than a position]`
- `[skipped: mouse support, add when users ask to click; mouse capture disables plain drag-to-copy]`
- `[skipped: Tab completion for paths, add when typing paths becomes a complaint]`

Rejected: keeping our own focus and field code with only TamboUI text inputs (keeps code TamboUI provides); Tab switching panes only (TamboUI owns Tab, so it would mean keeping our focus code).

## 2026-10-04: Dialogs for short questions, screens for work

A dialog asks one question or confirms one action over the current screen and returns to exactly where the user was. Dialogs have a double border in their own color, are centered and sized to content, and never cover the header or help lines. Multi-step work is a screen with one plainly labelled way back.

- `[skipped: dimming the whole screen behind a dialog, add when the border and lost focus are not enough separation]`

## 2026-10-04: Harbor palette on HomeLight's own dark background

The TUI paints its own dark background and uses the Harbor palette as exact RGB colors, named by role in one palette file; terminals without full-color support fall back to the nearest basic color per role. Color may carry meaning alone when the same information is also on screen another way. This replaces "inherit the terminal background" and "color never replaces words". The palette was chosen from three candidates rendered on real screens.

Rejected: the terminal's 16 colors only (plain, and drifts across 88 call sites); inheriting the terminal background with separate light and dark shades selected by a setting (two shade sets and a setting).

## 2026-10-04: Browse shows its lists and drops stale evidence

Browse always shows each candidate list's location, count and the shared file's modification time. Discovery no longer keeps previous results on screen while checking again, no longer distinguishes waiting from checking, and no longer remembers which lists once suggested a row. Deadlines, the single in-flight shared read, chained runs and the bundled-first batch stay (#84, #10). When both lists name a directory, the candidate list's app and advice win. Row notes use plain words.

- `[skipped: showing previous results while checking again, add when re-checks are slow enough that blank rows annoy users]`
- `[skipped: per-row list history, add when users need to know a list used to suggest a row]`

Rejected: showing "lists disagree" on rows where the lists differ (two pieces of advice per row).

## 2026-10-04: Apply progress does not follow running steps

With several relocations running at once, the selection stays where the user put it. Each relocation row shows its own status, the tree updates in place, a line gauge and one count line summarize progress, and on finish the selection moves once to the first failure or last completed action. Action names use plain words.

Rejected: following the first running step (pulls the selection away while reading); following the most recent step.

## 2026-10-04: Application-layer cleanup

`PlanSummary` is deleted (one reader compared two of its fields). `PlanModel` folds into the loaded evaluation. Screen text moves out of `DecisionChoice` and `PlanBadge` into the TUI's wording file. `HomeLightSession` stops being `open`; TUI tests drive real sessions. Review's pending steps are built in one place.

- `[skipped: merging the Missing and Unconfigured evaluation states, add when the JSON contract is revisited]`

## 2026-10-04: TUI design document holds current rules only

`tui-design.md` holds the current rules; history lives here and in git. Screens are checked at 80x24 and 120x30. Displayed paths show the home directory as `~`.

- `[skipped: 200x50 checks, add when a wide-terminal layout bug appears]`

## 2026-10-04: Only environment failures fail an action

The executor reports an action as failed only for an I/O failure or an expected environment failure: state drift, a staging root on another filesystem, or a filesystem without POSIX permissions. Any other exception is a bug and propagates out of `execute`, after staging cleanup and once running relocations finish. Apply then shows the exception's message as a diagnostic, beside the step that was running. Executing a `Blocked` action is a bug, because `execute` refuses plans that contain one.

- `[skipped: a stack trace for a bug that escapes execute, add when a bug report needs more than its message]`

## 2026-10-04: Bugs are reported as internal errors

A bug is any exception that is not a configuration, I/O or environment failure. It reads `Internal error (please report): <ExceptionType>: <message>`, with no stack trace. The CLI prints that one line on stderr and exits 70 (`EX_SOFTWARE`); a configuration error still prints its message and exits 1. `apply --json` still prints the result it has on stdout before the line, so automation keeps the evidence of what ran. Configuration evaluation turns only a `ConfigurationException` into an invalid or missing configuration, so a bug in inspection or planning is no longer shown as an invalid file. In the TUI, a bug while checking the configuration ends the TUI, restores the terminal and prints the line; a bug during apply shows the line as Apply's diagnostic, and the line is printed again with exit 70 when HomeLight exits. A terminal failure still prints `Failed to run HomeLight TUI: <message>` and exits 1.

- `[skipped: printing the stack trace, add when a bug report needs more than the exception type and message, e.g. behind a debug option]`

## 2026-10-04: Existing ancestors may be symlinks; overlap compares real paths

An existing ancestor of a path HomeLight works on may be a symlink to a directory, such as `/var` on macOS or `/home -> /var/home` on Fedora Atomic. Every directory HomeLight creates must be real, and so must the source, the target and the staging root themselves; their guards still inspect them without following links. `EnsureDirectory` follows the same rule, so an existing symlinked parent is accepted rather than refused.

Overlap compares each path's real spelling: the real path of its longest existing ancestor plus the remaining components, never following the path itself, because a source may be the link HomeLight created. The loader checks configured relocations this way and refuses overlap that appears only through a symlink; overlap visible as written stays a plan diagnostic. The executor's grouping compares claims this way at execute time. The planner stays pure: it runs again for every Workspace choice, and filesystem reads there could block on a slow mount.

- `[skipped: real-path check of an archive path against its own relocation, add when an archive root reached through a symlink is reported]`
- `[skipped: re-checking aliased overlap in preflight, add when ancestor links are seen to change between review and apply]`
- `[skipped: removing toRealPath() from cli and application test fixtures, add when those tests next change]`

Rejected: resolving in the planner (I/O in a pure step that runs on each choice); storing real paths in the configuration (links and displayed paths would change spelling).

## 2026-10-04: One staging operation per target

Staged publication uses one operation per target (#130, decisions D1 and D3 of the executor simplification). In the staging root, `operation-<sha256 of the target's real spelling>` is the staged copy and `operation-<same>.lock` its lock file, which is never deleted. A process claims the key in an in-process set, takes the file lock without waiting, clears whatever is at the copy's name (under the lock it can only be an earlier run's leftover), then copies, verifies and publishes; closing deletes the copy, releases the lock and then the key. Different targets never open each other's files, so relocations that share a staging root always run concurrently. A target already being staged, by this process or another, is an environment failure. The atomic-move probe is gone: the same-filesystem check rules out a cross-device rename, and `publish` uses `ATOMIC_MOVE`, which fails before the source changes.

This replaces UUID-named operations, the `target` marker, the claimed-names set, stale cleanup of other operations, the lock probe and the lock-unsupported fallback. It also fixes four bugs: a leftover containing a symlink is now cleared (B2); a staging cleanup failure is added to the original failure instead of replacing it (B4); a failure after the copy was renamed into place reports `failed-recovery`, not `unresolved` (B5); and no probe file can leak (B8).

- `[skipped: sweeping other targets' leftovers, add when abandoned staging copies are reported]` A target's leftover is cleared the next time that target is staged, and it stays inside the staging root.
- `[skipped: deleting per-target lock files, add when users object; safe deletion is defeated by inode reuse]` One empty `.lock` per target stays behind.
- The same target staged by two processes now fails fast with an environment failure, instead of the loser failing on drift after a full copy.
- The on-disk layout changes. Nothing has been released, so there is no migration.

## 2026-10-04: A source is replaced by its link in atomic steps

Replacing a source directory with its link (#132, bug B6) renames the source aside within its own parent, moves the link into its place, and only then deletes the renamed tree. Each step is one rename, so a crash leaves the whole source at its path, or nothing there and the whole source aside, or the link there with the rest of the source aside. It never leaves a partial source next to the target, which the next plan used to report as "both exist" and a saved `discard` rule would then delete together with the target.

The source is set aside as `<source parent>/.homelight-replaced-<source name>-<SHA-256 of the target's absolute normalized path>`. A directory at exactly that name is recognized only while the source is a link to that target and the target is a directory: the plan then deletes it (a `delete-directory` step with a `REPLACED_SOURCE_LEFT` warning) and never treats it as a source. The hash ties the name to the target that holds the content, so after the relocation moves to another target the name no longer matches. While the source is absent the set-aside tree is kept, and the usual only-target plan applies; once the link exists, the next plan deletes it. While the source is a directory again (an application may recreate it), the relocation is blocked until the user deals with the set-aside tree. Deletion still never follows links and never changes permissions, so an entry that cannot be deleted now stays in the set-aside tree, beside the link, rather than in the source.

- `[skipped: finishing an interrupted replacement while the source is absent, add when users ask why an only-target conflict follows a crash]` The plan asks for the adopt-target decision instead, and keeps the set-aside tree until the link exists.
- `[skipped: recognizing a published target whose source was not yet set aside, add when a crash between publication and replacement is reported]` A crash there leaves two whole directories, reported as "both exist" exactly as a failure after publication already is (B5). A saved `discard` rule would delete both whole copies; no name or marker distinguishes this case from two directories the user made.

## 2026-10-05: Functions return their results

Functions no longer take a mutable collection to fill; they return what they build, and an accumulator stays local to the function that builds it. This was a habit left from the Java port, not a design choice. Preflight, candidate parsing, candidate metadata failures, available choices, independent groups and per-action execution now return their results; parent walks use `generateSequence`. TUI line builders follow the same rule as their screens change (#110, #111, #115): they return `List<Line>`, or a small `Anchored(lines, anchor)` when they also need an anchor.

Moving `progress.finished` for a completed action out of the executor's `try` also fixes a bug: a listener that threw `IOException` there recorded the action twice, completed and then failed. An exception from `finished` now propagates like any other listener bug.

Legitimately mutable state stays: lock-guarded monitors, the executor's `halted` flag, `mapBounded`'s slots, staging keys, the JDK file visitor, picocli fields and `DetailViewport.wrap`.

- `[skipped: rewriting ensureDirectories's walk, add when it changes for another reason]`

## 2026-10-05: The archive destination is the source's name under the archive root

Archive-source moves a source to `<archive root>/<source name>` (rung 6, minimal new code), instead of nesting the source's full absolute path under the root, which was unique but hard to read (#142). When that name is taken, by an existing entry or by another configured relocation whose plain destination has the same real spelling, the name becomes `<source name>-<first 8 hex digits of the SHA-256 of the source's real spelling>`. The suffix reuses `sha256Hex` and the real-spelling rule from #128 (rung 2). The relocation rule depends only on the configuration, so two relocations with the same source name get different names whichever archives first; the suffix depends only on the source, so the same state always plans the same destination.

Inspection chooses the name, because whether it is taken is a filesystem fact; the planner stays pure and uses the inspected path. Its guards are unchanged: the destination must be absent, outside the source and target, and archiving is an atomic rename, so it fails before changing anything on another filesystem. A suffixed name that is taken too blocks archiving.

- `[skipped: a counter or further suffix when the suffixed name is also taken, add when users hit it; it means the same source was archived before and that archive is still there]`
- The on-disk layout of archives changes. Nothing has been released, so there is no migration.

Rejected: always adding the suffix (unreadable in the common case); a timestamp suffix (a re-check would plan a different path); giving the plain name to the first relocation in file order (reordering the file would change where a source goes).

## 2026-10-05: Workspace Details say the decision once and warn only for deletions

Workspace Details (#110) replace the "Your rule" and "Your choice (not saved)" pair with one `Decision:` line that says what will happen and where it comes from, for example `Decision: ask each time (your configuration)`. Rows no rule governs (Move, Link, In sync, blocked, can't read) have no such line. `Archive:` appears under Paths only when the rule or choice archives the source; while archiving is only offered, the archive choice names its destination (#107). A blocked row now shows the planner's reason after `Will do`, which already pointed to "the problem below".

`⚠ This deletes data for good.` and the summary's `deletes data` count cover only data that is not kept elsewhere: deleting a source while keeping the target, deleting both, or deleting what an interrupted replacement left behind. A verified Move no longer carries a warning: it replaces the source with a link only after the copy is checked, and its `Will do` line already says so. Review keeps its per-step ⚠ on every step that deletes or replaces something, because it lists exact steps.

- `[skipped: plain-language reasons for blocked rows, add when the planner's reasons are reworded for JSON output too]` The reasons are shared with JSON, so Details shows them as written.
- `[skipped: a softer Review warning for a verified "Replace source with a link" step, add when the Review walkthrough finds it alarming]`
- `[skipped: keeping the Decision line in view when Details takes focus, add when users miss it]` Focusing Details still scrolls to the focused choice (#108).
- `[skipped: List<Line> or Anchored builders in Setup and Browse, add when #114 and #115 replace those screens]`

Rejected: a softer warning line on a Move ("Replaces the source with a link after a verified copy."), because it repeats the `Will do` line.

## 2026-10-05: Quitting asks before forgetting one-time choices

From the #110 walkthrough, folded into #111. `q` with one-time choices that are not applied yet opens a dialog that says how many there are and that quitting forgets them; `n` or Esc goes back. A plan with no one-time choices quits at once, because the next run plans it again. Details call a choice `(your choice, this run only)`, matching the dialog; "saved" is kept for rules saved with `s` (#116).

Apply progress (#111) keeps the plan a tree by starting relocation rows at the left edge with their mark (`✔ ~/.cache/uv`), while action rows keep the pointer slot (`❯ ○ Replace source with a link ⚠`). Actions sit two cells under their relocation, as before, and the longest label still fits at 80 columns beside the scrollbar's cell. A deeper indent would cut it.

- `[skipped: "or s to always do this" in the quit dialog, add with #116]`
- `[skipped: indenting action rows more than two cells, add when the plan list is wider at 80 columns or its rows become one line each]`

## 2026-10-05: Help is a screen with two tabs, and the user guide is its only text

Walkthroughs of #127, #136, #140 and #144 asked about the order of the workflow and its concepts, which the two help lines cannot say (#146). `?` opens a full-screen Help screen from any screen, and F1 opens it everywhere, text fields included, where `?` types and the help lines offer `F1: Help`. It has two tabs (TamboUI `TabsElement`, rung 4):

- **This screen**: the place (such as `Configuration › Target root`), one purpose line for the screen's state, `Step: Configure › Workspace › Review › Apply › Results` with the current step bold in the focus color, then `Keys on <place>` and "They work after you go back (Esc or q). In Help they do nothing.", then the keys for the current focus in two groups, "Move around" and "Do". Each key shows a description that names what it acts on and whether it asks first (`↑/↓  Select a relocation`, `y  Apply the plan; this changes files on disk`); the help lines keep the short label. One `KeyHint(keys, action, description = action)` holds both, so they share one source. This framing was option B of a wording pass after the walkthrough, which found the bare labels unclear out of their screen and the keys easy to misread as working inside Help.
- **Guide**: `docs/user-guide.md`, rendered with TamboUI's Markdown element.

Tab and ←/→ switch tabs and each keeps its scroll; Esc, `q`, `?` or F1 return exactly where the user was; an apply keeps running behind. From the empty Workspace before there is a configuration file, Help opens on Guide, so a first run starts by reading it; that Workspace says `New to HomeLight? Press ? to read the guide.` From Configuration it opens on This screen. The tab bar shows the open tab bold in the focus color and the other dim. The help lines drop every scroll key while the open tab has nothing to scroll. Configuration's field reads **Suggestion list (optional)**, so the guide's name for it is true now; its help text and the configuration key stay for #115 and #114.

This reverses the first version of #152, which rejected a separate help screen. That version was a `?` dialog with five steps and three "key ideas" kept in `Wording.kt`, plus a separate `docs/user-guide.md` and a test that the two matched. The walkthrough rejected it: the dialog never said what HomeLight is, used the internal name "Setup", and pointed to a Markdown file that users of the native binary never see. A second walkthrough split the screen into tabs, so the keys and the guide each get the whole pane, and asked for F1 and a product-first guide.

- The guide is written for people using HomeLight: what it does, how to use it (one section per journey, "Free space on this machine" today), suggestion lists, words to know, undo, then reference. It takes over the Scripting section #19 put in the README while no guide existed. The feature is the **suggestion list** ("the built-in list", "your list"); the on-screen and configuration-key renames follow with #115 and #114, and until then the guide shows the current key. A test fails if the guide names tickets or pull requests or has a line over 78 columns.
- The guide is packaged as a resource and rendered by `MarkdownElement` (`tamboui-toolkit-markdown`) inside a `DetailViewport` pane that scrolls it and draws the scrollbar (rung 2). This screen is Markdown built from code, so both tabs render the same way. `homelight guide` prints the guide; its online address comes from one constant, `main` for a `-SNAPSHOT` version, else the `v<version>` tag, and `homelight --help` ends with it.
- Native Image needs CommonMark's `org/commonmark/internal/util/entities.txt` registered for any HTML entity. The guide has none now, so a JVM test renders an entity fixture and checks that every registered resource exists; the native TUI test opens both tabs.
- TamboUI moves focus on Tab before any handler sees it, so the open tab follows focus: the open tab's pane has that tab's id and the tab bar has the other's.
- HomeLight captures the mouse, for its wheel only (user decision). The walkthrough saw scrolling switch Help's tabs at an edge, in WezTerm on macOS with a MacBook Pro trackpad. Without capture, a terminal in its alternate screen turns the wheel into arrow keys (alternate-scroll, on by default in WezTerm), and WezTerm sends a horizontal wheel event as ←/→ (`term/src/terminalstate/mouse.rs`, `mouse_wheel`), so a trackpad's sideways drift switched tabs. The scroll keys themselves do nothing at an edge. Options considered: a timing guard that ignored ←/→ within 150 ms of another arrow (tried in this PR; unpredictable, and blind to arrows a list takes itself); Tab alone switching Help's tabs (also tried; it fixed only Help and made Help's keys differ from the rest); mouse capture. Capture won because it fixes the cause on every screen and keeps the keys consistent: TamboUI then reports wheel up, down, left and right as separate wheel events, never as keys. Wheel up and down scroll the pane under the pointer, or move a list's selection, and never change focus or a tab; sideways scrolling, clicks, drags and taps do nothing, because HomeLight's handler takes every mouse event before TamboUI's click-to-focus. TamboUI turns capture off when the runner closes, on normal exit, Ctrl+C, an internal error and its shutdown hook; the native TUI test checks the log for it. Selecting text now takes the terminal's bypass modifier (Shift-drag in WezTerm and Ghostty, Option-drag in iTerm2), which the guide says once. This supersedes `[skipped: mouse support, …]` from 2026-10-04 for the wheel only.
- The focused pane has a thick border (`┏━┓`) and the others a plain one, so focus shows without color. Lists sit in a TamboUI panel for this, because the list element offers only a rounded border.
- Each screen's help is a `ScreenHelp(name, purpose, step, navigation, commands)` built in one function (`WorkspaceView.screenHelp`, `ApplyView.screenHelp`, `SetupView.screenHelp`, `CandidateBrowser.screenHelp`). The help lines show the hints marked for them and Help lists all of them, so the two cannot disagree.
- `q` on Help goes back, like Esc, `?` and F1, as in less, man, htop's help and lazygit's help: it never quits and never opens a discard question. The help line names where it goes, `Esc/q: Back to <screen>`, from the same place as the This screen pane's title. An earlier push passed `q` to the screen behind; the walkthrough found that quitting from Help was wrong. Ctrl+C still quits from Help through the usual path (user decision), so unapplied choices, a Configuration draft and a running apply still get their question.
- The current step and the open tab are bold as well as in the focus color, so they stand out without color. HomeLight keeps bold in the basic palette and neither it nor TamboUI drops styles for `NO_COLOR`, so no extra marker such as `[Workspace]` is needed.
- Names users see: `[Setup]` becomes `[Configuration]`, and the warning badge `[Check]` becomes `[Warning]` (it clashed with "Check again"). `init` says "Create a configuration file." The README no longer lists `homelight config` until #114 adds it.
- Setup's relocations table says `b: Browse` instead of `b: Browse candidates`, so the line and `?: Help` fit 80 columns.
- `[skipped: tying key handlers to their listing, add when a walkthrough finds a listed key that does nothing]`
- `[skipped: first-run tour, add when walkthroughs show Help is not found]`
- `[skipped: PageUp/PageDown and Home/End in Review's Action details, add when long action details are reported]` Help lists only keys that work.
- `[skipped: click to focus or select, add when users ask]`
- `[skipped: "Leave" group for q/Esc, add when a walkthrough still misreads q or Esc after the descriptions]`
- Esc from Configuration's first fields closed it without asking, against this document's rule that Esc-to-close asks before discarding. It now opens the discard question when anything was typed or a relocation exists, and closes at once otherwise; its description says so.
- Configuration's `s` saves only a new file and never replaces an existing one, so its description says that rather than "asks before replacing".

Rejected: a `?` dialog with its own steps and key ideas (the first version of this PR; it duplicated the guide and still did not say what HomeLight is); one long Help pane with the keys above the guide (the second version; the keys pushed the guide off the first screen); `1`/`2` for the tabs (they are the Workspace and Review keys); listing keys by hand beside the help lines (two lists that drift); a hand-written Markdown renderer (kept as the fallback if TamboUI's failed in Native Image).

## 2026-10-05: Scripting keeps today's JSON commands, versioned

#19 is trimmed (user decision). The JSON commands already share evaluation, planning and `ReviewedExecution` with the TUI, and CI's native comparison depends on them, so they stay. Most of the original scope is not needed now. What remains: `plan --no-color`, parsed but never read, is removed; `status --json`, `plan --json` and `apply --json --yes` start with `"schema": 1`; the README documents the three commands, `--yes` and exit codes 0, 1, 2 and 70. `status --json` now has one shape, configured or not: `{"schema": 1, "configured": <bool>, "configPath": "...", "relocations": [...]}`. A configured status used to be a bare array, which has no first field. No one scripts against it yet, so the break costs nothing, and one shape needs one response class.

- `[skipped: one shared envelope for every outcome across commands, add when someone scripts against HomeLight and needs it]`
- `[skipped: JSON errors on stdout (config errors, internal errors stay one stderr line), add when a script needs to parse them]`
- `[skipped: config validate --json, add when a script needs validation without planning]`
- `[skipped: redirected-I/O/terminal-isolation and source-audit test suites beyond what exists]`

## 2026-10-05: B6 is left as is

A crash between publishing the target and setting the source aside leaves two whole directories, so the next plan reports "both exist". The reorder alternative needs recovery that relies on naming conventions, which is brittle (user).

- `[skipped: crash recovery between copying and linking, add when users report "both exist" after an interrupted apply]`

## 2026-10-06: Review's plan is a TamboUI tree

The tree described below was replaced by headings in a list on 2026-10-07; see "Review's plan is headings and rows".

Review, Applying and Results draw the plan with TamboUI's `TreeElement` (#150, rung 4) instead of a list whose items were one relocation line plus its first action. Each relocation is a parent row, always expanded, and its actions are children; every row is selectable. Selecting a relocation shows its path, its Decision line and its paths (Source, Target, Archive) in Details, which is now titled `Details` for both kinds of row. The tree counts lines, not items, so the trailing blank cell that kept TamboUI's scrollbar off two-line items is gone. Marks, spinner, colours and the once-at-finish selection rule from #111 are unchanged; the finish rule picks step rows only.

Spike, recorded in the PR: our keys keep their meaning, "Replace source with a link ⚠" fits beside the scrollbar at 80 columns, the selection holds during apply, and the wheel moves the selection while clicks do nothing. Each has a test.

- Keys: `TreeElement` handles ←/→ (collapse/expand), Enter and Space (toggle) itself when focused. Its `onKeyEvent` hook runs first, so the tree passes every key except ↑/↓, PageUp/PageDown and Home/End straight to HomeLight's key handler (rung 6, one lambda). → still opens Details, ← does nothing in the tree, Enter still leaves Results, and nothing collapses. No key changed, so the help lines and Help › This screen keep their keys; only the descriptions now say "a relocation or a step" and "the plan".
- Mouse: HomeLight's handler already takes every mouse event before TamboUI's elements, so the tree's own wheel (three rows) and click handling never run. The wheel over the tree moves its selection one row with `selectPrevious`/`selectNext`.
- Width: the pointer `❯` is the tree's one-cell highlight symbol and guides use `indentWidth(2)`, so a step label gets 28 cells at 80 columns beside the scrollbar. A two-cell pointer cut the label by one cell.
- The tree always draws `▼` before a parent. An in-sync relocation has no children, so it gets no `▼` and is indented two cells to keep the marks in one column.
- The tree's highlight style applies to the symbol and the whole row, so `❯` cannot take the focus colour without recolouring the row's marks. The selected row is bold instead, and `❯` is in the text colour.
- Review's Decision line says where the decision came from, in the Workspace's words (`ruleDecision`, `choiceDecision`; user decision in the #153 review). The reviewed plan alone cannot tell, because it has any one-time choice applied to the rule, and applying clears the session's draft. So starting a review captures the draft in `ReviewedExecution`, and every reviewed snapshot (`ApplyModel.Reviewed.choices`) carries it: Results still say `(your choice, this run only)` after the apply. Otherwise the line is the saved rule for the observations the plan was made from, with `(your configuration)`, so Results keep it after the disk changes. `WorkspaceView.rule` now takes the two observed states instead of a Workspace row (rung 2).
- `[skipped: TamboUI's own tree keys (collapse a relocation with ←, toggle with Enter), add when plans are long enough that users ask to fold them]`
- `[skipped: hiding the ▼ indicator, add when TamboUI's TreeElement lets a caller set it]`
- `[skipped: a status line in a relocation's Details (for example "2 of 3 steps done"), add when the step marks are not enough]`

## 2026-10-06: Configuration edits the file's own shape

#114 replaces setup with the one Configuration editor decided on 2026-10-04. `SetupView` became `ConfigurationView` (rung 2: same screen, same Browse, same discard dialog), and `SetupDraft` with its relative rows, path rules, `configured` join, `[=]` marker, `outsideRoot` and LOCATIONS mode is gone.

- **Draft:** a `HomeLightFile` (rung 2). Each relocation also keeps the index of the loaded row it came from, changed only by add and remove, so `N unsaved changes` counts an edited row once and a field typed back to its old value as no change. Fields the screen does not show (`staging-root`, `ignored-source-paths`) ride along untouched.
- **One owner for path rules:** the loader. `ConfigurationLoader.read` returns the file and the bytes it read (for the replace check, #113). The publisher takes a `HomeLightFile`, checks it with the loader's own conversion plus `validateConfiguration`, and writes paths as given, so `~` and `${USER}` survive. `ConfigurationDraft` and `configurationFile` are deleted (rung 1). The Resolved section uses the loader's `resolvePath` and `derivedTarget`, named with the screen's labels, so its messages are plain.
- **Fields:** TamboUI text inputs, each with its own focus id; Tab moves through list and fields, ↑/↓ between fields, Esc back to the list (rung 4). TamboUI has a `Select` widget but no element for it, so a ten-line element renders the widget (rung 6). `s` saves from the list and Selects; in a text field it types, so Details there says "Esc, then s to save." No Ctrl-S: some terminals take it to pause output (walkthrough).
- **Labels beside their fields at every size** (walkthrough), in an 18-cell label column; the field label is "Suggestion list", with "optional" as its placeholder, so the column stays short. At 80x24 the list pane is 28 columns and the fields pane 52, so a field is 32 cells wide (58 at 120x30), wide enough for the longest Select value (31). TamboUI's text input scrolls sideways to keep the cursor in view while typing; Configuration moves the cursor to the start when a field loses focus, so it shows the value's beginning, and to the end when it gains focus. Details' Resolved section always shows the whole value.
- **Help:** a field's note moved from the help area into the Details pane under the fields, which fixes the wrap that pushed the commands line out at 80x24; a test checks both help lines on every focus. F1 is listed in text fields.
- **Saving:** a new file is created directly; an existing one goes through the replace dialog and `ConfigurationPublisher.replace`. "Changed since it was loaded" is its own exception type (`ConfigurationChangedException`) and message, which keeps the draft and says how to start again. After a save the Workspace checks again and says the next step below its panes, until the next key it handles.
- **Invalid files (decision):** `e` opens only a file that loads. `e` is not offered on an invalid file, and `homelight init` and `config` refuse it with the loader's message and exit 1. Most broken files are JSON errors the editor could not show anyway, and the Workspace already says why the file is broken.
- **Entry:** `config` is a picocli alias of `init` (rung 4); both open a loaded file for editing or a new one.
- **Config key:** `discovery.shared-list` is now `suggestion-list` directly under `homelight`, matching "suggestion list" on screen and in the guide, and the `discovery` object is gone (walkthrough: it only held this one setting). Nothing is released, so there is no compatibility shim.
- **Browse:** rows from the file are ordinary draft rows (`[x]`), `e` edits any of them, and adding a suggestion still refuses an overlap. Its #115 work (Space toggle, Lists lines, renames) is not done here.
- **Relative paths are refused** (walkthrough), by the loader and so by Configuration: every path in the file is full or starts with `~/` (after `${USER}` is filled in), as the suggestion list already was. A relative path meant "from wherever HomeLight runs", which no one wants for storage. One error everywhere: "Use a full path, or one starting with ~/". `--source-path`/`--target-path` on the command line may still be relative to where the command runs.
- **Choices changed back are no change:** a Both exist value that does not keep the target puts the source's rule back to the file's value, so the unsaved count compares choices with the loaded file as it does text.
- `[skipped: opening an invalid file in Configuration, add when users ask to fix a broken file from the editor]`
- `[skipped: per-row changed/new markers, add when users lose track of edits in long lists]`
- `[skipped: Tab completion for paths, add when typing paths becomes a complaint]`
- `[skipped: the mouse wheel over Configuration's list and fields, add when users ask; it scrolls Details and Browse]`

## 2026-10-06: Browse is a tree of suggestions with a toggle and its lists on top

#115 finishes Browse inside Configuration (tui-design §8). The tree described below was replaced after the second walkthrough; see the last items. It supersedes `[skipped: TreeElement for Browse, add when its selection can follow an item rather than a position]` from 2026-10-04.

- **Tree (rung 4, replaced by variant C below):** apps are parent rows and directories children in TamboUI's `TreeElement`, set up as Review's (one-cell `❯`, `indentWidth(2)`, bold selected row). It replaces the hand-built group rows. Browse keeps the selected item and sets the tree's index from it on every frame, so the selection follows the item through Check again, `u`, adding and removing. When the selected item is not listed (hidden, or not suggested until a check finishes), the row at its place becomes the selected item.
- **Spike, all passed:** the focused Browse screen offers every key to the tree inside it, so the tree's `onKeyEvent` passes every key to the app's handler, as Review's does (rung 2); the tree's own moves, expand, collapse and toggle never run, and Space and Enter keep their meaning. At 80 columns a row keeps its marker, a 30-cell path and a note such as `not created yet` beside the scrollbar. The app takes every mouse event first, so the wheel over the tree moves its selection a row and clicks do nothing. Each has a test.
- **Space toggles** `[ ]` and `[x]` (decision 2026-10-04). Removing goes through Configuration's own remove (rung 2), so it edits only the draft. `a` no longer adds in Browse (rung 1: Space does it).
- **Lists lines (rung 2, 3):** the discovery snapshot already has each list's state; the shared-list thread now also reads the file's modification time (`Files.getLastModifiedTime`) after the read, so the UI thread never touches the file.
- **Your list wins (rung 2):** `CandidateCatalog.merge` puts the shared list's definitions first within each candidate, so the first definition's app and advice are the row's, and Details lists yours first. Hiding is unchanged: hidden only when every list that names a directory marks it usually not needed.
- **Words:** "suggestion list", "Built-in list", "Your list", "Suggested by", `r: Check again`, `i: Lists`, "Details", "Suggestion lists"; no "candidate" or "draft" on screen. Every string is in `Wording.kt`.
- **After the walkthrough (user decisions):** when the selected item is hidden, the row at its place becomes the selection and stays (no jump back). A configured directory no list suggests stays listed as `[ ]` after Space takes it out, until Browse closes (`BrowseDraft`'s `kept` sources), and rows keep the place they were first listed in. While Browse is open the header reads `[Configuration › Browse]`. `a` stays out of Browse.
- **Space on an app row (user request, rung 6):** the row reads `[x]`, `[ ]` or `[~]` over its directories that are in the configuration or can be added. Space adds every shown one that can be added, through the same add as a single row, so each overlap is refused and counted; on `[x]` it takes them all out. `Added 3. Skipped 1 that overlaps ~/.cache.` says what was skipped.
- **Layout variant C (user decision, from four rendered mockups):** a TamboUI `ListElement` of heading and row lines replaces the `TreeElement` (rung 4, the same element as Configuration's list). App names are bold headings without `▼` or guides, with `N of M added` (or `can't add`) dim at the notes column; directories are indented two cells and marked `●` added, `○` not added, `−` can't be added. `not created yet` and `checking…` are dim; `already a link` and `usually not needed` keep the text color and problems the warning color. The list's `onKeyEvent` passes every key to the app's handler as the tree's did, the rows draw the one-cell `❯` and bold themselves, and the wheel and clicks behave as before. Headings keep a mark (user addition), because Space acts on them: `●` all added, `◐` some, `○` none, `−` none can be, in the rows' mark column, with the dim count beside it; Space on `−` does nothing. The heading's mark sits at the left and its rows' marks two cells further in, so headings stand apart without colour; the count starts at the rows' notes column.
- **Groups no longer collapse (rung 1):** groups are a few rows each, and Space on a heading is the group action, so Enter on a heading does nothing. This drops `[skipped: showing [x] rows inside a collapsed group, …]`.
- **Glyph vocabulary** (tui-design §4), since `●` and `○` now appear on three screens: progress (`○` not run yet, spinner, `✔`, `✖`), included or not (`●`, `○`, `−`), one of several choices (`(●)`, `(○)`), with `◐` for a group heading that is partly added. A group row gets a mark only when the group itself can be selected; Review's relocation rows keep progress marks, which are status. An empty circle means nothing has happened yet or not included; filled or `✔` means it has. Parentheses mean pick one; bare marks mean each row is its own.
- `[skipped: PageUp/PageDown in Browse, add when suggestion lists grow past a few screens]`
- `[skipped: the year in "file updated", add when lists older than a year are common]`

## 2026-10-07: Review's plan is headings and rows

#157 gives Review, Applying and Results the layout Browse has after #115 (variant C, user decision 2026-10-07), so the two screens match. A TamboUI `ListElement` of heading and row lines replaces the `TreeElement` (rung 4, the element Workspace and Browse already use). Each relocation is a heading: its progress mark (spinner, `✔`, `✖` or `○`) at the left, then its path in bold. Its steps follow, indented two cells, with their marks. There is no `▼` and no guide. An in-sync relocation stays one dim heading, `─` and its path. Headings keep their progress marks: they show status, not selection (tui-design §4).

- **Kept from #111/#150:** every row is selectable, and a heading's Details still show the decision's origin and the paths; the rows, `finishedSelection` and so the once-at-finish rule are unchanged (rung 2). Colours are unchanged.
- **Pointer:** rows draw the one-cell `❯` and bold themselves, as Browse's do, so the list's highlight is off. `❯` now takes the focus colour, which the tree's highlight could not give it without recolouring the row. Width is unchanged: pointer, two-cell indent and mark take the five cells that pointer, guide and mark took, so "Replace source with a link ⚠" still fits beside the scrollbar at 80 columns.
- **Keys:** the list moves its selection on ↑/↓, PageUp/PageDown and Home/End, as the tree did; its `onKeyEvent` passes every other key to the app's handler first, so no TamboUI binding moves it and → and Enter keep their meaning (rung 2, the same lambda).
- **Mouse:** the app still takes every mouse event first; the wheel over the list moves its selection one row through the app's `moveSelection`, as on Workspace (rung 2), and clicks do nothing.
- This drops `[skipped: hiding the ▼ indicator, add when TamboUI's TreeElement lets a caller set it]`.
- `[skipped: folding a relocation's steps, add when plans are long enough that users ask to fold them]` replaces the tree-keys item of 2026-10-06.

## 2026-10-07: An unreadable configuration says what is wrong and how to fix it

#164, from the user's walkthrough: a malformed `~/.homelight.json` showed kotlinx's raw text (`Line 1, column 1: Expected start of the object '{', but had 'h' instead`) and no next step. The Workspace now fills its pane with `HomeLight can't read <path>`, the problem, `To fix it: …` and `To start over: …` (tui-design §5); Help's purpose line repeats the same four lines, and the help lines stay `r`, `?`, `q`. The CLI prints the same lines on stderr for `--json` commands and for `init`/`config`, with `run the command again` and `run homelight init [--config <path>]` for the TUI's keys; exit codes are unchanged.

- **Plain words for text that is not JSON (rung 6, small):** `decodeJson` recognizes kotlinx's lexer messages by their wording (`Expected … '{', but had 'h' instead`, `Expected end of the array or comma`, `Expected EOF after parsing`, an open block comment, a bad escape) and gives `JsonProblem.Syntax`, such as `should start with "{" but starts with "h"`. The loader leads it with `It isn't valid JSON: line L, column C`. A message no recognizer knows keeps kotlinx's words too, so a kotlinx upgrade can make a message less plain, never wrong. Parsing to a `JsonElement` first was rejected: kotlinx's tree reader accepts unquoted text such as `homelight`, so it cannot tell syntax from shape (rung 5 tried).
- **`InvalidConfigurationException` (rung 6):** the loader throws it, with the file's path and the line at fault (0 for a value check such as a relative path), for any problem in the file's text or values. The CLI explains only this exception; a missing file keeps its one line. `ConfigurationEvaluation.Invalid` keeps the line, so `To fix it` says `correct that line` or `correct that setting`.
- **Missing keys and values of the wrong kind in plain words (user decision after the walkthrough, rung 6):** kotlinx reports a value of the wrong kind with the same lexer messages, so where a value starts and the text there is a JSON value (`{`, `[`, `"`, a number, `true`, `false`, `null`), it is `JsonProblem.WrongKind`: `Line 2: relocations[0].source-path should be text, but it is a number.` A missing key is `JsonProblem.MissingKey`: `target-root is missing. Add it under "homelight".` Keys are named below `homelight`, as the reader sees them in the file.
- **Unknown keys and bad rule values in plain words (coordinator, before merge, rung 6):** `Line 2: relocations[0] has an unknown setting "existing". Check its spelling or remove it.` and `relocations[0].when-only-target-exists can't be "sometimes". Use one of: prompt, adopt-target.` kotlinx's message names only the enum, so the allowed values come from its `SerialDescriptor`, found by that name under the root descriptor (rung 5: generated at compile time, no reflection).
- **A value error shows its line, not its column (user decision after the walkthrough):** kotlinx's column for a key or value points past it, so a wrong kind and an unknown key read `Line 2: …`. Syntax errors keep line and column. The loader's own checks (relative path, blank path, staging root) keep their words and gain the `To fix it` and `To start over` lines.
- **Help's purpose is plain text (rung 2):** Help escapes Markdown punctuation in every purpose, since this one quotes the file.
- `[skipped: start over with a backup from inside the app, add when users ask]`
- `[skipped: own steps for a file HomeLight cannot open (a directory at the path, no permission, not UTF-8), add when a user hits one; the TUI shows the generic steps, the CLI its one line]`

## 2026-10-07: The planner blocks a folder that is not a folder

#163, from the #160 walkthrough: with the archive location replaced by a file, the plan was accepted and the apply stopped at "Create parent folder", and Results blamed a disk change that had happened before `y`.

- **Plan-time check (rung 2, the executor's own rule):** inspection now also walks each folder a step may create or work in, the way `ensureDirectories` does: the parents of the source, target and archive destination, and the staging root. Walking down from the filesystem root, every existing path must be a folder through links; the staging root itself must be a real folder. The first existing path where the walk stops is kept in `RelocationState.notFolders`, by the folder that needs it. The planner stays free of I/O: after planning a relocation, it blocks it when a folder one of its actions needs (`EnsureDirectory`, or a migration's target parent and staging root) has an entry. A plan that needs none of those folders, such as one already in sync, is not blocked by them.
- **Reasons in plain words:** `<path> is a file, not a folder`, `is a link, not a folder` (a link to something other than a folder), `is a broken link, not a folder`, `can't be read, so HomeLight can't tell if it is a folder`, or `is not a folder`. A staging root that is a working link gets its own reason (user decision): `the staging folder must be a real folder, not a link: <path>`. They show on the Workspace as `Problem: …` and in `plan --json` as a blocked action's `reason`.
- **A way around (user decision):** when a row is blocked only by a folder in the way and one of its offered choices plans without a block, Details add `Or choose an option below that doesn't need this folder.` under the Problem line. Evaluation works this out (rung 2, the planner it already has): it plans the row again with no folder in the way, and once per offered choice, each alone. That is a few pure plans per blocked row, so it is cheap.
- **One inspection:** `inspectRelocations` builds every `RelocationState`, for `ConfigurationEvaluation` and the planner tests (rung 2).
- **Stop message:** `Stopped: a step found something different from the plan. The steps after it did not run. See the failed step's details, then press r to check again.` replaces "the disk changed while applying", which guessed a cause. The failed step's Details keep the exact problem.
- Executor preconditions now checked at plan time: every `EnsureDirectory` path, and a migration's target parent and staging root (each walked as above, the staging root without following a link at its end). Already checked before: the state of the source, target, archive destination and replaced source, which `CreateDirectory`, `ArchiveDirectory`, `CreateSymlink`, `DeleteDirectory` and the replacements guard.
- `[skipped: a plan-time check that the staging root is on the target's filesystem and that both support POSIX permissions, add when a user's apply stops on either]`
- `[skipped: preflight re-checking these folders between review and y, add when a folder breaking in that window is reported; the step still stops with the new message]`
- `[skipped: a stricter check when a configured staging root is also a source, target or archive parent; the parents' rule wins, add when someone configures one that way]`

## 2026-10-07: Browse groups apps by ecosystem; pixi joins the built-in list

#165 (user decision, 2026-10-07) adds a level above apps in Browse, so a whole ecosystem (JVM, Python, …) can be added with one key (tui-design §8).

- **Format (rung 5):** an optional `"ecosystem"` string per app, read by kotlinx.serialization as the other optional keys are; nonblank and trimmed like `name`. Each `CandidateDefinition` carries its app's ecosystem. Existing lists stay valid.
- **Your list wins, per app (rung 2):** an app's ecosystem is your list's when your list gives that app one, else the built-in list's; within a list the first one given. An app your list names without an ecosystem keeps the built-in one, so a team can add directories to Maven without retyping `JVM`. The rule lives in Browse beside the existing "first definition's app wins", from the definitions `CandidateCatalog.merge` already orders.
- **Three levels in the same `ListElement` (rung 2):** ecosystem headings, app headings two cells in, directories two further. Apps with no ecosystem go under **Other tools**; directories with no app stay under **Other directories**, a heading at the ecosystems' level. Ecosystems and apps keep first-appearance order; Other tools, then Other directories, come last. The built-in list is ordered by ecosystem so the file reads like the screen.
- **Space on an ecosystem (rung 2):** the same group logic as an app heading from #155: counts, marks, `AddAll`/`RemoveAll`, overlaps refused one by one, and the same skip message. Only the Help descriptions differ.
- **Width:** a path now shows at most 28 cells (was 30), so the notes column stays where it was despite the deeper indent. At 80 columns notes start at column 38, leaving 40 cells before the scrollbar; the longest note, `can't read: its real location is unclear` (40), still fits exactly.
- **Built-in ecosystems:** JVM (Maven, Gradle, JBang), Rust (Cargo, rustup), JavaScript (npm, Yarn, pnpm, node-gyp, nvm, Bun; JavaScript rather than Node because of Bun), Python (pip, uv, Poetry, PDM, virtualenv, pipx, pixi), Go (Go), Editors (JetBrains, VS Code).
- **pixi under Python:** pixi's docs present it as multi-language, built on conda packages, but Python is its main use and it reads `pyproject.toml` and installs PyPI packages. One more heading for one app (`Conda`) did not seem worth it. Directories, from pixi's and rattler's source and docs: `.cache/rattler` (the package cache: `rattler::default_cache_dir`, `dirs::cache_dir()/rattler/cache`), `.cache/pixi` (used instead when it exists: `pixi_config::resolve_cache_root`), and `.pixi/envs` (global tool environments under `PIXI_HOME`, default `~/.pixi`). `.pixi/bin` (small trampolines on `PATH`) and `.pixi/manifests` (the user's global manifest) stay home. Environments link files from the cache with hard links when both are on one filesystem; with the cache moved, new environments in the home directory get copies instead.
- **conda, mamba, micromamba not added:** their directories depend on where the user installed them (`~/miniconda3`, `~/miniforge3`, `~/anaconda3`, or micromamba's root prefix, default `~/micromamba`), with packages and environments inside, and the base install holds the `conda` command itself. `~/.conda/pkgs` and `~/.conda/envs` are used only when the base install is not writable. None is clearly one safe user-level path.
- `[skipped: conda, mamba and micromamba directories, add when users ask for a specific install layout]`
- `[skipped: moving an app to another ecosystem without naming one of its directories, add when teams want to retag built-in apps wholesale]`
- `[skipped: the ecosystem in Details' "Suggested by" lines, add when users ask which list set it]`
- `[skipped: folding ecosystems, add when the built-in list grows past a few screens]`

## 2026-10-07: Rename HomeLight to Lighten

#174 (user decision): the tool is **Lighten** and its command `lighten`. `homelight` was long to type, and HomeLight, Inc. (real estate) holds HOMELIGHT trademarks and owns the search results. Among the names the user liked, `lighten` had the fewest collisions.

- **Everything that carries the name:** the header `⌂ LIGHTEN` (the `⌂` mark stays), Help, dialogs, `--help`, `--version`, the guide, the binary and CI artifacts `lighten-linux-<arch>`, the Kotlin package `io.github.bigswlittlesw.lighten`, class names, the config file `~/.lighten.json` and its key `"lighten"`, the hidden names written to disk (`.lighten-staging`, `.lighten-archive`, `.lighten-replaced-…`, temporary `.lighten-*` files), thread names and CI environment variables. A test renders every screen and Help tab and fails if the old name shows.
- **No compatibility with the old names:** nothing is released. The README tells anyone who ran an earlier build how to rename the file, its key and leftover folders; the guide does not mention HomeLight.
- **History stays:** `docs/research/` and the dated entries above keep the old name.
- **Ecosystem becomes category (user decision):** the suggestion-list level above apps is a **category**, and its JSON key `"ecosystem"` is now `"category"`, in the built-in list, the parser and the fixtures, with no compatibility. "Ecosystem" did not fit Editors or Other tools, and "app group" would clash with an app's own group of directories. Code names, Help's descriptions, the guide and `tui-design.md` follow; headings still show the names (JVM, Python, Editors, Other tools), so only Help's text changes on screen. The #165 entry above keeps the old word.
- `[skipped: the repository URL, add when the user renames big-sw-little-sw/homelight; then ci/try-pr, the guide URL and the README links follow]`

## 2026-10-07: Bare marks for choices

#173 (user decision): Workspace Details mark one-time choices with bare `●` chosen and `○` not chosen, as Browse marks its rows. The focused choice keeps `❯` and bold. "Parentheses mean pick one" (#165 entry) is dropped: a choice list is one of many by behaviour, since choosing one clears the others, and the Help for `Space/Enter` says so.

## How to add decisions

Use this format:

```markdown
## YYYY-MM-DD: Short decision title

Decision and rationale.

Alternatives considered, if relevant.
```
