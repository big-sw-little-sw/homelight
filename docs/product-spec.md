# HomeLight Product Specification

## Purpose

HomeLight keeps a space-constrained or shared `$HOME` directory lightweight by relocating selected directories to machine-local storage and safely reconciling symlinks.

The primary use case is Linux systems where `$HOME` is mounted over NFS or is quota-constrained, while each machine has larger local storage.

HomeLight is a Java CLI application. Its initial user experience is an interactive guided CLI. A full-screen TUI or GUI is deferred until a workflow clearly benefits from richer interaction.

## Scope

HomeLight manages:

- relocation of configured directories from `$HOME` to machine-local storage
- creation and repair of symlinks back into `$HOME`
- migration of existing directories and files
- incorrect and broken existing symlinks
- configurable treatment of existing contents
- declarative links to externally managed files inside relocated trees
- ownership conflicts with externally managed dotfiles
- guided discovery and configuration of candidate heavy paths

HomeLight does not replace GNU Stow or become a general dotfile or workstation manager.

## Configuration

Configuration describes desired state and user policy. It must not encode a fixed sequence of low-level filesystem operations.

Users should normally create and update configuration through interactive commands rather than editing it manually.

An illustrative configuration is:

```yaml
target-root: /local/home/${USER}

externallyManagedSourceRoots:
  - ~/dotfiles/stow

relocations:
  - source-path: ~/.m2
    existing: move

  - source-path: ~/.cache/uv
    existing: discard

  - source-path: ~/.config/agent-tools
    existing: move

links:
  - path: ~/.config/agent-tools/AGENTS.md
    source: ~/dotfiles/agent-guidance/global-agent-defaults.md
```

The schema may evolve. Path expansion and validation belong at the configuration boundary.

## Interactive CLI

Initial commands are:

```text
homelight init
homelight plan
homelight apply
homelight status
```

Expected behavior:

- `init` performs guided discovery and configuration.
- `plan` computes and displays changes without modifying the filesystem.
- `apply` executes the current plan and prompts for unresolved or risky decisions.
- `status` reports whether configured paths match desired state.

Automation-friendly forms include:

```text
homelight apply --yes
homelight plan --json
homelight status --json
```

Interactive prompts must remain outside the reconciliation engine. The engine returns structured actions, warnings, conflicts, and unresolved decisions. CLI code decides whether to prompt, render human-readable output, emit JSON, or apply a plan.

Prompts are for unresolved decisions, not repeated confirmation of decisions already stored in configuration.

`init` should:

1. determine or ask for the machine-local storage root
2. scan known candidate paths
3. show existence and approximate size
4. let the user select paths to manage
5. ask how existing contents should be handled
6. detect externally managed links beneath candidate paths
7. ask how intersections should be handled
8. show the resulting plan
9. write durable decisions to configuration
10. optionally apply the plan

Choices must be derived from the detected state and available reconciliation options. The example prompt flow is illustrative, not exhaustive.

## Built-in candidate defaults

HomeLight should provide optional candidate defaults for commonly large machine-local directories, such as:

- `~/.m2`
- `~/.cargo`
- `~/.rustup`
- `~/.npm`
- `~/.pnpm-store`
- `~/.cache/uv`
- `~/.cache/pip`
- `~/.cache/JetBrains`
- `~/.vscode-server`

Defaults are candidate policies, not unconditional mutations. Users must be able to review, override, disable, and extend them. Prefer the narrowest directory that captures bulky machine-local state.

## Reconciliation model

For every configured path, HomeLight determines:

1. actual filesystem state
2. desired state
3. applicable user policy
4. actions required to converge safely
5. whether additional user input is required

The actual-state model must explicitly support, at minimum:

- absent paths
- normal files
- normal directories
- correct symlinks
- symlinks to wrong destinations
- broken symlinks
- absent destinations
- populated destinations
- both source and destination populated
- partially migrated state
- unexpected filesystem object types
- permission or ownership mismatches
- inaccessible paths
- externally managed descendants
- conflicting managed links
- unavailable source paths
- unavailable destination paths

The action model must be extensible and may include:

- create directories and parents
- move files, directories, or contents
- remove files, directories, or disposable contents
- create, replace, or repair symlinks
- preserve existing state
- leave unchanged
- skip
- block on a conflict
- request an explicit decision
- validate an external link
- retain an externally managed descendant
- fail because safe convergence cannot be determined

Actions are implementation details of a plan, not configuration semantics.

## Existing-content policies

Initial user-facing policies are:

- `move`: preserve existing contents by moving them to local storage
- `discard`: remove existing contents and create fresh local state
- `preserve`: refuse destructive replacement when existing contents make the operation unsafe
- unmanaged or skip: leave the path outside HomeLight management

A policy can produce multiple actions depending on actual state. For example, `move` may create a destination, move contents, create a symlink, and validate the result.

HomeLight must be idempotent. Once the desired state is reached, repeated planning produces no unnecessary actions and repeated application is safe.

## Planning and safety

Plans distinguish:

- no-op or already-correct state
- safe automatic actions
- destructive actions
- warnings
- conflicts
- unresolved decisions
- blocked operations

`apply` must:

- display destructive actions clearly
- prompt only when needed
- apply safe changes together where appropriate
- require explicit handling of ambiguous or destructive conflicts
- support `--yes` only when configuration resolves required decisions
- fail closed rather than guessing

The system must not silently overwrite or destroy data when desired behavior cannot be determined confidently.

## External dotfile managers

Ownership is exclusive: a destination path is owned by either HomeLight or an external dotfile manager, never both.

Ordinary dotfiles remain managed by systems such as Stow. If a directory is relocated by HomeLight, HomeLight owns placement within that relocated subtree.

`externallyManagedSourceRoots` identifies source trees such as `~/dotfiles/stow`. HomeLight inspects symlinks under paths it intends to manage. If an existing link resolves into an external source root:

- `plan` surfaces the intersection
- interactive commands ask how to handle it when necessary
- `apply` never silently replaces it
- a safe existing external link may be preserved when consistent with desired state

HomeLight must not parse or depend on Stow internals. Detection operates on filesystem state and configured source roots, so it also works with chezmoi, yadm, plain Git-managed dotfiles, or other systems.

## Managed links inside relocated trees

HomeLight may create a link whose source is under an external source root while its destination is inside a HomeLight-managed relocated directory.

For example:

```text
~/.config/agent-tools
    -> /local/home/config/agent-tools

/local/home/config/agent-tools/AGENTS.md
    -> ~/dotfiles/agent-guidance/global-agent-defaults.md
```

This is supported when HomeLight owns the destination and the external system owns only the source. HomeLight ensures the configured destination link exists and resolves as intended; the source repository remains independently managed.

## Existing heavy-storage setup

An existing dotfiles setup uses concepts such as `~/.local-heavy`, `heavy-dirs`, `heavy-links`, `agent-guidance-heavy`, and helper scripts.

HomeLight should eventually replace that special-purpose machinery. The dotfiles repository may continue to store configuration sources, manage ordinary dotfiles, contain HomeLight configuration, and invoke HomeLight during machine setup.

HomeLight must not depend on that repository. Safe migration from the existing setup is desirable but sophisticated automated migration can wait until the core reconciliation model is stable.

## Architecture constraints

The initial implementation should be small and library-oriented:

```text
core       domain model, planning, and filesystem/configuration ports
adapters   local filesystem, configuration serialization, future Git/HTTP adapters
cli        Picocli commands, prompts, and presentation
```

These may initially be packages in one Maven module. Separate modules only when that boundary provides practical value.

The reconciliation engine is independent of terminal presentation and mutation. Filesystem mutation happens only during explicit application of a plan. Filesystem operations must be testable against temporary directory trees.

Avoid framework infrastructure, dependency injection, runtime scanning, and premature plugin systems. Keep the core friendly to GraalVM Native Image by preferring explicit construction, standard Java APIs, and isolated serialization or integration adapters.

## Deferred scope

Defer:

- full-screen TUI or GUI
- automatic cleanup
- rollback history
- sophisticated migration assistance
- broad workstation-management features
- a general plugin framework

Possible future commands include `repair`, `restore`, `reconfigure`, and `tui`, but their names and behavior are not fixed by this specification.

## Initial milestone

The first useful version supports:

1. loading and writing configuration
2. one configurable local root
3. built-in candidate discovery
4. interactive `init`
5. directory relocation
6. `move`, `discard`, `preserve`, and unmanaged policies
7. explicit actual-state detection
8. correct, wrong, and broken symlink handling
9. declarative managed links
10. external source-root conflict detection
11. managed links inside relocated directories
12. structured reconciliation plans
13. `plan`
14. `apply`
15. `status`
16. `--yes` for automation
17. JSON output
18. unit and integration tests using temporary filesystem trees

This list does not limit the state or action model to only the listed cases.
