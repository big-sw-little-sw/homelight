# Lighten

Lighten moves large directories out of your home directory to storage on the local machine. It puts a symlink in the place of each directory that it moves.

Lighten is a terminal application. You start it with the `lighten` command. It is for home directories that have little space or are shared, for example Linux home directories on NFS. Lighten shows each move in a plan for you to review before it changes anything on disk.

## Install

Lighten is one executable file. It runs on Linux x86_64 (any distribution) and on Linux arm64 with glibc 2.17 or later. On Alpine arm64, install the `gcompat` package first. Lighten does not need Java.

The install script does these steps:

- It downloads the binary for your machine from the latest release.
- It checks the binary against the release's `SHA256SUMS` file.
- It installs the binary as `~/.local/bin/lighten`.

The script never uses sudo. If `~/.local/bin` is not on your `PATH`, the script shows the line that adds it. It also shows the startup files in your home directory that it would add the line to. For bash, these are `~/.bashrc` and `~/.bash_profile` or `~/.profile`. The script asks you before it changes them. If you do not own a file or cannot write to it, the script does not change it and tells you.

The script works after the first release is published:

```text
curl -fsSL https://github.com/big-sw-little-sw/lighten/releases/latest/download/install.sh | sh
```

To read the script before you run it:

```text
curl -fsSLO https://github.com/big-sw-little-sw/lighten/releases/latest/download/install.sh
less install.sh
sh install.sh
```

Put options after `sh install.sh`. When you pipe the script into `sh`, put them after `sh -s --`:

```text
--version 1.2.3     install that release instead of the latest
--dir ~/bin         install into another directory
--no-modify-path    do not offer to change PATH; only show the line
```

To update Lighten, run `lighten update` or run the script again. If you do not have `curl`, use `wget -qO- <url> | sh`. The script uses `curl` or `wget`, whichever it finds.

These tools also install Lighten from its [GitHub Releases](https://github.com/big-sw-little-sw/lighten/releases), after the first release is published:

```text
mise use -g github:big-sw-little-sw/lighten
eget big-sw-little-sw/lighten --to ~/.local/bin
ubi --project big-sw-little-sw/lighten --in ~/.local/bin
```

If you installed Lighten with mise, update it with `mise upgrade`. `lighten update` updates an install that you made with the script, eget, ubi or by hand. It runs the install script of the latest release on the directory that holds the running `lighten`. Thus it also needs `curl` or `wget`.

- `lighten update --check` shows the installed version and the latest version. It changes nothing.
- `lighten update --version 1.2.3` installs that release, also when it is older than the installed one.

To install by hand, download `SHA256SUMS` and one binary from a release. The binaries are `lighten-<version>-linux-x86_64-musl` and `lighten-<version>-linux-aarch64-gnu`. Then run:

```text
sha256sum --check --ignore-missing SHA256SUMS
install -m 755 lighten-<version>-linux-<arch>-<libc> ~/.local/bin/lighten
```

## First run

Run `lighten`. If you do not have a configuration file, press `i` to make one:

1. In **Target root**, type where your storage is.
2. From the built-in suggestions, select the directories to move.
3. Press `s` to save. Saving writes `~/.lighten.json` and nothing else.

The Workspace then shows what Lighten would do for each directory. Press `a` to review all the steps, then press `y` to apply them. Lighten changes nothing on disk before you press `y`.

```text
lighten                       the full-screen application
lighten config                open Configuration directly (also: lighten init)
lighten status --json         what is on disk now, as JSON
lighten plan --json           the plan, as JSON; changes nothing
lighten apply --json --yes    apply the plan if it needs no choices, as JSON
lighten guide                 print the user guide
lighten update                update Lighten
```

To use a configuration file other than `~/.lighten.json`, add `--config <file>`.

## Documentation

The [user guide](docs/user-guide.md) tells you how to use Lighten. It gives the words that Lighten uses and all the configuration settings. It tells you what each rule does on disk, how to undo a change and how to use the JSON commands. To read it in Lighten, press `?` and open the Guide tab. You can also run `lighten guide`.

To build or change Lighten, read [`CONTRIBUTING.md`](CONTRIBUTING.md).
