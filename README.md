# Lighten

Lighten moves bulky directories out of your home directory to machine-local storage and leaves a symlink in each one's place.

It is a terminal application, run as `lighten`, for space-constrained or shared home directories, including Linux systems using NFS-mounted home directories. Each move is planned for review before anything changes on disk.

## Install

Lighten is one executable for Linux x86_64 (any distribution) and Linux arm64 (glibc 2.17 or later; on Alpine, install the `gcompat` package first). It needs no Java.

The install script downloads the binary for your machine from the latest release, checks it against the release's `SHA256SUMS`, and installs it as `~/.local/bin/lighten`. It never uses sudo. If that directory is not on your `PATH`, it shows the line that adds it and the startup files in your home it would add it to (for bash, `~/.bashrc` and `~/.bash_profile` or `~/.profile`), and asks first. It skips a file you do not own or cannot write, and says so. It works once the first release is published:

```text
curl -fsSL https://github.com/big-sw-little-sw/lighten/releases/latest/download/install.sh | sh
```

To read the script before running it:

```text
curl -fsSLO https://github.com/big-sw-little-sw/lighten/releases/latest/download/install.sh
less install.sh
sh install.sh
```

Options go after `sh install.sh`, or after `sh -s --` when piping:

```text
--version 1.2.3     install that release instead of the latest
--dir ~/bin         install into another directory
--no-modify-path    do not offer to change PATH; only show the line
```

Run `lighten update`, or the script again, to update Lighten in place. Without `curl`, download it with `wget -qO- <url> | sh`; the script uses whichever of the two it finds.

These tools also install Lighten from its [GitHub Releases](https://github.com/big-sw-little-sw/lighten/releases), once the first release is published:

```text
mise use -g github:big-sw-little-sw/lighten
eget big-sw-little-sw/lighten --to ~/.local/bin
ubi --project big-sw-little-sw/lighten --in ~/.local/bin
```

mise updates what it installed (`mise upgrade`). `lighten update` updates an install made with the script, eget, ubi or by hand: it runs the latest release's install script on the directory that holds the running `lighten`, so it needs `curl` or `wget` too. `lighten update --check` only shows the installed and the latest version, and `lighten update --version 1.2.3` installs that release, even an older one.

To download by hand, take `lighten-<version>-linux-x86_64-musl` or `lighten-<version>-linux-aarch64-gnu` and `SHA256SUMS` from a release, then:

```text
sha256sum --check --ignore-missing SHA256SUMS
install -m 755 lighten-<version>-linux-<arch>-<libc> ~/.local/bin/lighten
```

## First run

Run `lighten`. With no configuration file yet, press `i` to create one: type where storage is in **Target root**, pick the directories to move from the built-in suggestions, and press `s` to save. Saving writes `~/.lighten.json` and nothing else.

The Workspace then shows what Lighten would do for each directory. Press `a` to review every step, and `y` to apply. Nothing on disk changes before `y`.

```text
lighten                       the full-screen application
lighten config                open Configuration directly (also: lighten init)
lighten status --json         what is on disk now, as JSON
lighten plan --json           the plan, as JSON; changes nothing
lighten apply --json --yes    apply the plan if it needs no choices, as JSON
lighten guide                 print the user guide
lighten update                update Lighten
```

`--config <file>` uses another configuration file than `~/.lighten.json`.

## Documentation

The [user guide](docs/user-guide.md) covers how to use Lighten, the words it uses, every configuration setting, what each rule does on disk, how to undo a change and the JSON commands. Inside Lighten, press `?` and open the Guide tab, or run `lighten guide`.

To build or change Lighten, see [`CONTRIBUTING.md`](CONTRIBUTING.md).
