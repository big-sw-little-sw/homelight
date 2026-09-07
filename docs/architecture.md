# HomeLight Architecture

## Architectural direction

HomeLight should be a small Java library with a thin interactive CLI. The reconciliation library is the durable product boundary; the CLI is one consumer of it.

The design should support future TUI, GUI, automation, Git, and HTTP integrations without placing those concerns in the reconciliation engine.

Initially, use one Maven module with clear package boundaries. Split into Maven modules only when independent compilation, packaging, or dependency isolation becomes useful.

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

cli
  Picocli commands, options, output formatting, JSON, and exit codes

tui
  interactive prompts, candidate selection widgets, and step wizards
```

The dependency direction is:

```text
cli, tui -> config, reconcile, fs
reconcile -> domain
fs -> domain
config -> domain
domain -> Java standard library only where practical
```

The reconciliation engine must not depend on `cli`, `tui`, terminal APIs, or a concrete YAML implementation.

## Domain model

Use records for pure data and sealed interfaces for genuinely closed concepts. The model should represent these separately:

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

Destructive actions must be marked explicitly. Symlink handling must avoid accidental traversal. Deletion and replacement must be narrowly scoped to validated intended paths.

## Ports and adapters

Use interfaces at real external boundaries, for example:

```java
interface FileSystem {
    ActualPathState inspect(Path path);
    void execute(Action action);
}

interface ConfigStore {
    Configuration load(Path path);
    void write(Path path, Configuration configuration);
}
```

The exact APIs may change. Do not introduce interfaces merely to abstract ordinary in-memory domain logic.

Future Git or HTTP support should be adapters that provide source information or source material through a narrow port. The core must not know whether a source is managed by Stow, chezmoi, Git, HTTP, or a local directory.

## GraalVM considerations

GraalVM Native Image is a future packaging goal, not a reason to introduce a framework.

Prefer:

- explicit object construction
- standard Java APIs, especially `java.nio.file`
- limited reflection
- no runtime classpath scanning
- no dependency injection container
- no dynamic plugin loading in the initial design
- isolated serializers and integration clients
- native-image verification early in the build lifecycle

Third-party libraries are acceptable when they are isolated behind an adapter and their native-image behavior is verified. Configuration serialization should remain behind `ConfigStore` so its implementation can be changed without affecting the domain or CLI.

## Testing strategy

Test the pure reconciliation engine with state and policy combinations. Test filesystem behavior with temporary directory trees. Test CLI rendering and exit behavior separately.

Important invariants include:

- correct existing symlinks produce no actions
- unresolved or unsafe states never become silent destructive actions
- `--yes` does not bypass unresolved conflicts
- repeated planning after convergence is a no-op
- mutation does not follow symlinks unexpectedly
- external source ownership is not silently replaced
- managed links inside HomeLight-relocated trees are supported
