# HomeLight Product Specification

## Purpose

HomeLight keeps a space-constrained or shared `$HOME` directory lightweight by relocating selected directories to machine-local storage and safely reconciling symlinks.

The primary use case is Linux systems where `$HOME` is mounted over NFS or is quota-constrained, while each machine has larger local storage.

HomeLight is a Kotlin terminal application. Its primary human interface is a full-screen TUI, with prompt-free JSON commands for automation. A desktop GUI is deferred.

## Scope

HomeLight manages:

- relocation of configured directories from `$HOME` to machine-local storage
- creation and repair of symlinks back into `$HOME`
- migration of existing directories and files
- incorrect and broken existing symlinks
- configurable treatment of existing contents
- declarative links to externally managed files inside relocated trees
- ownership conflicts with externally managed dotfiles
- full-screen discovery and configuration of candidate heavy paths

HomeLight does not replace GNU Stow or become a general dotfile or workstation manager.

## Configuration

Configuration describes desired state and user policy. It must not encode a fixed sequence of low-level filesystem operations.

The configured relocation list is an explicit allow-list. Built-in candidate
paths are offered for discovery but are not managed unless the user selects
them and they are written to configuration.

Users should normally create and update configuration through the TUI rather than editing it manually.

An illustrative configuration is:

```json
{
  "target-root": "/local/home/${USER}",
  "externallyManagedSourceRoots": ["~/dotfiles/stow"],
  "relocations": [
    {"source-path": "~/.m2", "when-source-and-target-directories-exist": "prompt"},
    {"source-path": "~/.cache/uv", "when-source-and-target-directories-exist": "discard"},
    {"source-path": "~/.config/agent-tools", "when-source-and-target-directories-exist": "prompt"}
  ],
  "links": [
    {"path": "~/.config/agent-tools/AGENTS.md", "source": "~/dotfiles/agent-guidance/global-agent-defaults.md"}
  ]
}
```

Configuration and candidate lists are JSON; comments and trailing commas are allowed for hand editing.

The schema may evolve. Path expansion and validation belong at the configuration boundary.

## TUI and automation

Running HomeLight without arguments opens the TUI home screen. Named commands deep-link to the corresponding workflow:

```text
homelight
homelight init
homelight config
homelight plan
homelight apply
homelight status
```

Expected behavior:

- `init` opens discovery and initial configuration.
- `config` opens configuration editing; `init` opens the same editor, starting a new file when none exists.
- `plan` computes and displays changes without modifying the filesystem.
- `apply` opens plan review and explicit application.
- `status` opens the current reconciliation status.

Passing `--json` bypasses the TUI and selects the automation contract:

```text
homelight apply --json --yes
homelight plan --json
homelight status --json
homelight config validate --json
```

JSON commands never initialize TamboUI, prompt, emit color, or write non-JSON content to standard output. They use stable exit codes and versioned response envelopes. `apply --json` requires `--yes`; `--yes` never resolves missing decisions. Without `--json`, a non-interactive terminal fails with a clear diagnostic rather than silently changing modes.

The reconciliation engine returns structured actions, warnings, conflicts, and unresolved decisions. It does not depend on TamboUI, command routing, JSON serialization, or prompt wording.

The TUI holds an exact structured plan in memory between review and application. Immediately before mutation it preflights that plan's expected state. If state has drifted, the plan is marked stale and the user must re-plan; HomeLight never substitutes an unreviewed plan behind an existing confirmation.

The TUI asks only for decisions that the configuration's rules leave open. A decision made in the TUI applies to the next apply only; the user saves it as a rule explicitly, and saving never applies.

The full-screen `init` workflow should:

1. determine or ask for the machine-local storage root
2. scan known candidate paths
3. show existence and approximate size
4. let the user select paths to manage
5. ask how existing contents should be handled
6. detect externally managed links beneath candidate paths
7. ask how intersections should be handled
8. transition to the resulting plan without leaving the TUI session
9. write durable decisions to configuration
10. optionally apply the plan

Choices must be derived from the detected state and available reconciliation options. The workflow is illustrative, not exhaustive.

## TUI interaction and visual design

One persistent TamboUI application owns navigation, focus, configuration editing, decision resolution, plan review, confirmation, execution progress, and the retained final result. Workflows are screens within this application, not separate short-lived inline applications.

The interface must be recognizably designed for HomeLight. It must avoid generic dashboard-card layouts, gratuitous gradients, excessive borders, decorative clutter, canned copy, and other presentation patterns that make the product look template-generated. Information hierarchy, typography, spacing, color, keyboard behavior, empty states, failure states, and narrow-terminal layouts must be deliberate.

The Plan/Apply workflow shows relocations and their actions as a navigable hierarchy. During execution, action state changes in place through pending, running, completed, and failed states. It must not append duplicate plan and progress trees. Progress claims must match executor capabilities; per-file or byte progress and cancellation are not promised until the executor supports them safely.

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

## Directory-state decisions

The configuration names the decision for the observed state:

- `when-source-and-target-directories-exist`: `prompt`, `adopt`, `leave-unchanged`, or `discard`
- `when-only-target-exists`: `prompt` or `adopt-target`
- `when-adopting-target`: `prompt`, `discard-source`, or `archive-source`
- `archive-root`: where `archive-source` moves the source; optional, defaulting to `.homelight-archive` beside the source. It must be on the source's filesystem, because archiving is an atomic rename. The source moves to `<archive-root>/<source name>`. When that name is taken, by an existing entry or by another relocation with the same source name and archive root, it becomes `<source name>-<first 8 hex digits of the SHA-256 of the source's real path>`, so the same state always plans the same destination.

An absent target with a source directory is staged, verified, and atomically published as one relocation. `adopt` makes the target authoritative, but a separate source disposition remains mandatory. `leave-unchanged` is intentional success, not convergence or a no-op.

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

Application must:

- display destructive actions clearly
- request additional decisions only when needed
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
- the TUI asks how to handle it when necessary
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
core          domain model, planning, and filesystem/configuration ports
application   presentation-neutral workflow state and orchestration
adapters      local filesystem, configuration serialization, future Git/HTTP adapters
tui           full-screen TamboUI presentation
cli           Picocli routing, JSON contracts, and exit codes
```

These may initially be packages in one Gradle module. Separate modules only when that boundary provides practical value.

The reconciliation engine is independent of terminal presentation and mutation. Filesystem mutation happens only during explicit application of a plan. Filesystem operations must be testable against temporary directory trees.

Avoid framework infrastructure, dependency injection, runtime scanning, and premature plugin systems. Keep the core friendly to GraalVM Native Image by preferring explicit construction, standard JDK APIs, and isolated serialization or integration adapters.

## Deferred scope

Defer:

- desktop GUI
- automatic cleanup
- rollback history
- sophisticated migration assistance
- broad workstation-management features
- a general plugin framework

Possible future commands include `repair`, `restore`, and `reconfigure`, but their names and behavior are not fixed by this specification.

## Initial milestone

The first useful version supports:

1. loading and writing configuration
2. one configurable local root
3. built-in candidate discovery
4. interactive `init`
5. directory relocation
6. state-specific directory decisions and unmanaged paths
7. explicit actual-state detection
8. correct, wrong, and broken symlink handling
9. declarative managed links
10. external source-root conflict detection
11. managed links inside relocated directories
12. structured reconciliation plans retained between TUI review and application
13. a full-screen TUI shell with deep-linked workflows
14. TUI plan review and guarded apply
15. TUI init and configuration editing
16. TUI status
17. prompt-free, versioned JSON plan, apply, status, and configuration validation
18. `--yes` for JSON apply automation
19. unit and integration tests using temporary filesystem trees

This list does not limit the state or action model to only the listed cases.
