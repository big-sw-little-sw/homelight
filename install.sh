#!/bin/sh
# Installs or updates Lighten from its GitHub Releases.
#
#   curl -fsSL https://github.com/big-sw-little-sw/lighten/releases/latest/download/install.sh | sh
#   sh install.sh [--version <version>] [--dir <dir>] [--no-modify-path] [--force]
#
# Downloads the binary for this machine to a temporary directory, checks it against the release's
# SHA256SUMS, and only then installs it as <dir>/lighten (default ~/.local/bin): it copies it
# beside the target and renames, so a rerun replaces an installed lighten in place. It never calls
# sudo, and asks before it adds <dir> to PATH in shell startup files in the user's home.
# Asset names are a contract: see "Release assets" in docs/decisions.md.
#
# For testing only: LIGHTEN_INSTALL_BASE_URL replaces
# https://github.com/big-sw-little-sw/lighten/releases. The server must serve the assets at
# latest/download/<asset> and download/v<version>/<asset> under it, as GitHub does.
#
# Everything runs from main, called on the last line, so a download cut short runs nothing.
set -eu

releases_url=https://github.com/big-sw-little-sw/lighten/releases
marker='# Added by the Lighten installer'

usage() {
  cat <<'EOF'
Installs or updates lighten, the Lighten command.

Usage: install.sh [options]

  --version <version>  Install this release, such as 1.2.3. Default: the latest release.
  --dir <dir>          Install lighten into this directory. Default: ~/.local/bin.
  --no-modify-path     Do not offer to add the directory to PATH; only show how.
  --force              Install the arm64 binary on a musl system without gcompat.
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

# Removes what this run left behind: the download, a staged copy, and directories it created
# for an install that did not finish. Anything that existed before is kept.
cleanup() {
  [ -z "$download_dir" ] || rm -rf "$download_dir"
  [ -z "$staged" ] || rm -f "$staged"
  if [ -n "$created" ] && [ $installed = no ]; then
    d=$dir
    while rmdir "$d" 2> /dev/null && [ "$d" != "$created" ]; do d=$(dirname "$d"); done
  fi
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
      *) usage >&2; die "Unknown option: $1" ;;
    esac
    shift
  done
  version=${version#v}

  # Platform. Asset names use what uname -m prints.
  [ "$(uname -s)" = Linux ] || die "Lighten runs on Linux only, and this system is $(uname -s)."
  machine=$(uname -m)
  platform="Linux $machine"
  case $machine in
    x86_64 | amd64) suffix=linux-x86_64-musl ;;
    aarch64 | arm64)
      suffix=linux-aarch64-gnu
      # gcompat provides the glibc loader that the arm64 binary asks for.
      if is_musl; then
        if [ -e /lib/ld-linux-aarch64.so.1 ]; then
          platform="$platform (musl, with gcompat)"
        elif [ $force = no ]; then
          die "This system uses musl libc (Alpine Linux, for example)." \
            "Lighten for arm64 needs glibc. On musl it runs with the gcompat package:" \
            "" "  apk add gcompat" "" \
            "Then rerun this script."
        fi
      fi
      ;;
    *) die "There is no Lighten binary for $machine. Releases have x86_64 and arm64 (aarch64) binaries." ;;
  esac

  # Tools.
  if command -v curl > /dev/null 2>&1; then
    downloader=curl
  elif command -v wget > /dev/null 2>&1; then
    downloader=wget
  else
    die "This script needs curl or wget to download Lighten. Install one of them and rerun it."
  fi
  if command -v sha256sum > /dev/null 2>&1; then
    hasher=sha256sum
  elif command -v shasum > /dev/null 2>&1; then
    hasher="shasum -a 256"
  else
    die "This script needs sha256sum or shasum to check the download. Install one of them and rerun it."
  fi

  # Directory. Checked now, created only after a good download. Never sudo: a directory the
  # user cannot write to stops the install.
  dir=${dir:-${HOME:?HOME is not set}/.local/bin}
  case $dir in
    /*) ;;
    *) dir=$PWD/$dir ;;
  esac
  while [ "$dir" != / ] && [ "${dir%/}" != "$dir" ]; do dir=${dir%/}; done
  [ ! -e "$dir" ] || [ -d "$dir" ] || die "$dir exists and is not a directory."
  existing=$dir
  while [ ! -d "$existing" ]; do existing=$(dirname "$existing"); done
  if [ ! -w "$existing" ]; then
    die "You can't write to $existing." \
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
      die "Could not download $url/SHA256SUMS." "Check that $version is a published release: $releases_url"
    else
      die "Could not download $url/SHA256SUMS." "Check your network connection, or see $releases_url"
    fi
  line=$(printf '%s\n' "$sums" | awk -v suffix="-$suffix" '{
    name = $2; sub(/^\*/, "", name)
    if (name ~ /^lighten-/ && substr(name, length(name) - length(suffix) + 1) == suffix) { print $1, name; exit }
  }')
  [ -n "$line" ] || die "The release at $url has no $suffix binary."
  expected=${line%% *}
  asset=${line#* }
  release=${asset#lighten-}
  release=${release%-"$suffix"}
  if [ -n "$version" ] && [ "$release" != "$version" ]; then
    die "The release v$version lists $asset, not version $version."
  fi

  current=""
  if [ -x "$target" ]; then
    current=$("$target" --version 2> /dev/null | head -n 1) || current=""
    current=${current#lighten }
  fi

  target_shown=$(tilde "$target")
  say "Installing Lighten $release for $platform."
  say "  From: $url/$asset"
  if [ -n "$current" ]; then
    say "  To:   $target_shown (replacing lighten $current)"
  else
    say "  To:   $target_shown"
  fi

  download_dir=""
  staged=""
  created=""
  installed=no
  trap cleanup EXIT
  trap 'exit 1' HUP INT TERM
  download_dir=$(mktemp -d)
  download=$download_dir/$asset
  fetch "$url/$asset" "$download" || die "Could not download $url/$asset. Nothing was installed."

  actual=$($hasher "$download" | cut -d ' ' -f 1)
  if [ "$actual" != "$expected" ]; then
    die "The download does not match SHA256SUMS. Nothing was installed." \
      "  Expected: $expected" "  Got:      $actual" \
      "The download may be corrupt. Try again, and report it if it happens again: $releases_url"
  fi
  say "Checked the download against SHA256SUMS."

  # Stage beside the target, so the final rename is atomic on the same filesystem. The staged
  # copy runs from there rather than the temporary directory, which may be mounted noexec.
  [ -d "$dir" ] || created=$(d=$dir; while [ ! -d "$(dirname "$d")" ]; do d=$(dirname "$d"); done; echo "$d")
  mkdir -p "$dir" || die "Could not create $dir. Nothing was installed."
  staged=$dir/.lighten-install.$$
  cp "$download" "$staged"
  chmod 755 "$staged"
  ran=$("$staged" --version 2>&1) ||
    die "The downloaded lighten does not run on this system. Nothing was installed." "$ran"
  [ "$ran" = "lighten $release" ] ||
    die "The downloaded lighten reports '$ran', not 'lighten $release'. Nothing was installed."

  mv -f "$staged" "$target"
  installed=yes
  if [ -z "$current" ]; then
    say "Installed lighten $release."
  elif [ "$current" = "$release" ]; then
    say "Reinstalled lighten $release."
  else
    say "Updated lighten from $current to $release."
  fi

  offer_path
}

# Why a startup file cannot be changed, or nothing when it can. Only regular files in the
# user's home that the user owns and can write are changed; a missing one is created.
rc_problem() {
  case $1 in
    "$HOME"/*) ;;
    *) echo "it is outside your home directory"; return ;;
  esac
  if [ -e "$1" ] || [ -L "$1" ]; then
    [ -f "$1" ] || { echo "it is not a regular file"; return; }
    [ -O "$1" ] || { echo "it belongs to another user"; return; }
    [ -w "$1" ] || echo "it is read-only"
  else
    parent=$(dirname "$1")
    while [ ! -d "$parent" ]; do parent=$(dirname "$parent"); done
    [ -w "$parent" ] && [ -O "$parent" ] || echo "you can't create files in $(tilde "$parent")"
  fi
}

# "a", "a and b", "a, b and c"
join_and() {
  case $# in
    0) ;;
    1) printf '%s\n' "$1" ;;
    *)
      out=$1
      shift
      while [ $# -gt 1 ]; do out="$out, $1"; shift; done
      printf '%s and %s\n' "$out" "$1"
      ;;
  esac
}

# Explains how to put $dir on PATH, and with a terminal and the user's yes, adds the line once to
# each startup file the user's shell reads.
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
  line="export PATH=\"$path_expr:\$PATH\""
  shell_name=${SHELL:-sh}
  # bash reads ~/.bashrc in interactive shells, and at login ~/.bash_profile, or ~/.profile
  # when there is none.
  case ${shell_name##*/} in
    bash)
      if [ -e "$HOME/.bash_profile" ]; then
        set -- "$HOME/.bashrc" "$HOME/.bash_profile"
      else
        set -- "$HOME/.bashrc" "$HOME/.profile"
      fi
      ;;
    zsh) set -- "${ZDOTDIR:-$HOME}/.zshrc" ;;
    fish)
      set -- "${XDG_CONFIG_HOME:-$HOME/.config}/fish/conf.d/lighten.fish"
      line="set -gx PATH \"$path_expr\" \$PATH"
      ;;
    *) set -- "$HOME/.profile" ;;
  esac

  say "" "$(tilde "$dir") is not on your PATH, so your shell does not find lighten yet." \
    "This line adds it:" "" "  $line" ""

  # Sorts the files: those that have the line, those that cannot be changed, and the rest,
  # which are kept as newline-separated paths and shown names.
  todo=""
  todo_shown=""
  had=no
  for rc; do
    shown=$(tilde "$rc")
    problem=$(rc_problem "$rc")
    if [ -f "$rc" ] && grep -qxF "$line" "$rc" 2> /dev/null; then
      say "$shown already has it."
      had=yes
    elif [ -n "$problem" ]; then
      say "Not changing $shown: $problem. Add the line there yourself if your shell reads it."
    else
      todo="$todo$rc
"
      todo_shown="$todo_shown$shown
"
    fi
  done
  old_ifs=$IFS
  IFS='
'
  # shellcheck disable=SC2086 # split on newlines only
  set -- $todo_shown
  IFS=$old_ifs
  files=$(join_and "$@")

  if [ -z "$todo" ]; then
    if [ $had = yes ]; then
      say "Open a new terminal, or run the line above in this one."
    else
      say "Run lighten as $target_shown until you add the line."
    fi
    return
  fi
  if [ $modify_path = no ]; then
    say "Add it to $files yourself, or run lighten as $target_shown."
    return
  fi
  if ! has_tty; then
    say "Not changing $files without a terminal to ask in. Add the line yourself, or run lighten as $target_shown."
    return
  fi

  # Reads from the terminal, since stdin is the script itself under curl | sh.
  printf 'Add it to %s now? [y/N] ' "$files"
  answer=""
  read -r answer < /dev/tty || answer=""
  case $answer in
    [yY] | [yY][eE][sS]) ;;
    *)
      say "Left $files unchanged. Add the line yourself, or run lighten as $target_shown."
      return
      ;;
  esac
  old_ifs=$IFS
  IFS='
'
  for rc in $todo; do
    IFS=$old_ifs
    mkdir -p "$(dirname "$rc")"
    printf '\n%s\n%s\n' "$marker" "$line" >> "$rc"
  done
  IFS=$old_ifs
  say "Added it to $files. Open a new terminal, or run this in the current one:" "" "  $line"
}

main "$@"
