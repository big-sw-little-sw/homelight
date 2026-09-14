# HomeLight

HomeLight is a Java terminal application for relocating selected, bulky `$HOME` directories to machine-local storage while safely maintaining symlinks and declarative links.

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

The project uses Java 25 and Maven.

```text
mvn test
```

Run the CLI directly through Maven with `exec:java`:

```text
mvn -q compile exec:java
mvn -q compile exec:java -Dexec.args="status --config /path/to/.homelight.yaml --json"
mvn -q compile exec:java -Dexec.args="plan --config /path/to/.homelight.yaml --json"
mvn -q compile exec:java -Dexec.args="apply --config /path/to/.homelight.yaml --json --yes"
```

The repository launcher hides that Maven detail:

```text
./homelight
./homelight status --json
./homelight plan
./homelight plan --config /path/to/.homelight.yaml --json
./homelight apply --config /path/to/.homelight.yaml --json --yes
```

The default configuration path is `~/.homelight.yaml`.

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
