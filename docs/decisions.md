# HomeLight Design Decisions

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

## How to add decisions

Use this format:

```markdown
## YYYY-MM-DD: Short decision title

Decision and rationale.

Alternatives considered, if relevant.
```
