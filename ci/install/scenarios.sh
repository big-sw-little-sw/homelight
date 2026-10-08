#!/bin/sh
# Runs inside a container started by test.sh; not meant to be run directly.
# Mounts: /ci this directory (ro), /install.sh (ro). Environment: BASE, the asset server with the
# good/ and bad/ trees described in test.sh, and VERSION, the version it serves.
# Prints one line per scenario, with the output under a failure. Exit status is non-zero when
# any scenario fails.
# shellcheck disable=SC2088 # expected output shows home paths as ~/...
set -u

out=/tmp/install-output
bin=.local/bin/lighten
line='export PATH="$HOME/.local/bin:$PATH"'
failures=0

have() {
  command -v "$1" > /dev/null 2>&1
}

# scenario <name> <function> [show]: runs the function in a subshell with a fresh $home.
# "show" prints its output even when it passes, as a transcript.
scenario() {
  home=$(mktemp -d)
  : > "$out"
  if (cd / && "$2") >> "$out" 2>&1; then
    echo "ok   $1"
    [ "${3:-}" != show ] || sed 's/^/     | /' "$out"
  else
    echo "FAIL $1"
    sed 's/^/     | /' "$out"
    failures=$((failures + 1))
  fi
  rm -rf "$home"
}

# Inside a scenario: ends it as failed when the command fails.
check() {
  "$@" || { echo "check failed: $*"; exit 1; }
}

fails() {
  ! "$@"
}

# install [options]: runs the script without a terminal, as CI or a pipe to a log would.
install() {
  HOME=$home SHELL=/bin/bash LIGHTEN_INSTALL_BASE_URL=${tree:-$BASE/good} sh /install.sh "$@" < /dev/null
}

# interactive <answer> [options]: runs it on a terminal as curl | sh does; see answer.exp.
interactive() {
  HOME=$home SHELL=${shell:-/bin/bash} LIGHTEN_INSTALL_BASE_URL=$BASE/good expect /ci/answer.exp "$@"
}

said() {
  grep -qF -- "$1" "$out"
}

installed() {
  [ "$("$home/$bin" --version)" = "lighten $VERSION" ]
}

no_leftovers() {
  [ -z "$(find "$home/.local/bin" -name '.lighten-install.*' 2> /dev/null)" ]
}

count() {
  grep -cxF -- "$1" "$2"
}

fresh() {
  check install
  check installed
  check said "  $line"
  check said "Not changing ~/.bashrc without a terminal"
  check [ ! -e "$home/.bashrc" ]
  check no_leftovers
}

rerun() {
  check install
  check install
  check said "Reinstalled lighten $VERSION."
  check installed
  check no_leftovers
}

update() {
  mkdir -p "$home/.local/bin"
  printf '#!/bin/sh\necho lighten 0.0.1\n' > "$home/$bin"
  chmod 755 "$home/$bin"
  check install
  check said "replacing lighten 0.0.1"
  check said "Updated lighten from 0.0.1 to $VERSION."
  check installed
}

pinned() {
  check install --version "v$VERSION"
  check said "/download/v$VERSION/lighten-$VERSION-"
  check installed
}

unpublished() {
  check fails install --version 9.9.9
  check said "Check that 9.9.9 is a published release"
  check [ ! -e "$home/$bin" ]
}

mismatch() {
  tree=$BASE/bad
  check fails install
  check said "the download does not match SHA256SUMS. Nothing was installed."
  check [ ! -e "$home/$bin" ]
  check no_leftovers
}

mismatch_keeps_installed() {
  check install
  before=$(sha256sum < "$home/$bin")
  tree=$BASE/bad
  check fails install
  check [ "$(sha256sum < "$home/$bin")" = "$before" ]
  check no_leftovers
}

path_yes() {
  check interactive y
  check said "Added it to ~/.bashrc."
  check [ "$(count "# Added by the Lighten installer" "$home/.bashrc")" = 1 ]
  check [ "$(count "$line" "$home/.bashrc")" = 1 ]
  check installed
}

path_yes_rerun() {
  check interactive y
  check interactive none
  check said "~/.bashrc already has the line"
  check [ "$(count "$line" "$home/.bashrc")" = 1 ]
}

path_no() {
  check interactive n
  check said "Left ~/.bashrc unchanged."
  check [ ! -e "$home/.bashrc" ]
  check installed
}

path_zsh() {
  shell=/bin/zsh
  check interactive yes
  check [ "$(count "$line" "$home/.zshrc")" = 1 ]
}

path_fish() {
  shell=/usr/bin/fish
  check interactive Y
  check [ "$(count 'set -gx PATH "$HOME/.local/bin" $PATH' "$home/.config/fish/config.fish")" = 1 ]
}

no_modify_path() {
  check interactive none --no-modify-path
  check said "  $line"
  check [ ! -e "$home/.bashrc" ]
}

on_path() {
  PATH=$home/.local/bin:$PATH
  check interactive none
  check said "Run lighten to start."
}

unwritable() {
  check fails install --dir /proc/lighten
  check said "you cannot write to /proc/lighten."
  check said "This script does not use sudo."
}

no_downloader() {
  check fails install
  check said "this script needs curl or wget"
}

musl_stops() {
  check fails install
  check said "apk add gcompat"
  check [ ! -e "$home/.local/bin" ]
}

musl_force_without_gcompat() {
  check fails install --force
  check said "the downloaded lighten does not run on this system. Nothing was installed."
  check [ ! -e "$home/$bin" ]
  check no_leftovers
}

musl_force_with_gcompat() {
  check install --force
  check installed
}

# The image's own tools decide which download paths run: Ubuntu has neither curl nor wget, so it
# checks that stop and then installs wget alone; Alpine has only busybox wget.
start_tools=$(for tool in curl wget; do have $tool && printf '%s ' $tool; done)
packages=expect
musl_arm=no
[ "$(uname -m)" = aarch64 ] && ls /lib/ld-musl-* > /dev/null 2>&1 && musl_arm=yes

# A subshell, since os-release sets VERSION too.
echo "$(. /etc/os-release && echo "$PRETTY_NAME"), $(uname -m), downloaders: ${start_tools:-none}"
[ -n "$start_tools" ] || scenario "no curl or wget: stops and says so" no_downloader

if have apt-get; then
  apt-get update -qq > /dev/null
  [ -n "$start_tools" ] || packages="$packages wget"
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends $packages > /dev/null
elif have dnf; then
  dnf install -y -q $packages > /dev/null
elif have yum; then
  yum install -y -q $packages > /dev/null
elif have apk; then
  apk add -q $packages
fi
have expect || { echo "FAIL could not install expect"; exit 1; }
echo "downloader used: $(have curl && echo curl || echo wget)"

if [ $musl_arm = yes ]; then
  scenario "musl arm64: stops and explains gcompat" musl_stops
  scenario "musl arm64, --force without gcompat: installs nothing" musl_force_without_gcompat
  apk add -q gcompat
  scenario "musl arm64, --force with gcompat: installs" musl_force_with_gcompat
else
  scenario "fresh install, no terminal: installs, prints the PATH line, edits nothing" fresh
  scenario "rerun: reinstalls in place" rerun
  scenario "update from an older lighten" update
  scenario "--version v<version>: installs that release" pinned
  scenario "--version of an unpublished release: stops" unpublished
  scenario "checksum mismatch: stops, installs nothing" mismatch
  scenario "checksum mismatch over an install: keeps it" mismatch_keeps_installed
  scenario "PATH question, yes: adds the line once" path_yes show
  scenario "PATH question, yes, then rerun: does not ask or duplicate" path_yes_rerun
  scenario "PATH question, no: changes nothing" path_no
  scenario "PATH question, zsh: ~/.zshrc" path_zsh
  scenario "PATH question, fish: config.fish" path_fish
  scenario "--no-modify-path on a terminal: does not ask" no_modify_path
  scenario "directory already on PATH: does not ask" on_path
  scenario "directory not writable: stops without sudo" unwritable
fi

exit $((failures > 0))
