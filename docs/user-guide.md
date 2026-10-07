# HomeLight

HomeLight frees space in your home directory. It moves large directories, such
as caches, to other storage and leaves a link at the old path, so programs
still find them.

It always shows you its plan first. Nothing on disk changes until you press
y.

## What it does

- **Moves directories to storage.** It copies a directory to storage, checks
  the copy, then replaces the original with a link.
- **Prepares directories that do not exist yet.** It creates them in storage
  and links to them, so they never fill your home directory.
- **Handles copies already in storage.** When storage already has the
  directory, a rule decides what happens, or HomeLight asks you.
- **Suggests what to move.** Browse lists directories that are usually safe
  and worth moving, such as tool caches.
- **Shows every step before it runs.** You review the whole plan and confirm
  it with `y`.
- **Checks again at any time.** Press `r` and HomeLight looks at the disk
  again and makes a new plan.
- **Works in scripts.** `status`, `plan` and `apply` can print JSON.

## How to use it

Press `?` on any screen to see what it is for and its keys. In a text field,
press `F1`.

### Free space on this machine

```
Configure → Workspace → Review → Apply → Results
                ↑                           │
                └────── r: check again ─────┘
```

1. **Configure.** Run `homelight`. The first time, there is no configuration
   file yet: press `i` to open **Configuration**. Type where storage is in
   **Target root**, then add the directories to move: press `a` and type
   one in, or press `b` to browse suggestions. Paths are full, such as
   `/data/me`, or start with `~/`. Press `Esc`, then `s` to save. Saving
   writes the configuration file and nothing else.
2. **Check the plan on the Workspace (`1`).** Each directory you added is a
   *relocation*. The Workspace shows what is there now and what HomeLight
   will do, such as `[Move]` or `[In sync]`. A relocation marked `[Choose]`
   needs your choice: select it, press `Tab`, pick a choice and press
   `Enter`. A relocation marked `[Blocked]` cannot be done as things are, for
   example because a file is where its archive folder should be. Its
   Details say what is in the way: fix that, then press `r`. If Details say
   you can, pick a choice below that doesn't need that folder instead.
3. **Review (`2`).** Press `a` to see every step HomeLight will take, listed
   under the relocation it belongs to. Select a relocation to see its decision
   and paths, or a step to see what it does. Nothing has changed yet. Press
   `y` to apply, or `n` to go back.
4. **Apply.** HomeLight makes the changes and shows each step as it runs.
   Leave it running until it finishes. If you press `q`, it finishes the
   changes first, then exits.
5. **Results (`2`).** Each step shows whether it worked. Press `r` to check
   again: the Workspace then shows each relocation as it is now, normally
   `[In sync]`. If a step finds something different from the plan, HomeLight
   stops there and the steps after it do not run. Select the failed step to
   see what it found, then press `r`.

### Change the configuration later

Press `e` on the Workspace to open Configuration, change it, and press `s`.
HomeLight checks again and shows the new plan.

- Select **Storage locations** or a relocation in the list on the left, and
  press `Enter` to change its fields. `Esc` goes back to the list. In a
  field every letter types, so press `Esc` before `s`.
- `a` adds a relocation and `d` removes the selected one. Nothing changes in
  the file until you press `s`.
- Saving asks first, because it replaces the whole file. Comments in the
  file are not kept.
- If the file changed after you opened Configuration, for example because you
  edited it by hand, HomeLight does not replace it. Your changes stay on
  screen: press `q`, then `y`, then `e` to start again from the file.

`homelight config` opens Configuration directly. The configuration file is
`~/.homelight.json`, or the file you gave with `--config`. You can also edit
it by hand, then press `r` in HomeLight to check again.

Run HomeLight again whenever you like, for example after you add a
directory. It changes only what is not in sync yet.

### If HomeLight can't read your configuration

If the file has a mistake, the Workspace says `HomeLight can't read`, the
file's name, and what is wrong, for example:

```
homelight.target-root: Use a full path, or one starting with ~/
```

- **To fix it:** open the file in a text editor, correct the line or
  setting it names, then press `r` to check again.
- **To start over:** rename or delete the file, then press `r`. HomeLight
  then offers `i` to create a new one.

Configuration can't open a file HomeLight can't read, so `e` is not offered
until the file is fixed. `homelight config` and the `--json` commands print
the same explanation and exit with code 1.

## Suggestion lists

Browse, inside Configuration, suggests directories to move. The suggestions
come from two suggestion lists:

- **The built-in list.** It comes with HomeLight and names directories that
  are usually large and safe to move: package caches and toolchains for
  Maven, Gradle, npm, pip, uv, pixi, Cargo, Go, VS Code and others.
- **Your list** (optional). A file you write, for example one on a shared
  drive that everyone on your team uses. Browse always shows the built-in
  list too.

Press `b` in Configuration's list to open Browse. Its first two lines are
the lists: the built-in list with its number of suggestions, then your list
with its location, its number of suggestions and the day its file last
changed. If your list could not be used, its line says why. Press `i` for
the full detail.

Below them, the suggestions are listed under their ecosystem, such as JVM,
Python or JavaScript, and under it the name of their app. Apps with no
ecosystem are under "Other tools", and directories with no app under
"Other directories", at the end. Each directory has a mark:

- `●` it is in your configuration, whether saved earlier or added now.
- `○` it is not. Press `Space` to add it.
- `−` it cannot be added, for example because it is a link. Its note says
  why.

`Space` on a `●` row takes it out again. A directory you take out stays in
the list until you leave Browse, so `Space` can add it back. Adding and
removing change only what Configuration shows: the file changes when you
press `s`. Press `e` on a `●` row to change its target or rules.

Each ecosystem's and app's name has a mark too: `●` all the directories
under it are added, `◐` some are, `○` none are, `−` none can be. Beside it,
Browse counts them, such as `1 of 2 added`. `Space` on the name adds all
the directories under it that are shown and can be added, so `Space` on
"Python" adds every Python tool's directories at once. On a `●` name it
takes them all out. If some could not be added, Browse says so, for example
`Added 3. Skipped 1 that overlaps ~/.cache.`

A list can mark a directory **usually not needed**. Browse hides a directory
when every list that names it says so, and counts what it hid. Press `u` to
show them. A directory already in your configuration is never hidden.

When both lists name the same directory, Browse shows it once, in your
list's group and with your list's advice. When both lists give the same app
different ecosystems, Browse uses your list's. Select a suggestion and press
`Enter` to see which lists suggest it and what each one says.

### Write your own list

A suggestion list is a JSON file. For example:

```json
{
  // Suggestions for HomeLight's Browse.
  "apps": [
    {
      "name": "Bazel",
      "ecosystem": "Build",
      "directories": [
        {"path": ".cache/bazel", "advice": "consider",
         "reason": "Build outputs, rebuilt when needed"}
      ]
    },
    {
      "name": "Docker",
      "directories": [
        {"path": ".docker/buildx", "advice": "usually-unnecessary",
         "reason": "Small on our machines"}
      ]
    }
  ],
  "directories": [
    {"path": "scratch", "reason": "Scratch data from the cluster"}
  ]
}
```

- `apps` holds groups. Each has a `name` and its `directories`. The
  top-level `directories` holds suggestions with no group; Browse shows them
  under "Other directories". A file needs at least one of the two.
- `ecosystem` is optional on an app: the heading Browse shows the app
  under, such as `Python`. An app with none is under "Other tools". To move
  a built-in app, such as Gradle, under another heading, name it in your
  list with the ecosystem you want and at least one of its directories.
- `path` is relative to your home directory: `.cache/bazel`, not
  `~/.cache/bazel` or `/home/me/.cache/bazel`. It cannot use `..`,
  variables such as `$USER`, or wildcards.
- `advice` is optional: `consider` or `usually-unnecessary`.
- `reason` is optional. Browse shows it with the suggestion.
- Comments (`//` and `/* */`) and trailing commas are allowed. Any other key
  is an error.
- The file can be up to 1 MiB and list up to 10,000 directories.

One error rejects the whole file. Browse then says so on your list's line
(press `i` for the detail) and uses the built-in list alone. HomeLight waits
at most 5 seconds for the file, so a slow or missing drive never blocks you.
Configuration saves either way.

To use a list, enter its path in Configuration's **Suggestion list** field,
or add it to the configuration file:

```json
"suggestion-list": "/net/team/homelight/suggestions.json"
```

The path must be full or start with `~/`. Each person keeps their own
configuration file; only the list is shared. After someone changes the list,
press `r` in Browse to check again.

## Words to know

**Relocation.** One directory HomeLight manages. Its **source** is where
programs look for it, in your home directory. Its **target** is where its
contents live, in storage. After a move, the source is a link to the target.

**In sync.** The source already links to the target. Nothing to do.

**Rule or one-time choice.** A rule is saved in the configuration file and
decides every time. A one-time choice decides one relocation for the next
apply only. Checking again or applying forgets it.

**Archive or delete.** Archive moves the source's contents into an archive
folder, so you can move them back. Delete removes them for good.

**Check again.** HomeLight looks at the disk again and makes a new plan. Do it
after you change files or the configuration outside HomeLight.

## Undo

There is no undo command. Nothing changes before you press `y`. After that,
you can reverse a change by hand:

- **Moved or linked:** remove the link, then move the target back. For
  example: `rm ~/.cache/uv` (removes only the link), then
  `mv /local/home/me/.cache/uv ~/.cache/uv`.
- **Archived:** remove the link, then move the archive back, for example
  `mv ~/.cache/.homelight-archive/uv ~/.cache/uv`. The target keeps its own
  contents.
- **Deleted:** HomeLight cannot recover it. Restore it from a backup.

Remove the relocation from the configuration first, or HomeLight plans to
move it again: press `e`, select it, press `d`, then `s`.

## Reference

### What each rule does on disk

HomeLight looks at the source and the target, then:

- **Only the source exists:** it copies the source to the target, checks the
  copy, then replaces the source with a link. The Workspace marks it `[Move]`.
- **Neither exists:** it creates an empty target and links the source to it
  (`[Link]`).
- **The source already links to the target:** nothing (`[In sync]`).
- **Only the target exists:** the **Only target** rule decides.
- **Both exist:** the **Both exist** rule decides.

#### When only the target exists

The configuration value is `when-only-target-exists`.

- **Ask each time** (`prompt`): nothing until you choose (`[Choose]`).
- **Keep target, link source** (`adopt-target`): links the source to the
  existing target (`[Link]`).

#### When source and target both exist

The configuration values are `when-source-and-target-directories-exist`
and, for what happens to the source when the target is kept,
`when-adopting-target`.

- **Ask each time** (`prompt`): nothing until you choose (`[Choose]`).
- **Keep target, ask about source** (`adopt`, `prompt`): nothing until you
  choose to delete or archive the source.
- **Keep target, delete source** (`adopt`, `discard-source`): deletes the
  source and replaces it with a link to the target (`[Keep target]`). The
  source's contents are gone for good.
- **Keep target, archive source** (`adopt`, `archive-source`): moves the
  source into the archive folder, then links it to the target (`[Archive]`).
  The archive folder is `.homelight-archive` beside the source unless
  `archive-root` says otherwise, so `~/.cache/uv` goes to
  `~/.cache/.homelight-archive/uv`.
- **Leave both as they are** (`leave-unchanged`): nothing (`[Left as is]`).
- **Delete both, start empty** (`discard`): deletes both, creates an empty
  target and links the source to it (`[Delete]`). Both contents are gone for
  good.

A one-time choice offers the same outcomes, for one relocation and the next
apply only.

### Scripting

Three commands never ask a question and print one line of JSON:

- `homelight status --json`: what is on disk now for each relocation. Without
  a configuration file at the default path, `configured` is `false` and
  `relocations` is empty.
- `homelight plan --json`: the steps HomeLight would take for each
  relocation, any choices still needed (`conflicts`) and any warnings. It
  changes nothing.
- `homelight apply --json --yes`: makes that plan and, if nothing is blocked
  and no choice is needed, applies it and prints how each step went. If
  something is blocked or needs a choice, it prints the plan instead and
  changes nothing.

`apply --json` needs `--yes`, which confirms the plan the command makes.
Without `--json`, `apply` opens the Review screen, with or without `--yes`.

Every response is a JSON object that starts with `"schema": 1`. The number
changes when the output changes in a way that could break a script.

Exit codes:

- `0`: it worked. `plan --json` exits 0 even when a choice is needed or a
  step is blocked; read `conflicts` and `blocked`.
- `1`: the configuration file has a problem (what is wrong and how to fix
  it on stderr, nothing on stdout), or `apply` was blocked, needed a choice
  or had a step fail (JSON on stdout).
- `2`: the command line is wrong, such as an unknown option or
  `apply --json` without `--yes` (a message on stderr).
- `70`: a bug in HomeLight (one line on stderr). Please report it.

### More help

- This guide online:
  https://github.com/big-sw-little-sw/homelight/blob/main/docs/user-guide.md
- `homelight --help` lists the commands and options.
- `homelight apply --help` shows the options of one command, here `apply`.
- `homelight guide` prints this guide, for example to read with
  `homelight guide | less`.
- To select and copy text while HomeLight runs, hold Shift as you drag
  (WezTerm, Ghostty) or Option (iTerm2). HomeLight uses the mouse wheel to
  scroll.
