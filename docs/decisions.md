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

TamboUI remains the presentation toolkit because its high-level layout, widget, focus, and styling primitives allow a distinctive interface without rebuilding terminal infrastructure. It remains isolated behind the TUI adapter because its snapshot API is experimental.

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

## How to add decisions

Use this format:

```markdown
## YYYY-MM-DD: Short decision title

Decision and rationale.

Alternatives considered, if relevant.
```
