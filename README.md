# HomeLight

HomeLight is a Java CLI for relocating selected, bulky `$HOME` directories to machine-local storage while safely maintaining symlinks and declarative links.

It is intended for space-constrained or shared home directories, including Linux systems using NFS-mounted home directories.

## Status

The project is at the initial design and implementation stage. The repository currently contains the Maven Java 25 skeleton and project documentation. Filesystem relocation is not implemented yet.

## Planned commands

```text
homelight init
homelight plan
homelight apply
homelight status
```

Automation and machine-readable output are planned through options such as `apply --yes` and `plan --json`.

## Design

HomeLight is planned as a small library-oriented core with a thin Picocli CLI. Reconciliation will produce structured plans independently of prompts and filesystem mutation. Future Git, HTTP, TUI, or GUI integrations should remain adapters around that core.

See the project requirements in [`docs/product-spec.md`](docs/product-spec.md), the architecture in [`docs/architecture.md`](docs/architecture.md), and recorded design choices in [`docs/decisions.md`](docs/decisions.md).

## Development

The project uses Java 25 and Maven.

```text
mvn test
```

Follow [`AGENTS.md`](AGENTS.md) for repository conventions.
