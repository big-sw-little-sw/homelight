# Lighten Architecture

## Architectural direction

Lighten should be a small Kotlin library with a presentation-neutral application workflow. The reconciliation library remains the durable safety boundary. A full-screen TUI is the primary human consumer, and JSON commands are the automation consumer.

The design should support future integrations without placing presentation, serialization, Git, or HTTP concerns in the reconciliation engine.

Initially, use one Gradle module with clear package boundaries. Split into Gradle subprojects only when independent compilation, packaging, or dependency isolation becomes useful.

## Boundaries

```text
domain
  desired configuration, actual states, policies, actions, plans

reconcile
  pure comparison of desired and actual state

fs
  filesystem inspection and mutation primitives

config
  configuration serialization, validation, and path resolution

tui
  TamboUI screens, rendering, focus, navigation, and terminal lifecycle

cli
  Picocli routing, JSON contracts, and exit codes

application
  workflow state, typed user intents, and orchestration of configuration,
  inspection, planning, and execution

```

The dependency direction is:

```text
tui -> application
cli -> application
application -> config, reconcile, fs
reconcile -> domain
fs -> domain
config -> domain
domain -> Kotlin and JDK standard libraries only where practical
```

The reconciliation engine must not depend on `application`, `tui`, `cli`, terminal APIs, or a concrete configuration format. JSON commands must not initialize or depend on a live terminal session.

## Application workflow

The primary behavioral seam is a presentation-neutral workflow/session boundary. It accepts typed user intents and exposes immutable state describing the current screen, configuration draft, exact reviewed plan, plan freshness, diagnostics, and execution progress. Side effects are explicit operations delegated to configuration, inspection, planning, and execution services.

TamboUI renders workflow state and translates input events into intents. JSON commands invoke the same orchestration directly and serialize versioned response contracts. Neither adapter owns reconciliation policy or filesystem mutation rules.

Conflict resolution uses typed domain or application choices. Presentation code must not infer a choice by matching diagnostic text or construct configuration overrides from screen indexes.

## Domain model

Use data classes for pure data and sealed types for genuinely closed concepts. The model should represent these separately:

- desired configuration
- resolved paths
- actual filesystem state
- existing-content policy
- concrete actions
- warnings
- conflicts
- unresolved decisions
- blocked operations
- complete plans

Do not encode the domain as a collection of loosely related boolean flags. Do not make prompt wording or terminal interaction part of the model.

## Planning and application

Planning is pure from the filesystem's perspective. It reads an inspection result and produces a structured plan. It does not prompt or mutate.

Application consumes a plan and performs only the actions already represented in it. It must refuse plans containing unresolved decisions or blocked operations. It must not guess during execution.

The TUI retains the exact reviewed plan. Application preflight checks its expected filesystem state immediately before execution. Drift invalidates the plan and requires a new review; application never silently recomputes and substitutes a plan after confirmation.

Destructive actions must be marked explicitly. Symlink handling must avoid accidental traversal. Deletion and replacement must be narrowly scoped to validated intended paths.

## Reconciliation and safety invariants

- **Configuration is desired state.** It names relocations and the rule for each observed state, never a sequence of filesystem operations. The relocation list is an explicit allow-list: suggestions are not managed until the user selects them and they are saved.
- **Idempotence.** Once a relocation reaches its desired state (a real target directory and a correct source symlink), planning again produces no actions and applying again is safe. `leave-unchanged` is intentional success, not convergence.
- **Fail closed.** When the desired behavior cannot be determined confidently, Lighten blocks or asks. It never guesses, and never silently overwrites or destroys data.
- **`--yes` never resolves a choice.** It confirms a plan whose decisions the configuration's rules already resolve. A plan with an open choice or a blocked relocation is refused, with or without `--yes`.
- **Exclusive ownership.** A path is owned by either Lighten or an external dotfile manager, never both. Lighten owns placement inside a relocated tree; ordinary dotfiles stay with tools such as Stow. Detection works from filesystem state and configured source roots, never from a manager's internals, so it applies equally to chezmoi, yadm or a plain Git checkout. An existing link into an external source root is never silently replaced.

## Ports and adapters

Use interfaces at real external boundaries, for example:

```kotlin
interface FileSystem {
    fun inspect(path: Path): ActualPathState
    fun execute(action: Action)
}

interface ConfigStore {
    fun load(path: Path): Configuration
    fun write(path: Path, configuration: Configuration)
}
```

The exact APIs may change. Do not introduce interfaces merely to abstract ordinary in-memory domain logic.

Future Git or HTTP support should be adapters that provide source information or source material through a narrow port. The core must not know whether a source is managed by Stow, chezmoi, Git, HTTP, or a local directory.

## GraalVM considerations

GraalVM Native Image is a future packaging goal, not a reason to introduce a framework.

Prefer:

- explicit object construction
- standard JDK APIs, especially `java.nio.file`
- limited reflection
- no runtime classpath scanning
- no dependency injection container
- no dynamic plugin loading in the initial design
- compile-time serializers (kotlinx.serialization) and isolated integration clients
- native-image verification early in the build lifecycle

Third-party libraries are acceptable when they are isolated behind an adapter and their native-image behavior is verified. Configuration serialization should remain behind `ConfigStore` so its implementation can be changed without affecting the domain or CLI.

## Testing strategy

Drive complete user journeys through the presentation-neutral workflow/session boundary. These tests assert externally visible state transitions and effects rather than TamboUI implementation details. Test the pure reconciliation engine with state and policy combinations and filesystem behavior with temporary directory trees.

Keep adapter tests narrow: deterministic TamboUI rendering and navigation at fixed terminal sizes, exact JSON schemas and exit codes, and a small real-terminal smoke test for startup, resizing, deep links, and clean shutdown. Visual review establishes the Lighten-specific design language before the complete workflow is built.

Important invariants include:

- correct existing symlinks produce no actions
- unresolved or unsafe states never become silent destructive actions
- `--yes` does not bypass unresolved conflicts
- repeated planning after convergence is a no-op
- mutation does not follow symlinks unexpectedly
- external source ownership is not silently replaced
- managed links inside Lighten-relocated trees are supported
