# Lighten Decisions

The rules Lighten follows today and why, grouped by theme. Each rule gives the decision, a one-line reason, the omissions still open as `[skipped: X, add when Y]`, and the issues and pull requests behind it. Implementation details, measurements and rejected alternatives live in those issues and pull requests and in git history. Superseded entries are listed in the [archive](#archive). How decisions are made is in [`AGENTS.md`](../AGENTS.md).

## Product and safety

### One library core, two thin adapters

A presentation-neutral workflow and reconciliation core sits under a full-screen TUI for people and `--json` commands for automation, in one binary. No dependency injection, classpath scanning, plugin framework or application framework (Spring, Quarkus). Why: a filesystem tool needs fast startup and plain tests, and every layer on the safety-critical path is risk. See [`architecture.md`](architecture.md). (2026-08-29, 2026-09-07, 2026-09-12)

### Apply exactly the reviewed plan

The TUI keeps the plan shown in Review. Preflight checks its expected states before anything runs; drift makes the plan stale and needs a new check. Lighten never re-plans and substitutes after `y`. Why: the user confirmed those steps, not others. (2026-09-12)

### Directories only, no ownership registry

Lighten relocates directories; a source that is a file is blocked. It keeps no record of what it did: an already-correct link is recognized from the disk, and adopting or replacing anything else needs a rule or a choice. Why: no recovery, staleness or lifecycle state to get wrong. (2026-09-08)

### Directory permissions are kept or the move is refused

Every published directory keeps the nine POSIX permission bits of its source; a target filesystem that cannot store them refuses publication. Ownership, ACLs, timestamps, extended attributes and special bits are out of scope. Why: relocated homes often go to shared storage, where widening `0700` exposes private data. (2026-09-30)

### Existing ancestors may be symlinks; overlap compares real paths

An existing ancestor may be a link to a directory (`/home -> /var/home`), but every directory Lighten creates, and the source, target and staging root themselves, must be real. Overlap compares real spellings: the loader refuses relocations that overlap only through a link, and overlap visible as written blocks the plan. The planner stays pure. Why: Fedora Atomic and macOS have linked ancestors; planner I/O could block on a slow mount on every choice. (#128)

- `[skipped: real-path check of an archive path against its own relocation, add when an archive root reached through a symlink is reported]`
- `[skipped: re-checking aliased overlap in preflight, add when ancestor links are seen to change between review and apply]`
- `[skipped: removing toRealPath() from cli and application test fixtures, add when those tests next change]`
- `[skipped: rewriting ensureDirectories's walk without mutable locals, add when it changes for another reason]`

### One staging operation per target

A move copies into `operation-<sha256 of the target's real spelling>` in the staging root, under a non-waiting lock on `operation-<same>.lock`, verifies, then publishes with one atomic rename. A target already being staged, by this or another process, is an environment failure; leftovers at that name are cleared the next time the target is staged. Why: different targets never touch each other's files, so relocations sharing a staging root run concurrently. (#130)

- `[skipped: sweeping other targets' leftovers, add when abandoned staging copies are reported]`
- `[skipped: deleting per-target lock files, add when users object; safe deletion is defeated by inode reuse]`

### A source is replaced by its link in atomic steps

The source is renamed aside to `.lighten-replaced-<name>-<sha256 of the target>`, the link takes its place, then the set-aside tree is deleted. A set-aside tree is recognized only while the source links to that target; the next plan deletes it. Why: a crash never leaves a partial source that a saved rule could treat as "both exist". (#132)

- `[skipped: finishing an interrupted replacement while the source is absent, add when users ask why an only-target conflict follows a crash]`

### A crash between publishing and setting the source aside is left as is

It leaves two whole directories, and the next plan reports "both exist". Why: recovering it would rely on brittle naming conventions (user decision). (B6)

- `[skipped: crash recovery between copying and linking, add when users report "both exist" after an interrupted apply]`

### The archive destination is the source's name under the archive root

Archive-source moves a source to `<archive root>/<source name>`, or `<source name>-<8 hex digits of its real spelling's SHA-256>` when that name is taken on disk or by another relocation. The default archive root is `.lighten-archive` beside the source. Why: readable in the common case, and the same state always plans the same path. (#142)

- `[skipped: a counter or further suffix when the suffixed name is also taken, add when users hit it]`

### The planner blocks a folder that is not a folder

Inspection walks every folder a step may create or work in, and the planner blocks a relocation whose steps need a path that is a file, link or unreadable; the staging root must be a real folder. Details offer a choice that avoids the folder when one exists. Why: the apply used to stop halfway and blame a change made before `y`. (#163)

- `[skipped: a plan-time check that the staging root is on the target's filesystem and that both support POSIX permissions, add when a user's apply stops on either]`
- `[skipped: preflight re-checking these folders between review and y, add when a folder breaking in that window is reported]`
- `[skipped: a stricter check when a configured staging root is also a source, target or archive parent, add when someone configures one that way]`

### The copy skips sockets and stops on named pipes and device files

Sockets are left out of the copy and its check, and Results name them. A named pipe or device file stops the copy before it is opened; the copy is thrown away and nothing is published. There is no plan-time check. Why: programs recreate sockets; a pipe or device could block or never end; walking every source tree on every check is too slow. (#198)

- `[skipped: plan-time block for pipes and devices, add when a user hits one]`
- `[skipped: saying in Review that sockets will be skipped, add when users are surprised by it in Results]`
- `[skipped: a socket in the native comparison, add when CI containers have a tool that makes one]`

### Independent relocations run two at a time; an apply waits on a hung mount

Relocations whose paths do not overlap run on at most `RELOCATION_CONCURRENCY` (2) virtual threads through `mapBounded`; steps within one relocation stay in order. After a failure no new relocation starts and running ones finish. A step blocked in the kernel, such as on a stuck NFS mount, holds its thread and the apply waits: no timeout and no abandoned threads. Quitting during an apply means "exit when it finishes"; to stop sooner the user ends the process. Why: that is safe, because the source changes only at atomic renames, the staging lock dies with the process, and the next apply of that target clears its leftover copy. (#10)

### Only environment failures fail an action; bugs are internal errors

An action fails only for I/O or an expected environment failure (drift, staging on another filesystem, no POSIX permissions), reported as a typed `ActionFailure`. Any other exception is a bug: it propagates after cleanup and reads `Internal error (please report): <Type>: <message>`, exit 70, with no stack trace. Why: a bug must not pass as a disk problem or an invalid file. (2026-10-04, #172)

- `[skipped: printing the stack trace, add when a bug report needs more than the exception type and message, e.g. behind a debug option]`

### Choices are for one apply; rules are saved on request

A Workspace choice applies to the next apply only; any check, save or apply clears it. `s: Always do this` saves it as the relocation's rule after a dialog that warns when the rule deletes data. A missing rule means "Ask each time" (`prompt`, omitted when written). Why: a one-off "delete both" must not become permanent for `apply --json --yes`. (#116)

- `[skipped: keeping one-time choices across a re-check, add when users re-check often with many open choices]`
- `[skipped: showing a missing rule apart from an explicit prompt, add when a global defaults layer exists]`
- `[skipped: generic Policy<C>, add when a fourth rule appears or code needs to treat all rules the same way]`

### Ignored sources are listed, never planned

`x` ignores a source: Lighten plans nothing for it and never offers it, but always shows it. A path cannot be both a relocation and ignored; the loader and every save refuse it. Why: the user can always see and undo what they ignored. (#147)

- `[skipped: listing ignored paths in Configuration's list with d to remove one, add when users want to manage ignores without Browse]`
- `[skipped: shared ignore lists in their own files, add when users want ignores shared across machines]`
- `[skipped: naming which app owns a path, add with #5/#117]`
- `[skipped: an ignored count in the Workspace summary rows, add when users miss it]`

### The name is Lighten

The tool is Lighten, the command `lighten`, the file `~/.lighten.json` with key `"lighten"`, and on-disk names start with `.lighten-`. No compatibility with earlier names. Why: the old name was long and held by a trademark; nothing was released. (#174)

## Platform and build

### Kotlin on JVM 25, released as native Linux binaries

Code is Kotlin with kotlinx.serialization. Releases are GraalVM Native Image binaries for Linux x86_64 (fully static, musl) and Linux arm64 (`--static-nolibc`, glibc 2.17+, `-march=compatibility`). macOS is a development platform only. Why: null safety, data and sealed types, compile-time serializers; native startup and memory suit the target machines. (#40, 2026-09-30)

The 2026-09-30 spike (Oracle GraalVM 25.0.3): native start 2–3 ms against 130–210 ms on the JVM; TUI first frame 8–48 ms; peak RSS 18–27 MB against 93–106 MB; binary about 31 MB; a native build needs 3 GB of RAM. The arm64 build needs gcc 12 (Oracle Linux 8). The suite passed on Oracle Linux 7.9, Ubuntu 24.04 (with `noexec` `/tmp`), Debian 13 and Fedora 44; musl x86_64 ran down to CentOS 6 and on Alpine.

### One Gradle module with package boundaries

Gradle Kotlin DSL, the official `org.graalvm.buildtools.native` plugin, one module. Why: package boundaries separate concerns without multi-module upkeep. (2026-09-07, 2026-10-01)

### Native support through supported mechanisms only

No GraalVM internals (`@Substitute`, `@TargetClass`, svm APIs), no `kotlin-reflect`, no in-process HTTP or TLS. Native builds use JLine's exec provider. Why: internals tie upgrades to GraalVM releases; TLS added 15 MB; JLine's JNI provider fails on a `noexec` `/tmp` and under musl, and its FFM provider hangs. (2026-10-01, #169)

### Keep picocli; generate its metadata

picocli stays the CLI library. Its reflection metadata is generated from the compiled classes on every build by picocli-codegen. Why: Clikt reached full option parity, but needed about 45 lines re-creating picocli features (inherited options, rejecting repeated options, exit codes, UTF-8 output); a 2.5 MB smaller binary and dropping codegen did not outweigh that and the help-text change. Generated metadata cannot drift. (#70, #156, #161)

### Stay with TamboUI

TamboUI (pinned in `gradle/libs.versions.toml`) is the TUI toolkit, used as fully as possible; gaps are worked around in our code. Why: no Kotlin TUI framework offers a full-screen app with layout widgets in a static native binary. Revisit if Mosaic ships alternate-screen and tested native support, or TamboUI stalls for about six months. (#88)

## Configuration and formats

### JSON, read and written by kotlinx.serialization

Configuration and suggestion lists are JSON with `//` and `/* */` comments and trailing commas. One set of `@Serializable` classes defines each format; kotlinx rejects unknown keys, missing keys, wrong types and unknown rule values, and a repeated key keeps its last value. The loader adds only domain rules. Writing uses `encodeDefaults = false` and drops comments. No environment or system-property overrides. Why: precise errors, no YAML aliases or tags in shared input, no hand-written parser. (#49, #79)

### Paths are full or start with `~/`, and stay as written

Every path in the file is full or starts with `~/`, after `${USER}` is filled in; a relative one is refused. Paths expand only when converted to domain types, so a saved file keeps `~` and `${USER}`. `source-root` defaults to `~`; a target is derived from the source's place under it unless given. Why: a relative path would depend on where Lighten runs. (#114)

### One editor for creating and editing

Configuration edits the file's own shape, checked by the loader's own conversion, so path rules have one owner. A new file is written directly; replacing one asks first, says comments are lost, refuses if the file changed since it was read, and writes atomically. Only a file that loads can be edited. Why: one set of rows and rules instead of two editors. (#114)

- `[skipped: opening an invalid file in Configuration, add when users ask to fix a broken file from the editor]`
- `[skipped: per-row changed/new markers, add when users lose track of edits in long lists]`
- `[skipped: Tab completion for paths, add when typing paths becomes a complaint]`
- `[skipped: a way to reorder relocations in Configuration, add when users ask to rearrange without editing the file]`

### An unreadable configuration says what is wrong and how to fix it

The loader throws `InvalidConfigurationException` with the path and line, and kotlinx's messages are reworded where a recognizer knows them (syntax, wrong kind, missing or unknown key, bad rule value); unknown wording passes through. The TUI and CLI both show the problem, `To fix it` and `To start over`. Why: raw parser text gave no next step. (#164)

- `[skipped: start over with a backup from inside the app, add when users ask]`
- `[skipped: own steps for a file Lighten cannot open (a directory at the path, no permission, not UTF-8), add when a user hits one]`

## CLI and JSON contract

### TUI for people, JSON for scripts

`lighten` and its commands open the TUI; there is no plain-text human output. `--json` never starts a terminal. A command without `--json` and without a terminal exits 2. `apply --json` needs `--yes`, which confirms a plan the rules already resolve and never resolves a choice. Why: one human interface to keep right, and automation that cannot prompt. (2026-09-12)

### Versioned responses and stable exit codes

`status --json`, `plan --json` and `apply --json --yes` start with `"schema": 1`; `status` has one shape whether configured or not. Exit codes: 0 success, 1 configuration or apply failure, 2 usage, 70 internal error. JSON paths stay full. `apply --json` keeps the executor's text in `message`. Control characters are escaped in lower-case hex (`\u001f`), as kotlinx writes them. Why: scripts need a stable contract; escape case is invisible to JSON parsers. (#19, #61, #172, #201)

- `[skipped: one shared envelope for every outcome across commands, add when someone scripts against Lighten and needs it]`
- `[skipped: JSON errors on stdout (config errors, internal errors stay one stderr line), add when a script needs to parse them]`
- `[skipped: config validate --json, add when a script needs validation without planning]`
- `[skipped: a machine-readable failure kind in apply --json, add when a script needs to tell failures apart]`
- `[skipped: merging the Missing and Unconfigured evaluation states, add when the JSON contract is revisited]`
- `[skipped: redirected-I/O/terminal-isolation and source-audit test suites beyond what exists]`

### Paths on screen use `~`; machine output keeps full paths

Every path people read shows the home directory as `~` (never the source root); `--json` keeps full paths. Paths stay `Path`s inside messages (`PathText`) until shown, and exception text never shows a Java type name. Why: shorter, and scripts need exact paths. (#201)

- `[skipped: ~ in Configuration's fields, which show the file's text as written, add when a user wants the editor to rewrite full home paths]`

## Release and distribution

### A version tag publishes a GitHub Release

Pushing `v<major>.<minor>.<patch>[-<pre-release>]` publishes a release, only if the commit is on `main` and the 7 required checks passed on it. The tag's version is built in; notes are GitHub's generated ones; a pre-release part publishes a pre-release. Why: only tested commits are released, with no manual steps. Steps are in [`CONTRIBUTING.md`](../CONTRIBUTING.md). (#167)

- `[skipped: signed releases (minisign or cosign), add when users outside the maintainer's machines install it]`
- `[skipped: JBang catalog, add when a JVM build is wanted for macOS/Windows or architectures without native binaries]`
- `[skipped: Homebrew tap, add when Linuxbrew users ask or macOS binaries ship]`
- `[skipped: waiting for the checks in the release workflow, add when tagging right after a merge becomes common]`

### Release assets

Asset names are a public contract for `install.sh`, `lighten update` and tools that install from GitHub Releases (mise, ubi, eget). They never change once published.

```text
lighten-<version>-linux-x86_64-musl    static musl binary, any Linux x86_64
lighten-<version>-linux-aarch64-gnu    glibc 2.17+ binary, Linux arm64
SHA256SUMS                             sha256sum output for both binaries
install.sh                             the install script
```

Why: bare binaries need no `tar`; `uname -m` names need no table; the libc suffix says what each needs. Alpine on arm64 needs `gcompat`. (#167)

- `[skipped: lighten-<version>-linux-aarch64-musl, add when Alpine arm64 users ask; eget would then ask which arm64 binary to take]`
- `[skipped: a <asset>.sha256 file per binary for eget's check, add when eget users ask]`
- `[skipped: aqua-registry entry, add when aqua users ask]`

### Install script

`install.sh` (POSIX `sh`) picks the asset from `uname -m`, reads the latest version from `SHA256SUMS` (no GitHub API), checks the hash, test-runs `--version` and renames into place, `~/.local/bin` by default. It never calls sudo, asks once before editing a shell startup file, and edits only files the user owns in their home. Why: one safe install and update path that leaves an existing install untouched on failure. (#168)

- `[skipped: signature checks beyond SHA256SUMS, add with signed releases]`
- `[skipped: an uninstall option, add when users ask; removing ~/.local/bin/lighten and the marked line is the uninstall]`
- `[skipped: a containers test of the shasum fallback, add when a supported distro lacks sha256sum]`
- `[skipped: a system-wide PATH entry (/etc/profile.d) for root installs, add when admins install for all users]`

### `lighten update` runs the release's install script

`update` checks `SHA256SUMS`, then runs that release's `install.sh` on the running binary's real directory with `--no-modify-path`, using `curl` or `wget`. It never downgrades without `--version`, and refuses development builds, the JVM, mise installs, unwritable directories and a binary not named `lighten` before downloading. `--check` only reports. Why: one copy of the install rules, and no TLS in the binary (about 15 MB). (#169)

- `[skipped: automatic update notice, add when users run old versions without knowing]`
- `[skipped: signature verification, add with signed releases]`
- `[skipped: a distinct --check exit code for "update available", add when a script needs it]`
- `[skipped: a native end-to-end update in CI, add by testing the release build in release.yml]`
- `[skipped: detecting aqua or Homebrew installs, add when either is a documented install method]`

## Suggestion-list curation

### Lists are files, the built-in list first, yours merged in

The built-in list ships in the binary; `suggestion-list` names an optional shared file, typically on storage the machines already share. Lighten never fetches lists over HTTP or Git. When both lists name a directory, yours gives its app and advice; an app takes your list's category when yours gives one, else the built-in one. Lists are bounded (size, records, string length) and strictly decoded. Why: no network failure modes or credentials; a team can extend the built-in list without retyping it. (2026-09-30, #115, #165)

### Categories group apps, in the file's order

Each app may name a `category`; Browse shows categories, apps and directories in first-appearance order, with Other tools and Other directories last. Built-in order: Editors, Python, Rust, C and C++, JVM, JavaScript, Go, Ruby, Android, Build tools, Version managers. A version manager for one language sits under that language. Why: whole stacks can be added with one key, and the file reads like the screen. (#165, #176, #191)

- `[skipped: moving an app to another category without naming one of its directories, add when teams want to retag built-in apps wholesale]`
- `[skipped: the category in Details' "Suggested by" lines, add when users ask which list set it]`

### Each directory is checked and the narrowest safe one is listed

A directory joins the built-in list only after the tool was installed in a container, relocated with `lighten apply`, used again and cleaned with its own commands, with paths taken from the tool's docs or source. The list names the cache or install folder, not a parent that also holds the tool, its shims or settings, unless the tool needs the whole parent on one filesystem (Volta). Where a tool's clean command replaces the link with a folder, the entry carries a `caution`, shown in Browse Details. Why: a suggestion must keep the tool working after the move. (#165, #176, #191)

- `[skipped: conda, mamba and micromamba directories, add when users ask for a specific install layout]`
- `[skipped: .cache/mise, add when users report it growing large]`
- `[skipped: legacy .fnm/node-versions, add when users with old installs ask]`
- `[skipped: Volta's .volta/tools alone, add if Volta stages installs inside tools]`
- `[skipped: a caution on the Browse row itself, add when users miss cautions that only Details shows]`
- `[skipped: CMake, add when it gains a default per-user cache]`
- `[skipped: Zed's whole data directory; #198 removed the socket that blocked it, add when users ask for Zed's database and logs to move too]`
- `[skipped: .platformio/platforms and .platformio/.cache, add when users report them large]`
- `[skipped: .cache/bazelisk, add when users report versions piling up]`
- `[skipped: .local/share/Google (Android Studio plugins), add when users report it large]`
- `[skipped: keeping hard links when moving, add when users move Hunter or similar caches and ask about the space]`

## TUI

The rules themselves are in [`tui-design.md`](tui-design.md), which holds only current rules; history is here and in git.

- **Full-screen TamboUI app** with screens for every command; not a desktop GUI, which needs a display over SSH. ([§2](tui-design.md#2-screens-and-navigation), 2026-09-12)
- **Its own visual language**, designed around relocation work, not a generic dashboard. ([§1](tui-design.md#1-principles))
- **Harbor palette on Lighten's own background**, basic colors as fallback; color may carry meaning only when the screen also says it another way. ([§4 Color](tui-design.md#color))
- **TamboUI owns focus, fields, choices and dialogs**, with its `standard` key bindings; Esc goes back one level and never exits. ([§3](tui-design.md#3-keys-and-focus))
  - `[skipped: TamboUI FormElement, add when it supports per-field key handling and a dialog on top]`
- **Mouse captured for the wheel only**; clicks do nothing, and selecting text takes the terminal's bypass modifier. ([§3](tui-design.md#3-keys-and-focus), #146)
  - `[skipped: click to focus or select, add when users ask]`
  - `[skipped: the mouse wheel over Configuration's list and fields, add when users ask]`
- **Dialogs for one question, screens for work.** ([§4 Dialogs](tui-design.md#dialogs))
  - `[skipped: dimming the whole screen behind a dialog, add when the border and lost focus are not enough separation]`
  - `[skipped: wrapping long paths in dialogs, add when a path cut off at 80 columns is reported]`
- **Help is a screen with two tabs**, This screen and Guide; the user guide is its only text and the help lines share one source with it. ([§4 Help screen](tui-design.md#help-screen), #146)
  - `[skipped: tying key handlers to their listing, add when a walkthrough finds a listed key that does nothing]`
  - `[skipped: first-run tour, add when walkthroughs show Help is not found]`
  - `[skipped: PageUp/PageDown and Home/End in Review's Action details, add when long action details are reported]`
  - `[skipped: "Leave" group for q/Esc, add when a walkthrough still misreads q or Esc after the descriptions]`
- **Workspace** keeps the file's order within each urgency group, says the decision once, and warns only where data is lost for good. ([§5](tui-design.md#5-workspace), #110, #183)
  - `[skipped: plain-language reasons for blocked rows, add when the planner's reasons are reworded for JSON output too]`
  - `[skipped: keeping the Decision line in view when Details takes focus, add when users miss it]`
- **Quitting asks before forgetting one-time choices.** ([§3 Quit](tui-design.md#quit), #111)
- **Review, Applying and Results** list the plan as headings and rows; progress never moves the selection, which moves once at the finish; a failed step says what is there and what to do. ([§6](tui-design.md#6-review-applying-and-results), #111, #157, #172)
  - `[skipped: a softer Review warning for a verified "Replace source with a link" step, add when the Review walkthrough finds it alarming]`
  - `[skipped: a status line in a relocation's Details, add when the step marks are not enough]`
  - `[skipped: folding a relocation's steps, add when plans are long enough that users ask to fold them]`
  - `[skipped: indenting action rows more than two cells, add when the plan list is wider at 80 columns or its rows become one line each]`
  - `[skipped: plain words for the preflight refusal's diagnostics, add when a walkthrough finds them unclear]`
  - `[skipped: own words for errors the OS reports only as a reason (no space left, read-only filesystem), add when a user hits one]`
- **Configuration** has labels beside fields and opens Browse on a first run. ([§7](tui-design.md#7-configuration), #114, #189)
  - `[skipped: selecting the first added relocation when the first-run Browse closes, add when users miss where their picks went]`
- **Browse** is a list of categories, apps and directories with bare marks; it always shows its lists, drops stale results while checking, and can filter to what is found on this machine. ([§8](tui-design.md#8-browse), #115, #165, #190)
  - `[skipped: showing previous results while checking again, add when re-checks are slow enough that blank rows annoy users]`
  - `[skipped: per-row list history, add when users need to know a list used to suggest a row]`
  - `[skipped: PageUp/PageDown in Browse, add when suggestion lists grow past a few screens]`
  - `[skipped: the year in "file updated", add when lists older than a year are common]`
  - `[skipped: folding categories, add when the built-in list grows past a few screens]`
  - `[skipped: / to filter by text, add when lists grow past two screens]`
  - `[skipped: first-run Browse with f on, add when new users report scrolling past tools they don't have]`
- **Bare marks for choices**: `●` chosen, `○` not, in green and bold. ([§4 Layout and glyphs](tui-design.md#layout-and-glyphs), #173)
- **Screens are checked at 80x24 and 120x30.** ([§10](tui-design.md#10-verification))
  - `[skipped: 200x50 checks, add when a wide-terminal layout bug appears]`

## Archive

Superseded or history-only entries, with the names they used at the time. Full text is in git history.

- 2026-08-29: Keep integrations behind adapters. History: no adapter layer was needed; the core reads the filesystem through `java.nio.file`, and lists are not fetched.
- 2026-08-29: Design for native-image compatibility. Done: native binaries are the release.
- 2026-09-07: Prefer guided CLI over desktop GUI. Superseded by the full-screen TUI; the desktop GUI rejection stands.
- 2026-09-07: Combine non-interactive and guided CLI in a single binary. Superseded by TUI for people, JSON for scripts.
- 2026-09-07: Maintain a single Maven module. Maven superseded by Gradle; the single module stands.
- 2026-09-07: Use plain Java 25 and targeted libraries. Java, SnakeYAML and Jackson superseded by Kotlin and kotlinx.serialization.
- 2026-09-30: Stay on Java; ship Linux native binaries. Java superseded by Kotlin; native targets and spike numbers stand. Rust was viable but a port cost about 13k lines.
- 2026-09-30: Replace smallrye-config with snakeyaml. snakeyaml superseded by JSON; dropping environment overrides stands.
- 2026-09-30: Relax candidate-list strictness. Superseded by JSON.
- 2026-09-30: Run agent work through a cloud coordinator. History-only.
- 2026-10-01: Move to Kotlin and kotlinx.serialization. Now "Kotlin on JVM 25"; the migration (#40, `kotlin-migration` branch) is done.
- 2026-10-01: Read JSON configuration strictly. Superseded by "Let kotlinx.serialization own the file format" (#79).
- 2026-10-01: Keep Java whitespace semantics for validation. Moved to `AGENTS.md` rule 10.
- 2026-10-01: Keep threads and locks during the Kotlin migration. Moved to `AGENTS.md` rule 11; #10 chose bounded virtual threads.
- 2026-10-04: How design and simplification decisions are made. Moved to `AGENTS.md`.
- 2026-10-04: Application-layer cleanup. Done.
- 2026-10-05: Functions return their results. Moved to `AGENTS.md` rule 5.
- 2026-10-06: Review's plan is a TamboUI tree. Replaced by headings and rows (#157).
- 2026-10-06: Browse is a tree of suggestions. Replaced by the heading list, variant C (#115).
- 2026-10-07: Browse groups apps by ecosystem. The level is now "category" (#174).
- 2026-10-07: Rename HomeLight to Lighten: the repository rename it left open is done.

## How to add

Add a decision under its theme, as a `###` rule in the form above: the rule, a one-line why, open `[skipped: …]` items, and the issue or pull request. Change a rule in place when it changes, and add a line to the archive when a rule is removed or replaced. Put details in the pull request, not here.
