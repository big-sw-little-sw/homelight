#!/bin/sh
# Runs inside a container that test.sh starts. Do not run it directly.
# test.sh mounts this directory at /ci and install.sh at /install.sh, both read-only.
# Environment: BASE, the asset server with the good/, bad/, missing/ and broken/ trees that
# test.sh describes, and VERSION, the version it serves.
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

# Runs a command as uid 12345, a user with no passwd entry, for the non-root scenarios.
other="setpriv --reuid=12345 --regid=12345 --clear-groups"

# install [options]: runs the script without a terminal, as CI or a pipe to a log would.
install() {
  ${runas:-} env HOME="$home" SHELL=/bin/bash LIGHTEN_INSTALL_BASE_URL="${tree:-$BASE/good}" \
    sh /install.sh "$@" < /dev/null
}

# interactive <answer> [options]: runs it on a terminal as curl | sh does; see answer.exp.
interactive() {
  ${runas:-} env HOME="$home" SHELL="${shell:-/bin/bash}" LIGHTEN_INSTALL_BASE_URL="$BASE/good" \
    expect /ci/answer.exp "$@"
}

said() {
  grep -qF -- "$1" "$out"
}

installed() {
  [ "$("$home/$bin" --version)" = "lighten $VERSION" ]
}

# No staged copy beside the target and no download left in /tmp.
no_leftovers() {
  [ -z "$(find "$home/.local/bin" -name '.lighten-install.*' 2> /dev/null)" ] &&
    [ -z "$(find /tmp -name 'lighten-*-linux-*' 2> /dev/null)" ]
}

count() {
  grep -cxF -- "$1" "$2"
}

fresh() {
  check install
  check installed
  check said "  $line"
  check said "Not changing ~/.bashrc and ~/.profile without a terminal"
  check [ ! -e "$home/.bashrc" ]
  check [ ! -e "$home/.profile" ]
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
  check [ ! -e "$home/.local" ]
}

missing_binary() {
  tree=$BASE/missing
  check fails install
  check said "Error: Could not download"
  check [ ! -e "$home/.local" ]
  check no_leftovers
}

mismatch() {
  tree=$BASE/bad
  check fails install
  check said "Error: The download does not match SHA256SUMS. Nothing was installed."
  check [ ! -e "$home/.local" ]
  check no_leftovers
}

# The run check refuses the binary after the install directory was created: the script removes
# the directory it created and keeps the one that was there.
does_not_run() {
  mkdir "$home/.local"
  tree=$BASE/broken
  check fails install
  check said "Error: The downloaded lighten does not run on this system. Nothing was installed."
  check [ -d "$home/.local" ]
  check [ ! -e "$home/.local/bin" ]
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
  check said "Add it to ~/.bashrc and ~/.profile now? [y/N]"
  check said "Added it to ~/.bashrc and ~/.profile."
  for rc in .bashrc .profile; do
    check [ "$(count "# Added by the Lighten installer" "$home/$rc")" = 1 ]
    check [ "$(count "$line" "$home/$rc")" = 1 ]
  done
  check [ ! -e "$home/.bash_profile" ]
  check installed
}

path_bash_profile() {
  : > "$home/.bash_profile"
  check interactive y
  check said "Added it to ~/.bashrc and ~/.bash_profile."
  check [ "$(count "$line" "$home/.bashrc")" = 1 ]
  check [ "$(count "$line" "$home/.bash_profile")" = 1 ]
  check [ ! -e "$home/.profile" ]
}

path_yes_rerun() {
  check interactive y
  check interactive none
  check said "~/.bashrc already has it."
  check said "~/.profile already has it."
  check [ "$(count "$line" "$home/.bashrc")" = 1 ]
  check [ "$(count "$line" "$home/.profile")" = 1 ]
}

path_no() {
  check interactive n
  check said "Left ~/.bashrc and ~/.profile unchanged."
  check [ ! -e "$home/.bashrc" ]
  check [ ! -e "$home/.profile" ]
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
  check [ "$(count 'set -gx PATH "$HOME/.local/bin" $PATH' "$home/.config/fish/conf.d/lighten.fish")" = 1 ]
}

# A user whose ~/.bashrc belongs to root: that file is skipped with the reason, ~/.profile is not.
rc_owned_by_root() {
  chown -R 12345:12345 "$home"
  : > "$home/.bashrc"
  runas=$other
  check interactive y
  check said "Not changing ~/.bashrc: it belongs to another user. Add the line there yourself"
  check said "Add it to ~/.profile now? [y/N]"
  check [ ! -s "$home/.bashrc" ]
  check [ "$(count "$line" "$home/.profile")" = 1 ]
  check installed
}

no_modify_path() {
  check interactive none --no-modify-path
  check said "Add it to ~/.bashrc and ~/.profile yourself"
  check [ ! -e "$home/.bashrc" ]
}

on_path() {
  PATH=$home/.local/bin:$PATH
  check interactive none
  check said "Run lighten to start."
}

unwritable() {
  chown -R 12345:12345 "$home"
  runas=$other
  check fails install --dir /usr/local/bin
  check said "Error: You can't write to /usr/local/bin."
  check said "This script does not use sudo."
  check [ ! -e "$home/.local" ]
}

no_downloader() {
  check fails install
  check said "Error: This script needs curl or wget"
}

musl_stops() {
  check fails install
  check said "apk add gcompat"
  check [ ! -e "$home/.local" ]
}

musl_with_gcompat() {
  check install
  check said "for Linux aarch64 (musl, with gcompat)."
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
  dnf install -y -q $packages util-linux > /dev/null
elif have yum; then
  yum install -y -q $packages > /dev/null
elif have apk; then
  apk add -q $packages setpriv
fi
for tool in expect setpriv; do have $tool || { echo "FAIL could not install $tool"; exit 1; }; done
echo "downloader used: $(have curl && echo curl || echo wget)"

if [ $musl_arm = yes ]; then
  scenario "musl arm64 without gcompat: stops and explains gcompat" musl_stops
  apk add -q gcompat
  scenario "musl arm64 with gcompat: installs" musl_with_gcompat
else
  scenario "fresh install, no terminal: installs, prints the PATH line, edits nothing" fresh
  scenario "rerun: reinstalls in place" rerun
  scenario "update from an older lighten" update
  scenario "--version v<version>: installs that release" pinned
  scenario "--version of an unpublished release: stops, creates no directory" unpublished
  scenario "binary download fails: stops, creates no directory" missing_binary
  scenario "checksum mismatch: stops, installs nothing, creates no directory" mismatch
  scenario "checksum mismatch over an install: keeps it" mismatch_keeps_installed
  scenario "binary does not run: removes the directory it created, keeps ~/.local" does_not_run
  scenario "PATH question, bash, yes: ~/.bashrc and ~/.profile, once" path_yes show
  scenario "PATH question, bash with ~/.bash_profile: ~/.bashrc and ~/.bash_profile" path_bash_profile
  scenario "PATH question, yes, then rerun: does not ask or duplicate" path_yes_rerun
  scenario "PATH question, no: changes nothing" path_no
  scenario "PATH question, zsh: ~/.zshrc" path_zsh
  scenario "PATH question, fish: conf.d/lighten.fish" path_fish
  scenario "~/.bashrc owned by root, non-root user: skips it, says why, changes ~/.profile" rc_owned_by_root
  scenario "--no-modify-path on a terminal: does not ask" no_modify_path
  scenario "directory already on PATH: does not ask" on_path
  scenario "non-root user, --dir /usr/local/bin: stops without sudo" unwritable
fi

exit $((failures > 0))
