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

`when-adopting-target` is `prompt`, `discard-source` or `archive-source`. Archiving
moves the source under `archive-root`, which defaults to `.homelight-archive` beside
the source. The root must be on the source's filesystem, because archiving is a rename.
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
