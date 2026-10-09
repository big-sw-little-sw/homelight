# Lighten Architecture

## Shape

Lighten is one Gradle module of Kotlin on JVM 25. Its releases are GraalVM Native Image binaries for two platforms:

- Linux x86_64: a static binary, built with musl.
- Linux arm64: a binary that needs glibc 2.17 or later.

The JVM build is for development and tests. The native binaries are the release.

An application workflow sits on top of a reconciliation core. The workflow does not depend on how its results are shown. The full-screen TUI is the interface for people, and the `--json` commands are the interface for automation. Both are adapters over the same workflow, planning and execution.

## Packages

All packages are under `io.github.bigswlittlesw.lighten`:

```text
fs            filesystem inspection: what is at a path, without following links, and
              a source's state relative to its target
config        the configuration file, its validation and path resolution; relocations
              and their rules; suggestion-list parsing and the built-in list
reconcile     inspection of each relocation, the pure planner, and the executor that
              stages, copies, archives and links
discovery     Browse's suggestions: which built-in and shared-list directories exist,
              with bounded, time-limited filesystem reads
concurrent    bounded parallel work on virtual threads, and its limits
application   the session: configuration evaluation, one-time choices, the reviewed
              plan and its execution, the user guide
update        lighten update: release versions and running the release's install.sh
tui           TamboUI screens, wording, keys, help and terminal lifecycle
cli           picocli commands, JSON output and exit codes; the entry point
```

The direction of dependencies:

```text
cli         -> application, tui, update, reconcile, config, fs
tui         -> application, discovery, reconcile, config, fs
update      -> application, fs
application -> reconcile, discovery, config, fs
reconcile   -> config, fs, concurrent
discovery   -> config, fs, concurrent
config      -> fs
fs, concurrent -> Kotlin and JDK only
```

The reconciliation core is `reconcile` and the packages below it. It never depends on `application`, `tui`, `cli` or terminal APIs. The JSON commands never start a terminal session.

## Application workflow

`LightenSession` is the boundary of the workflow. It accepts typed user intents. It exposes immutable state:

- the configuration and its evaluation;
- the current plan;
- the one-time choices;
- the exact plan that the user reviewed;
- the progress of the execution.

Side effects are explicit operations: load the configuration, inspect, plan and execute.

The TUI shows the session state and changes key presses into intents. The JSON commands call the same evaluation, planning and `ReviewedExecution` directly, and print versioned responses. Neither adapter owns the reconciliation policy or the rules for how to change the filesystem.

Choices use typed values (`DecisionChoice`). Presentation code never reads a choice from diagnostic text. It never makes configuration overrides from positions on the screen.

## Planning and execution

Inspection reads the filesystem one time for each relocation and records what it found. Planning is pure: it changes these observations into a structured plan. It never asks the user and never changes the disk.

Execution does only the actions in the plan. It refuses a plan that has an open choice or a blocked relocation, and it never guesses. The TUI keeps the exact plan that the user reviewed.

Two checks compare the disk with the plan:

- Before anything runs, preflight compares the expected states of the whole plan with the disk.
- While it runs, each action checks its own expected state. Preflight cannot prevent other programs from writing, so this second check is necessary.

If either check finds a difference, execution stops, and the user must make and review a new plan. After the user confirms, execution never calculates a different plan and uses it instead.

Destructive actions have an explicit mark. A change never follows a symlink by accident. Deletion and replacement apply only to paths that are validated and intended. A move does these steps:

1. It copies the directory into a staging directory on the filesystem of the target.
2. It checks the copy.
3. It renames the copy into its final place.
4. It replaces the source with a link, in atomic steps.

## Reconciliation and safety invariants

- **The configuration is the desired state.** It names the relocations and the rule for each observed state. It is never a sequence of filesystem operations. The relocation list is an explicit allow-list: Lighten does not manage a suggestion until the user selects it and saves it.
- **Idempotence.** The desired state of a relocation is a real target directory and a correct source symlink. When a relocation is in this state, a new plan has no actions, and a new apply is safe. `leave-unchanged` is a success on purpose. It is not the same as in sync.
- **Fail closed.** When Lighten cannot be sure of the desired behavior, it blocks or asks. It never guesses. It never overwrites or destroys data without telling the user.
- **`--yes` never makes a choice.** It confirms a plan only when the rules in the configuration already make all its decisions. Lighten refuses a plan with an open choice or a blocked relocation, with or without `--yes`.
- **Exclusive ownership.** Either Lighten or an external dotfile manager owns a path, never both. Lighten owns what is placed inside a relocated tree. Other dotfiles stay with tools such as Stow. Lighten does not detect which tool owns a link. Instead, the planner blocks each source link that points somewhere other than its target, and no rule or choice replaces it. Thus it works the same for chezmoi, yadm, Stow or a plain Git checkout. Detection of the tool that owns a link is planned for a later version.

## Native Image

The release is a Native Image, so the code must work well with Native Image:

- Construct objects explicitly. Do not use a dependency injection container, classpath scanning or dynamic plugins.
- Use standard JDK APIs, especially `java.nio.file`.
- Do not use `kotlin-reflect`. Use compile-time serializers (kotlinx.serialization) for all file formats.
- The build makes the reflection metadata for picocli from the compiled classes.
- Do not use HTTP or TLS in the process. `lighten update` runs `curl` or `wget` through the `install.sh` of the release.

You can use a third-party library when CI checks its native behavior (`Native test`, `Native distros`).

## Testing

- Tests run complete user journeys through `LightenSession` and a headless TUI at fixed terminal sizes. They check the visible state and the effects.
- Tests run the planner with combinations of states and rules. Tests run inspection and execution on temporary directory trees.
- Tests check the exact JSON shapes and exit codes of the CLI.
- CI compares the CLI output of the native binary with the output of the JVM build. It runs the TUI under `expect` on different terminal types. It does these tests again on different Linux distributions.

The tests make sure that these invariants stay true:

- Correct existing symlinks cause no actions.
- A state that is not resolved or not safe never becomes a destructive action without the user's knowledge.
- `--yes` does not make a choice that is still open.
- A new plan after a relocation is in sync has no actions.
- Changes do not follow symlinks unexpectedly.
- A source link that points somewhere other than its target is blocked, never replaced.
- Lighten supports managed links inside trees that it relocated.
