# Contributing to Lighten

Lighten is Kotlin on the JVM, built with Gradle (Kotlin DSL) and released as GraalVM native binaries for Linux. Read [`AGENTS.md`](AGENTS.md) for the coding conventions, [`docs/architecture.md`](docs/architecture.md) for the structure, [`CONTEXT.md`](CONTEXT.md) for the domain words and [`docs/decisions.md`](docs/decisions.md) for recorded decisions.

## Build and test

You need JDK 25 installed; Gradle's toolchain finds it but does not download it. Use the Gradle wrapper:

```text
./gradlew build                      compile, run all tests, build the distribution
./gradlew check                      compile and run all tests
./gradlew test --tests '*HelpTest'   run one test class
```

`docs/user-guide.md` is packaged into the application and has tests of its own: lines of at most 78 columns, no ticket or pull request references, and no line that starts with punctuation once rendered. Any change to what users see updates the guide in the same pull request.

## Run it

`./gradlew run` does not give the application the terminal, so the full-screen application needs the `./lighten` launcher. It runs `installDist` and starts the installed build with your arguments:

```text
./lighten
./lighten --config /path/to/.lighten.json
./lighten plan --config /path/to/.lighten.json --json
```

Point `--config` at a scratch file unless you mean to change your own `~/.lighten.json`.

For a disposable walkthrough, run `bash scripts/setup-smoke-fixture.sh`. It creates a temporary home, storage and configuration with one relocation in each state (a move, a conflict to resolve, rules that adopt, discard or leave unchanged, one already in sync), and prints the commands and steps to try. Run it again for a fresh fixture.

To watch apply progress, add the hidden option `--debug-step-delay-ms 3000`, before or after the command name. Each action then stays in its running state for three seconds while the terminal stays responsive. It accepts 0 to 60000 and does not affect the `--json` commands.

## Pull requests and CI

Every pull request into `main` runs `.github/workflows/ci.yml`. Its 7 checks are required for merging:

- **JVM verify:** `./gradlew build` on Ubuntu with JDK 25.
- **Native build (x86_64), Native build (arm64):** `ci/native/build.sh` builds the native binary in a container: a static musl binary for x86_64, and a glibc 2.17 binary for arm64. It also records the JVM's output for the CLI comparison.
- **Native test (x86_64), Native test (arm64):** `ci/native/test.sh` compares the native binary's CLI output with the JVM's, tests `lighten update` against a local release server, and drives the full-screen application under `expect`. The job summary shows the rendered screens.
- **Native distros (x86_64), Native distros (arm64):** `ci/native/distros.sh` runs the tests in containers of older and newer Linux distributions, then `ci/install/test.sh` tests `install.sh` on them.

The native jobs run on pull requests into `main` and on pushes; on a pull request into another branch, add the `native` label to run them. The scripts under `ci/native/` and `ci/install/` also run locally with Docker.

To try a pull request's arm64 binary in a throwaway container, run `ci/try-pr <pr-number>`. It needs an authenticated `gh` and Docker on an arm64 host, such as OrbStack on an Apple silicon Mac. It downloads the binary from the pull request's latest successful CI run, sets up sample directories and a configuration, and opens the application; quitting it leaves you in a shell in the same container.

## Releasing

Push a tag `v<major>.<minor>.<patch>`, optionally with a pre-release part such as `v1.0.0-rc.1`, on a commit of `main` whose 7 checks have passed:

```text
git tag v1.2.3 origin/main
git push origin v1.2.3
```

`.github/workflows/release.yml` checks the commit, rebuilds both binaries with the tag's version and publishes a GitHub Release with the binaries, `SHA256SUMS`, `install.sh` and notes generated from the merged pull requests. A version with a pre-release part is published as a pre-release, which install tools skip. Tagging before `main`'s CI finishes fails the release; re-run the workflow once CI passes. Asset names are a public contract: see "Release assets" in [`docs/decisions.md`](docs/decisions.md).

A manual run of the workflow is a dry run: it builds the same assets and uploads them as a workflow artifact, and publishes nothing.
