# HomeLight

HomeLight is a Kotlin terminal application for relocating selected, bulky `$HOME` directories to machine-local storage while safely maintaining symlinks and declarative links.

It is intended for space-constrained or shared home directories, including Linux systems using NFS-mounted home directories.

## Status

The project is at the initial design and implementation stage. Configuration loading, filesystem inspection, planning, and guarded filesystem mutation are implemented. The human interface is being rebuilt as a full-screen terminal application.

## Commands

```text
homelight
homelight init
homelight config
homelight plan
homelight apply
homelight status
```

Running `homelight` starts the full-screen TUI. Named commands open the corresponding TUI workflow. Automation uses prompt-free JSON forms such as `plan --json`, `status --json`, and `apply --json --yes`.

## Scripting

Three commands print one line of JSON on stdout and never prompt:

- `homelight status --json`: the state of each configured relocation, as `{"schema": 1, "configured": true, "configPath": "...", "relocations": [...]}`. Without a configuration file at the default path, `configured` is false and `relocations` is empty.
- `homelight plan --json`: the actions that would converge each relocation, with conflicts and diagnostics. It changes nothing.
- `homelight apply --json --yes`: plans and, if nothing is blocked or in conflict, applies that plan and prints the result of each action. If the plan is blocked or has a conflict, it prints the plan instead and changes nothing.

`--yes` is required with `apply --json`; it confirms the plan the command computes. Without `--json`, `apply` opens the TUI review whether or not `--yes` is given.

Each response is a JSON object whose first property is `"schema": 1`. The number changes when a change could break a script that reads the current shape.

Exit codes:

- `0`: success. `plan --json` exits 0 even when the plan has conflicts or blocked actions; read `conflicts` and `blocked`.
- `1`: a configuration error (one line on stderr, nothing on stdout), or `apply` was blocked, met a conflict or had a failed action (JSON on stdout).
- `2`: a usage error, such as an unknown option or `apply --json` without `--yes` (message on stderr).
- `70`: an internal error, a bug in HomeLight (one line on stderr).

## Design

HomeLight has a small library-oriented core with a presentation-neutral application workflow. Reconciliation produces structured plans independently of terminal rendering and filesystem mutation. The full-screen TUI and JSON commands are adapters around that workflow.

See the project requirements in [`docs/product-spec.md`](docs/product-spec.md), the architecture in [`docs/architecture.md`](docs/architecture.md), and recorded design choices in [`docs/decisions.md`](docs/decisions.md).

## Development

The project uses Kotlin on a Java 25 toolchain and Gradle (Kotlin DSL) through the Gradle wrapper.

```text
./gradlew build
```

Run the JSON commands directly through Gradle:

```text
./gradlew -q run --args="status --config /path/to/.homelight.json --json"
./gradlew -q run --args="plan --config /path/to/.homelight.json --json"
./gradlew -q run --args="apply --config /path/to/.homelight.json --json --yes"
```

`gradlew run` does not give the application the terminal, so the TUI needs the repository
launcher. It installs the application with `installDist` and runs it:

```text
./homelight
./homelight status --json
./homelight plan
./homelight plan --config /path/to/.homelight.json --json
./homelight apply --config /path/to/.homelight.json --json --yes
```

The default configuration path is `~/.homelight.json`. `homelight init` writes it;
hand-written files may use `//` and `/* */` comments and trailing commas:

```json
{
  "homelight": {
    "target-root": "/local/home/${USER}",
    "discovery": {"shared-list": "/net/team/homelight/candidates.json"},
    "relocations": [
      // Target defaults to target-root plus the path under source-root.
      {"source-path": "~/.m2"},
      {
        "source-path": "~/.cache/uv",
        "target-path": "/local/home/${USER}/uv",
        "when-source-and-target-directories-exist": "discard"
      },
      {
        "source-path": "~/.gradle",
        "when-source-and-target-directories-exist": "adopt",
        "when-adopting-target": "archive-source",
        "archive-root": "~/archive"
      }
    ]
  }
}
```

`source-root` is optional and defaults to `~`. A relocation without `target-path`
takes its source's path under `source-root` and places it under `target-root`; a
source outside `source-root` needs an explicit `target-path`.

The three `when-…` rules default to `prompt`, which asks each time. A saved file leaves
out a rule set to `prompt`.

`when-adopting-target` is `prompt`, `discard-source` or `archive-source`. Archiving
moves the source to `<archive-root>/<source name>`, for example
`~/.cache/.homelight-archive/tool-a`. `archive-root` defaults to `.homelight-archive`
beside the source. If that name is taken, the name gets a short suffix made from the
source path, such as `tool-a-3f9c2b1d`. The root must be on the source's filesystem,
because archiving is a rename.
Review always offers archiving when the policy prompts.

Unknown keys, missing required keys, wrong value types and unknown policy values are
rejected. Errors name the key path, and give the line and column where the JSON
reader knows them. A repeated key keeps its last value. Saving from the setup screen
writes a new file without comments.

`homelight apply` opens Plan for review. Press `a` or Enter to inspect the
confirmation checklist, then `y` to apply that exact plan. `n` or Esc cancels
without changes. Execution runs in the background; leaving and re-planning are
disabled until it finishes. The result stays visible: Enter returns to refreshed
Status, and `r` explicitly re-plans after a failure or stale-plan rejection.
`--yes` confirms only JSON automation; it does not bypass TUI review.

For a disposable walkthrough, run `bash scripts/setup-smoke-fixture.sh` and use
the printed Plan/Apply commands. Resolve the fixture's conflict, review the
destructive actions, confirm, and check the resulting Status screen.

To slow down TUI execution and inspect animated spinners and action progress, add
`--debug-step-delay-ms 3000` before or after the command name. This holds each
action in its running state for three seconds while the terminal stays responsive.
The delay accepts 0–60000 milliseconds and does not affect JSON automation.

The `relocations` list is an explicit allow-list. A built-in candidate is only
managed after it is selected and written to this list; leaving a candidate out
means HomeLight leaves it unchanged.

Follow [`AGENTS.md`](AGENTS.md) for repository conventions.
