# Native Image spike material

Reference only. These scripts were written for a one-off spike and contain
absolute paths from that session (scratchpad and worktree). Roadmap step 4
turns them into real CI; do not run them as-is. Findings are in
`docs/research/native-image-spike.md`.

- `patch/`: the spike's code changes, not merged.
  - `tracked.diff`: `native` Maven profile and the `TuiLauncher` exec-provider default.
  - `files/`: `NativeConfigMappingGenerator` (SmallRye workaround, obsolete once
    SmallRye is removed) and the trimmed `reachability-metadata.json`.
- `linux/`: container builds and tests.
  - `build-linux.sh <amd64-glibc|arm64-glibc|amd64-musl>`: entry point.
  - `Dockerfile.ol7` (x86_64, glibc 2.17), `Dockerfile.ol8` (arm64, gcc-toolset-12),
    `Dockerfile.musl` (Oracle musl toolchain); `build-in-container.sh` runs inside.
    Build contexts need GraalVM 25.0.3 and Maven tarballs, plus the musl toolchain.
  - `testimg/`: runtime test images (apt, dnf, yum families).
  - `run-tests.sh`: per-container suite; `compare-linux.sh`: 23-step CLI comparison.
  - `tui-linux.exp`: pty, key, resize and `stty` checks; `render.py` replays logs with `pyte`.
  - `bench-linux.sh`: hyperfine timings; `hl-jvm`: JVM launcher for comparison.
- `macos/`: the first macOS spike (comparison, TUI expect scripts, tracing-agent
  collection and metadata curation).
