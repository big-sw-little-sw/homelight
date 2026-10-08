#!/bin/sh
# Installs or updates Lighten from its GitHub Releases.
#
#   curl -fsSL https://github.com/big-sw-little-sw/lighten/releases/latest/download/install.sh | sh
#   sh install.sh [--version <version>] [--dir <dir>] [--no-modify-path] [--force]
#
# Downloads the binary for this machine, checks it against the release's SHA256SUMS and installs
# it as <dir>/lighten (default ~/.local/bin). It writes beside the target and renames, so a rerun
# replaces an installed lighten in place. It never calls sudo, and asks before it adds <dir> to
# PATH in a shell startup file. Asset names are a contract: see "Release assets" in
# docs/decisions.md.
#
# For testing only: LIGHTEN_INSTALL_BASE_URL replaces
# https://github.com/big-sw-little-sw/lighten/releases. The server must serve the assets at
# latest/download/<asset> and download/v<version>/<asset> under it, as GitHub does.
#
# Everything runs from main, called on the last line, so a download cut short runs nothing.
set -eu

releases_url=https://github.com/big-sw-little-sw/lighten/releases

usage() {
  cat <<'EOF'
Installs or updates lighten, the Lighten command.

Usage: install.sh [options]

  --version <version>  Install this release, such as 1.2.3. Default: the latest release.
  --dir <dir>          Install lighten into this directory. Default: ~/.local/bin.
  --no-modify-path     Do not offer to add the directory to PATH; only show how.
  --force              Install the arm64 binary on a musl system such as Alpine.
  -h, --help           Show this help.

Running it again updates lighten in place.
EOF
}

say() {
  printf '%s\n' "$@"
}

# Prints "Error: <first line>" and any further lines, then stops.
die() {
  printf 'Error: %s\n' "$1" >&2
  shift
  [ $# -eq 0 ] || printf '%s\n' "$@" >&2
  exit 1
}

# Writes a URL's content to a file, or to stdout for "-".
fetch() {
  case $downloader in
    curl) curl -fsSL --retry 2 -o "$2" "$1" ;;
    wget) wget -q -O "$2" "$1" ;;
  esac
}

is_musl() {
  for loader in /lib/ld-musl-*.so.1; do
    [ -e "$loader" ] && return 0
  done
  return 1
}

# Opening /dev/tty fails without a controlling terminal, for example under CI or cron.
has_tty() {
  (: < /dev/tty) 2> /dev/null
}

# Shows a path under $HOME as ~/...
tilde() {
  # shellcheck disable=SC2088 # a literal ~ for display
  case $1 in
    "$HOME"/*) printf '~/%s\n' "${1#"$HOME"/}" ;;
    *) printf '%s\n' "$1" ;;
  esac
}

main() {
  version=""
  dir=""
  modify_path=yes
  force=no
  while [ $# -gt 0 ]; do
    case $1 in
      --version) [ $# -ge 2 ] || die "--version needs a value, such as 1.2.3."; version=$2; shift ;;
      --version=*) version=${1#*=} ;;
      --dir) [ $# -ge 2 ] || die "--dir needs a directory."; dir=$2; shift ;;
      --dir=*) dir=${1#*=} ;;
      --no-modify-path) modify_path=no ;;
      --force) force=yes ;;
      -h | --help) usage; exit 0 ;;
      *) usage >&2; die "unknown option: $1" ;;
    esac
    shift
  done
  version=${version#v}

  # Platform. Asset names use what uname -m prints.
  [ "$(uname -s)" = Linux ] || die "Lighten runs on Linux only, and this system is $(uname -s)."
  machine=$(uname -m)
  case $machine in
    x86_64 | amd64) suffix=linux-x86_64-musl ;;
    aarch64 | arm64)
      suffix=linux-aarch64-gnu
      if is_musl && [ $force = no ]; then
        die "this system uses musl libc (Alpine Linux, for example)." \
          "Lighten for arm64 needs glibc. On musl it runs only with the gcompat package:" \
          "" "  apk add gcompat" "" \
          "Then rerun this script with --force."
      fi
      ;;
    *) die "there is no Lighten binary for $machine. Releases have x86_64 and arm64 (aarch64) binaries." ;;
  esac

  # Tools.
  if command -v curl > /dev/null 2>&1; then
    downloader=curl
  elif command -v wget > /dev/null 2>&1; then
    downloader=wget
  else
    die "this script needs curl or wget to download Lighten. Install one of them and rerun it."
  fi
  if command -v sha256sum > /dev/null 2>&1; then
    hasher=sha256sum
  elif command -v shasum > /dev/null 2>&1; then
    hasher="shasum -a 256"
  else
    die "this script needs sha256sum or shasum to check the download. Install one of them and rerun it."
  fi

  # Directory. Never sudo: a directory the user cannot write to stops the install.
  dir=${dir:-${HOME:?HOME is not set}/.local/bin}
  case $dir in
    /*) ;;
    *) dir=$PWD/$dir ;;
  esac
  while [ "$dir" != / ] && [ "${dir%/}" != "$dir" ]; do dir=${dir%/}; done
  if ! { mkdir -p "$dir" 2> /dev/null && [ -w "$dir" ]; }; then
    die "you cannot write to $dir." \
      "This script does not use sudo. Choose a directory you can write to with --dir," \
      "or, if you mean to install for all users, run the script as root yourself, for example:" \
      "" "  sudo sh install.sh --dir $dir"
  fi
  target=$dir/lighten

  # Release. SHA256SUMS names each binary with its version, so it also tells which version
  # "latest" is, without the GitHub API.
  base=${LIGHTEN_INSTALL_BASE_URL:-$releases_url}
  if [ -n "$version" ]; then
    url=$base/download/v$version
  else
    url=$base/latest/download
  fi
  sums=$(fetch "$url/SHA256SUMS" -) ||
    if [ -n "$version" ]; then
      die "could not download $url/SHA256SUMS." "Check that $version is a published release: $releases_url"
    else
      die "could not download $url/SHA256SUMS." "Check your network connection, or see $releases_url"
    fi
  line=$(printf '%s\n' "$sums" | awk -v suffix="-$suffix" '{
    name = $2; sub(/^\*/, "", name)
    if (name ~ /^lighten-/ && substr(name, length(name) - length(suffix) + 1) == suffix) { print $1, name; exit }
  }')
  [ -n "$line" ] || die "the release at $url has no $suffix binary."
  expected=${line%% *}
  asset=${line#* }
  release=${asset#lighten-}
  release=${release%-"$suffix"}
  if [ -n "$version" ] && [ "$release" != "$version" ]; then
    die "the release v$version lists $asset, not version $version."
  fi

  current=""
  if [ -x "$target" ]; then
    current=$("$target" --version 2> /dev/null | head -n 1) || current=""
    current=${current#lighten }
  fi

  target_shown=$(tilde "$target")
  say "Installing Lighten $release for Linux $machine."
  say "  From: $url/$asset"
  if [ -n "$current" ]; then
    say "  To:   $target_shown (replacing lighten $current)"
  else
    say "  To:   $target_shown"
  fi

  # Download beside the target, so the final rename is atomic on the same filesystem.
  tmp=$dir/.lighten-install.$$
  trap 'rm -f "$tmp"' EXIT
  trap 'exit 1' HUP INT TERM
  fetch "$url/$asset" "$tmp" || die "could not download $url/$asset. Nothing was installed."

  actual=$($hasher "$tmp" | cut -d ' ' -f 1)
  if [ "$actual" != "$expected" ]; then
    die "the download does not match SHA256SUMS. Nothing was installed." \
      "  Expected: $expected" "  Got:      $actual" \
      "The download may be corrupt. Try again, and report it if it happens again: $releases_url"
  fi
  say "Checked the download against SHA256SUMS."

  chmod 755 "$tmp"
  ran=$("$tmp" --version 2>&1) ||
    die "the downloaded lighten does not run on this system. Nothing was installed." "$ran"
  [ "$ran" = "lighten $release" ] ||
    die "the downloaded lighten reports '$ran', not 'lighten $release'. Nothing was installed."

  mv -f "$tmp" "$target"
  if [ -z "$current" ]; then
    say "Installed lighten $release."
  elif [ "$current" = "$release" ]; then
    say "Reinstalled lighten $release."
  else
    say "Updated lighten from $current to $release."
  fi

  offer_path
}

# Explains how to put $dir on PATH, and with a terminal and the user's yes, adds the line once.
offer_path() {
  case ":${PATH-}:" in
    *":$dir:"*)
      found=$(command -v lighten 2> /dev/null || true)
      if [ -n "$found" ] && [ "$found" != "$target" ]; then
        say "" "Note: the command lighten runs $found, which comes before $(tilde "$dir") on PATH."
      else
        say "Run lighten to start."
      fi
      return
      ;;
  esac

  # Under $HOME, the line says $HOME so it survives a moved home directory.
  case $dir in
    "$HOME"/*) path_expr="\$HOME/${dir#"$HOME"/}" ;;
    *) path_expr=$dir ;;
  esac
  shell_name=${SHELL:-sh}
  case ${shell_name##*/} in
    bash) rc=$HOME/.bashrc; line="export PATH=\"$path_expr:\$PATH\"" ;;
    zsh) rc=${ZDOTDIR:-$HOME}/.zshrc; line="export PATH=\"$path_expr:\$PATH\"" ;;
    fish) rc=${XDG_CONFIG_HOME:-$HOME/.config}/fish/config.fish; line="set -gx PATH \"$path_expr\" \$PATH" ;;
    *) rc=$HOME/.profile; line="export PATH=\"$path_expr:\$PATH\"" ;;
  esac
  rc_shown=$(tilde "$rc")

  say "" "$(tilde "$dir") is not on your PATH, so your shell does not find lighten yet."
  if [ -f "$rc" ] && grep -qxF "$line" "$rc"; then
    say "$rc_shown already has the line that adds it. Open a new terminal, or run:" "" "  $line"
    return
  fi
  say "This line in $rc_shown adds it:" "" "  $line" ""

  if [ $modify_path = no ]; then
    say "Add it yourself, or run lighten as $target_shown."
    return
  fi
  if ! has_tty; then
    say "Not changing $rc_shown without a terminal to ask in. Add the line yourself, or run lighten as $target_shown."
    return
  fi

  # Reads from the terminal, since stdin is the script itself under curl | sh.
  printf 'Add it to %s now? [y/N] ' "$rc_shown"
  answer=""
  read -r answer < /dev/tty || answer=""
  case $answer in
    [yY] | [yY][eE][sS])
      mkdir -p "$(dirname "$rc")"
      printf '\n# Added by the Lighten installer\n%s\n' "$line" >> "$rc"
      say "Added it to $rc_shown. Open a new terminal, or run this in the current one:" "" "  $line"
      ;;
    *)
      say "Left $rc_shown unchanged. Add the line yourself, or run lighten as $target_shown."
      ;;
  esac
}

main "$@"
