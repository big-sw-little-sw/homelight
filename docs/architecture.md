# Lighten Architecture

## Shape

Lighten is one Gradle module of Kotlin on JVM 25, released as GraalVM Native Image binaries for Linux x86_64 (static, musl) and Linux arm64 (glibc 2.17 or later). The JVM build is for development and tests; the native binaries are the release.

A presentation-neutral application workflow sits on a reconciliation core. The full-screen TUI is the human interface, and the `--json` commands are the automation interface. Both are adapters over the same workflow, planning and execution.

## Packages

All under `io.github.bigswlittlesw.lighten`:

```text
domain        shared domain values, such as a source's state relative to its target
fs            filesystem inspection: what is at a path, without following links
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

The dependency direction:

```text
cli         -> application, tui, update, reconcile, config, domain
tui         -> application, discovery, reconcile, config, fs, domain
update      -> application
application -> reconcile, discovery, config, fs, domain
reconcile   -> config, fs, domain, concurrent
discovery   -> config, concurrent
fs          -> domain
config, domain, concurrent -> Kotlin and JDK only
```

The reconciliation core (`reconcile` and below) never depends on `application`, `tui`, `cli` or terminal APIs. The JSON commands never start a terminal session.

## Application workflow

`LightenSession` is the workflow boundary. It accepts typed user intents and exposes immutable state: the configuration and its evaluation, the current plan, one-time choices, the exact reviewed plan and execution progress. Side effects are explicit operations on configuration loading, inspection, planning and execution.

The TUI renders session state and turns key presses into intents. The JSON commands call the same evaluation, planning and `ReviewedExecution` directly and print versioned responses. Neither adapter owns reconciliation policy or filesystem mutation rules.

Conflict resolution uses typed choices (`DecisionChoice`). Presentation code never infers a choice from diagnostic text or builds configuration overrides from screen positions.

## Planning and execution

Inspection reads the filesystem once per relocation and records what it found. Planning is pure: it turns those observations into a structured plan and never prompts or mutates.

Execution performs only the actions in the plan. It refuses a plan with an open choice or a blocked relocation, and never guesses. The TUI keeps the exact reviewed plan. Preflight compares the whole plan's expected states with the disk before anything runs, and each action guards its own expected state as it runs, since preflight cannot lock out other writers. Either difference stops execution and needs a new plan and review. Execution never recomputes and substitutes a plan after confirmation.

Destructive actions are marked explicitly. Mutation never follows a symlink by accident. Deletion and replacement are scoped to validated, intended paths. A move copies into a staging directory on the target's filesystem, checks the copy, publishes it with a rename, and then replaces the source with a link in atomic steps.

## Reconciliation and safety invariants

- **Configuration is desired state.** It names relocations and the rule for each observed state, never a sequence of filesystem operations. The relocation list is an explicit allow-list: suggestions are not managed until the user selects them and they are saved.
- **Idempotence.** Once a relocation reaches its desired state (a real target directory and a correct source symlink), planning again produces no actions and applying again is safe. `leave-unchanged` is intentional success, not convergence.
- **Fail closed.** When the desired behavior cannot be determined confidently, Lighten blocks or asks. It never guesses, and never silently overwrites or destroys data.
- **`--yes` never resolves a choice.** It confirms a plan whose decisions the configuration's rules already resolve. A plan with an open choice or a blocked relocation is refused, with or without `--yes`.
- **Exclusive ownership.** A path is owned by either Lighten or an external dotfile manager, never both. Lighten owns placement inside a relocated tree; ordinary dotfiles stay with tools such as Stow. Detection works from filesystem state and configured source roots, never from a manager's internals, so it applies equally to chezmoi, yadm or a plain Git checkout. An existing link into an external source root is never silently replaced.

## Native Image

Native Image is the release, so the code stays friendly to it:

- explicit object construction; no dependency injection container, classpath scanning or dynamic plugins;
- standard JDK APIs, especially `java.nio.file`;
- no `kotlin-reflect`; compile-time serializers (kotlinx.serialization) for every file format;
- picocli's reflection metadata is generated from the compiled classes at build time;
- no in-process HTTP or TLS: `lighten update` runs `curl` or `wget` through the release's `install.sh`.

A third-party library is acceptable when its native behavior is verified in CI (`Native test`, `Native distros`).

## Testing

- Complete user journeys run through `LightenSession` and a headless TUI at fixed terminal sizes, asserting visible state and effects.
- The planner is tested with combinations of states and rules; inspection and execution with temporary directory trees.
- The CLI is tested for exact JSON shapes and exit codes.
- CI compares the native binary's CLI output with the JVM's, drives the TUI under `expect` on several terminal types, and repeats this across Linux distributions.

The invariants the tests guard:

- correct existing symlinks produce no actions;
- unresolved or unsafe states never become silent destructive actions;
- `--yes` does not bypass unresolved conflicts;
- repeated planning after convergence is a no-op;
- mutation does not follow symlinks unexpectedly;
- external source ownership is not silently replaced;
- managed links inside Lighten-relocated trees are supported.
