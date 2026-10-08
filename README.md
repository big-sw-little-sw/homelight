# Lighten

Lighten moves bulky directories out of your home directory to machine-local storage and leaves a symlink in each one's place.

It is a Kotlin terminal application, run as `lighten`, for space-constrained or shared home directories, including Linux systems using NFS-mounted home directories. Each move is planned for review before anything changes on disk.

## Status

The project is at the initial design and implementation stage. Configuration loading, filesystem inspection, planning, and guarded filesystem mutation are implemented. The human interface is being rebuilt as a full-screen terminal application.

## Install

Lighten is one executable for Linux x86_64 (any distribution) and Linux arm64 (glibc 2.17 or later, so not Alpine). It needs no Java.

The install script is coming soon (#168).

These tools install Lighten from its [GitHub Releases](https://github.com/big-sw-little-sw/lighten/releases). They work once the first release is published:

```text
mise use -g github:big-sw-little-sw/lighten
eget big-sw-little-sw/lighten --to ~/.local/bin
ubi --project big-sw-little-sw/lighten --in ~/.local/bin
```

Each tool updates what it installed; `lighten update` will be for installs made with the script.

To download by hand, take `lighten-<version>-linux-x86_64-musl` or `lighten-<version>-linux-aarch64-gnu` and `SHA256SUMS` from a release, then:

```text
sha256sum --check --ignore-missing SHA256SUMS
install -m 755 lighten-<version>-linux-<arch>-<libc> ~/.local/bin/lighten
```

## Renamed from HomeLight

Lighten was called HomeLight, with the command `homelight`. Its repository moved to [`big-sw-little-sw/lighten`](https://github.com/big-sw-little-sw/lighten); old URLs redirect.

If you ran an earlier build:

- Rename `~/.homelight.json` to `~/.lighten.json`, and change its top-level `"homelight"` key to `"lighten"`.
- Lighten no longer recognises folders named `.homelight-staging`, `.homelight-archive` or `.homelight-replaced-…`. Before running `lighten`, rename each to the same name starting `.lighten-` instead, or remove an empty staging folder.

## Commands

```text
lighten
lighten init      (or: lighten config)
lighten plan
lighten apply
lighten status
lighten guide
```

Running `lighten` starts the full-screen TUI. Named commands open the corresponding TUI workflow. Automation uses prompt-free JSON forms such as `plan --json`, `status --json`, and `apply --json --yes`.

The [user guide](docs/user-guide.md) is for people using Lighten: how to use it, the words it uses, what each rule does on disk and how to undo a change. The TUI shows it on the Guide tab of its Help screen (`?` or F1), and `lighten guide` prints it.

For the JSON commands, their `"schema"` field and exit codes, see [Scripting](docs/user-guide.md#scripting) in the user guide.

## Design

Lighten has a small library-oriented core with a presentation-neutral application workflow. Reconciliation produces structured plans independently of terminal rendering and filesystem mutation. The full-screen TUI and JSON commands are adapters around that workflow.

See the project requirements in [`docs/product-spec.md`](docs/product-spec.md), the architecture in [`docs/architecture.md`](docs/architecture.md), and recorded design choices in [`docs/decisions.md`](docs/decisions.md).

## Development

The project uses Kotlin on a Java 25 toolchain and Gradle (Kotlin DSL) through the Gradle wrapper.

```text
./gradlew build
```

Run the JSON commands directly through Gradle:

```text
./gradlew -q run --args="status --config /path/to/.lighten.json --json"
./gradlew -q run --args="plan --config /path/to/.lighten.json --json"
./gradlew -q run --args="apply --config /path/to/.lighten.json --json --yes"
```

`gradlew run` does not give the application the terminal, so the TUI needs the repository
launcher. It installs the application with `installDist` and runs it:

```text
./lighten
./lighten status --json
./lighten plan
./lighten plan --config /path/to/.lighten.json --json
./lighten apply --config /path/to/.lighten.json --json --yes
```

The default configuration path is `~/.lighten.json`. `lighten init` (or `lighten config`) creates or changes it in the TUI, keeping `~` and `${USER}` as typed but not comments;
hand-written files may use `//` and `/* */` comments and trailing commas:

```json
{
  "lighten": {
    "target-root": "/local/home/${USER}",
    "suggestion-list": "/net/team/lighten/suggestions.json",
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
`~/.cache/.lighten-archive/tool-a`. `archive-root` defaults to `.lighten-archive`
beside the source. If that name is taken, the name gets a short suffix made from the
source path, such as `tool-a-3f9c2b1d`. The root must be on the source's filesystem,
because archiving is a rename.
Review always offers archiving when the policy prompts.

Unknown keys, missing required keys, wrong value types and unknown policy values are
rejected. Errors name the key path, and give the line and column where the JSON
reader knows them. A repeated key keeps its last value. Saving from the setup screen
writes a new file without comments.

`lighten apply` opens Plan for review. Press `a` or Enter to inspect the
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

The `relocations` list is an explicit allow-list. A built-in suggestion is only
managed after it is added and written to this list; leaving a suggestion out
means Lighten leaves it unchanged.

Follow [`AGENTS.md`](AGENTS.md) for repository conventions.
