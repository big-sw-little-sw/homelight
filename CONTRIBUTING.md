# Contributing to Lighten

Lighten is written in Kotlin for the JVM and is built with Gradle (Kotlin DSL). Its releases are GraalVM native binaries for Linux. Before you change it, read these files:

- [`AGENTS.md`](AGENTS.md): the coding conventions.
- [`docs/architecture.md`](docs/architecture.md): the structure.
- [`CONTEXT.md`](CONTEXT.md): the words of the domain.
- [`docs/decisions.md`](docs/decisions.md): the current rules and the reasons for them.

## Build and test

Install JDK 25. The Gradle toolchain finds it, but it does not download it. Use the Gradle wrapper:

```text
./gradlew build                      compile, run all tests, build the distribution
./gradlew check                      compile and run all tests
./gradlew test --tests '*HelpTest'   run one test class
```

The application includes `docs/user-guide.md`, and the guide has its own tests:

- No line is longer than 78 columns.
- The guide has no references to tickets or pull requests.
- No rendered line starts with punctuation.

When you change what users see, update the guide in the same pull request.

## Run it

`./gradlew run` does not give the terminal to the application. Thus, to run the full-screen application, use the `./lighten` launcher. It runs `installDist` and starts the installed build with your arguments:

```text
./lighten
./lighten --config /path/to/.lighten.json
./lighten plan --config /path/to/.lighten.json --json
```

Give `--config` a test file, unless you want to change your own `~/.lighten.json`.

To try Lighten on test data, run `bash scripts/setup-smoke-fixture.sh`. It makes a temporary home, storage and configuration. These have one relocation in each state:

- a move;
- a choice to make;
- rules that keep the target, discard or leave both as they are;
- one relocation that is already in sync.

The script then prints the commands and steps to try. To get new test data, run it again.

To watch the progress of an apply, add the hidden option `--debug-step-delay-ms 3000` before or after the command name. Each action then shows as running for three seconds, and the terminal continues to respond. The option accepts values from 0 to 60000. It has no effect on the `--json` commands.

## Pull requests and CI

Each pull request into `main` runs `.github/workflows/ci.yml`. You cannot merge until its 7 checks pass:

- **JVM verify:** runs `./gradlew build` on Ubuntu with JDK 25.
- **Native build (x86_64), Native build (arm64):** `ci/native/build.sh` builds the native binary in a container. For x86_64 it builds a static musl binary, and for arm64 a glibc 2.17 binary. It also records the output of the JVM build for the CLI comparison.
- **Native test (x86_64), Native test (arm64):** `ci/native/test.sh` compares the CLI output of the native binary with the output of the JVM build. It tests `lighten update` against a local release server. It also runs the full-screen application under `expect`. Then `ci/native/e2e.sh` runs the end-to-end cases:
  - each rule and each blocked state, on disk;
  - special files;
  - an apply that stops partway, because a step fails or the process is killed, and then a new check and apply;
  - the Workspace rows, compared with `plan --json`.

  A case in the `KNOWN_FAILING` list of the script shows a known break of the contract, and it does not fail the job. The job summary shows the screens and the results of the cases.
- **Native distros (x86_64), Native distros (arm64):** `ci/native/distros.sh` runs the tests in containers of older and newer Linux distributions. Then `ci/install/test.sh` tests `install.sh` on them.

The native jobs run on pushes and on pull requests into `main`. To run them on a pull request into a different branch, add the `native` label. You can also run the scripts in `ci/native/` and `ci/install/` on your computer with Docker.

To try the arm64 binary of a pull request in a temporary container, run `ci/try-pr <pr-number>`. You need an authenticated `gh` and Docker on an arm64 host, for example OrbStack on a Mac with Apple silicon. The script downloads the binary from the latest successful CI run of the pull request. It makes sample directories and a configuration, and then starts the application. When you quit the application, you are in a shell in the same container.

## Releasing

Push a tag `v<major>.<minor>.<patch>` on a commit of `main` that passed its 7 checks. The tag can have a pre-release part, for example `v1.0.0-rc.1`.

```text
git tag v1.2.3 origin/main
git push origin v1.2.3
```

Then `.github/workflows/release.yml` does these steps:

1. It checks the commit.
2. It builds the two binaries again with the version of the tag.
3. It publishes a GitHub Release. The release contains the binaries, `SHA256SUMS`, `install.sh` and notes made from the merged pull requests.

A version with a pre-release part is published as a pre-release, and install tools do not install it. If you push the tag before the CI of `main` is complete, the release fails. Run the workflow again after CI passes.

The names of the release assets are a public contract. For the rules, read [Release assets](docs/decisions.md#release-assets) in `docs/decisions.md`.

When you start the workflow by hand, it is a dry run. It builds the same assets and uploads them as a workflow artifact, but it publishes nothing.
