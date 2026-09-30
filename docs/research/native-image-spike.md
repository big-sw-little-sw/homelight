# GraalVM Native Image spike

Date: 2026-09-30. Baseline: `018cb0e` plus uncommitted #25 tests. Question: does
Native Image work acceptably for HomeLight, or should the project move to Rust?

Outcome: it works on macOS and Linux (x86_64, arm64). HomeLight stays on Java.
See [roadmap](../roadmap.md) for the resulting plan and `docs/decisions.md` for
the recorded decisions.

Reproduction material is in `ci/spike/` (see its README). The spike's code
changes are in `ci/spike/patch/`; none are merged.

## Toolchain

- Oracle GraalVM 25.0.3 (GFTC licence). Community Edition was not tested.
- `native-maven-plugin` 1.1.8, `native` Maven profile (`ci/spike/patch/tracked.diff`).
- Build args: `--no-fallback`, `-H:+ReportExceptionStackTraces`,
  `--enable-native-access=ALL-UNNAMED` (silences JLine's JNI `System::load` warning).
- Linux adds `--static-nolibc -march=compatibility` (glibc) or
  `--static --libc=musl -march=compatibility` (musl), passed via `NATIVE_IMAGE_OPTIONS`.

## What had to change, by library

| Library | Failure without change | Change | Kind |
| --- | --- | --- | --- |
| smallrye-config | Every config-loading command fails: `@ConfigMapping` defines `$$CMImpl` classes at runtime | Build step pre-generates them (`NativeConfigMappingGenerator`), relying on SmallRye internals | Hard blocker. Removed by replacing SmallRye with snakeyaml |
| smallrye via jboss-logging | Error paths only: `ExceptionInInitializerError` on malformed config or bad enum | Reflection entries for `ConfigMessages_$bundle*`, `ConfigLogging_$logger*` | Goes away with SmallRye |
| TamboUI 0.5.0-SNAPSHOT | TUI fails: "Built-in bindings not found: standard.properties" | Resource glob `dev/tamboui/tui/bindings/*.properties`, `BackendProvider` service, reflection on `JLineBackendProvider` | Routine metadata. Ours to maintain: TamboUI ships none |
| JLine 3.25.1 | Signal handling | Reflection on `sun.misc.Signal`, `SignalHandler` proxy, `System.console`, JNI `Boolean.getBoolean` | Routine metadata |
| picocli, snakeyaml, jackson-core | None | picocli-codegen output was enough | None |

Metadata: `src/main/resources/META-INF/native-image/.../reachability-metadata.json`,
3.2 KB after trimming the tracing agent's 11.4 KB output (mostly logback, groovy,
log4j, JFR noise). Metadata gaps show up only on paths the tracing run did not
exercise, so CI must smoke-test error paths natively.

## JLine terminal providers

- Default JNI provider extracts `libjlinenative` into `java.io.tmpdir` on every launch.
  On macOS that costs about 200 ms. With `/tmp` mounted `noexec` it prints
  `WARNING: Failed to load native library` and falls back to exec. musl cannot load it.
- Exec provider works everywhere tested and is faster.
- FFM provider is unavailable in the native build: JLine falls back to a dumb
  terminal and the TUI hangs.
- `-Dorg.jline.terminal.provider=exec` at build time does not reach runtime. The
  spike sets it in `TuiLauncher.run` when `org.graalvm.nativeimage.imagecode=runtime`
  and the property is unset; an explicit `-D` still wins.
- `TERM=dumb` (or any dumb-terminal fallback) hangs the TUI on JVM and native:
  nothing renders and only Ctrl-C exits. Existing bug; the app should refuse a
  dumb terminal.

## glibc and Linux compatibility

- GraalVM 25 minimum glibc is officially ambiguous. The certified-platforms page
  (<https://docs.oracle.com/en/graalvm/jdk/25/docs/support>) lists Oracle Linux 7;
  the release notes (<https://www.graalvm.org/release-notes/JDK_25/>) say OL7
  support ended. Building on OL7 works today and may stop in a later release.
- x86_64 builds on `oraclelinux:7-slim` (glibc 2.17, gcc 4.8.5). Needs `zlib-static`
  (`ol7_optional_latest`). Highest symbol required: `GLIBC_2.15`.
- arm64 fails on OL7 (`undefined reference to __aarch64_ldadd8_acq_rel`: gcc 4.8
  libgcc lacks outline atomics). Builds on `oraclelinux:8` with `gcc-toolset-12`.
  Highest symbol: `GLIBC_2.17` (the aarch64 baseline).
- Dynamic deps of `--static-nolibc`: `libc`, `libdl`, `libpthread`, `librt` (+ loader).
  zlib and JDK libraries are static.
- musl x86_64 with Oracle's `musl-toolchain-1.2.5-oracle-00001`: fully static,
  runs on CentOS 6 through Fedora 44 and Alpine. musl arm64 was not attempted.
- GraalVM defaults to x86-64-v3 (AVX2); set `-march=compatibility` explicitly.

| Distro (glibc) | x86_64 glibc | x86_64 musl | arm64 glibc |
| --- | --- | --- | --- |
| CentOS 6 (2.12) | fails: `GLIBC_2.15` not found | pass (smoke) | – |
| Oracle Linux 7.9 (2.17) | pass | pass | pass (smoke) |
| Oracle Linux 8.10 (2.28) | – | – | pass |
| Ubuntu 24.04 (2.39) | pass, incl. noexec `/tmp` | pass, incl. noexec | pass, incl. noexec |
| Debian 13 (2.41) | pass | pass | – |
| Fedora 44 (2.43) | pass | pass | pass |
| Alpine 3.22 | – | pass (smoke) | – |

"pass" is the full suite: 23-step CLI comparison plus the 12-case TUI matrix.
"smoke" is `--version` and `plan --json`.

## Functional checks

- CLI (`compare-linux.sh`): help and version, subcommand help, bogus option,
  missing/malformed config, removed setting, bad enum, `status --json`,
  `plan --json` with CLI overrides, conflict apply, `apply` without `--yes`,
  non-TTY TUI refusal, `init` on existing config, `apply --json --yes`, then
  status/plan/apply again. Exit codes `0 0 2 2 2 2 2 1 1 1 1 0 0 0 0 1 2 2 1 0 0 0 0`,
  identical across every build, JVM and native. Stdout, stderr and resulting
  trees identical, except one extra stack frame in native error traces.
- TUI (`tui-linux.exp`, `render.py`): 120x40, resized to 24x80 and 50x140.
  `TERM` = xterm-256color, screen-256color, tmux-256color, linux, vt100 all pass:
  renders, keys redraw, full redraw after each resize, `q` exits 0, `stty -g`
  unchanged. macOS also covered apply confirm/execute and init/setup screens.
- Native test suite (`native:test`, macOS): 226/234 pass. All 8 failures are test
  construction (spawning `java`, `URLClassLoader`, reflection on
  `CandidateMetadata.Access`), not product behavior.
- Existing issues found, not native-specific: subcommands lack
  `mixinStandardHelpOptions` (`status --help` exits 2); config errors print Java
  stack traces.

## Measurements

| | macOS arm64 native | macOS JVM | Linux arm64 native | Linux arm64 JVM |
| --- | --- | --- | --- | --- |
| Binary | 30.8 MB | 6.6 MB jars + JRE | 30.9 MB | – |
| `--help` | 9.2 ms | 147 ms | 2.2 ms | 128 ms |
| `plan --json` | 14.7 ms | 231 ms | 2.5 ms | 206 ms |
| Peak RSS `--help` / `plan` | 18 / 23 MB | 71 / 84 MB | 18–23 / 22–27 MB | 93 / 106 MB |
| TUI first frame | 285–353 ms JNI, 81–95 ms exec | 664–839 ms | 8–48 ms | – |
| Build | 63–74 s, 2.3–2.8 GB | seconds | 78 s, 3.0 GB | – |

- x86_64 was only measured under Rosetta emulation (381 s build, 5.6 GB, ~150 ms
  startup); those numbers do not represent real hardware.
- Builds need at least 3 GB: 2 GB / 2 CPUs ran out of memory; 3 GB / 2 CPUs took 152 s.

## Not verified

- Real x86_64 hardware, real SSH, real tmux/screen, NFS home, user quotas.
- TUI apply and init flows on Linux (covered via CLI JSON only).
- Community Edition GraalVM; musl arm64.
