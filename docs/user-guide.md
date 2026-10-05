# HomeLight user guide

## What HomeLight does

HomeLight frees space in your home directory. It moves large directories, such
as caches, to other storage and leaves a link at the old path, so programs still
find them. It shows its plan first and changes nothing on disk until you press
`y`.

## How to use it

Press `?` on any screen to see its keys.

### Move directories to storage

1. Run `homelight`. The first time, there is no configuration file yet: press
   `i` to open **Configuration**. Say where storage is and which directories to
   move, then save. Saving writes the configuration file and nothing else.
2. The **Workspace** (`1`) lists each directory to move and what HomeLight plans
   for it. A directory marked `[Choose]` needs your choice: press `Tab`, pick a
   choice with `↑`/`↓`, and press `Enter`. You can also leave the plan as it is.
3. Press `a` to open **Review** (`2`). It lists every step HomeLight will take.
   Nothing has changed yet: `y` applies the plan, `n` goes back.
4. **Results** (`2`) show what each step did. Press `r` to check again.

To change the configuration later, edit `~/.homelight.json` (or the file you
gave with `--config`), then press `r`.

## Words to know

**Relocation.** One directory HomeLight manages. Its **source** is where
programs look for it, in your home directory. Its **target** is where its
contents live, in storage. After a move, the source is a link to the target.

**Rule or one-time choice.** A rule is saved in the configuration file and
decides every time. A one-time choice decides one relocation for the next apply
only; checking again or applying forgets it.

**Archive or delete.** Archive moves the source's contents into an archive
folder, so you can move them back. Delete removes them for good.

**Check again.** HomeLight looks at the disk again and makes a new plan. Do it
after you change files or the configuration outside HomeLight.

**Undo.** There is no undo command. Nothing changes before you press `y`. After
that, you can reverse a change by hand:

- Moved or linked: remove the link, then move the target back. For example:
  `rm ~/.cache/uv` (removes only the link), then
  `mv /local/home/me/.cache/uv ~/.cache/uv`.
- Archived: remove the link, then move the archive back, for example
  `mv ~/.cache/.homelight-archive/uv ~/.cache/uv`. The target keeps its own
  contents.
- Deleted: HomeLight cannot recover it. Restore it from a backup.

Remove the relocation from the configuration file first, or HomeLight plans to
move it again.

## What each rule does on disk

HomeLight looks at the source and the target, then:

- **Only the source exists:** it copies the source to the target, checks the
  copy, then replaces the source with a link. The Workspace marks it `[Move]`.
- **Neither exists:** it creates an empty target and links the source to it
  (`[Link]`).
- **The source already links to the target:** nothing (`[In sync]`).
- **Only the target exists:** the **Only target** rule decides.
- **Both exist:** the **Both exist** rule decides.

### When only the target exists

The configuration value is `when-only-target-exists`.

- **Ask each time** (`prompt`): nothing until you choose (`[Choose]`).
- **Keep target, link source** (`adopt-target`): links the source to the
  existing target (`[Link]`).

### When source &amp; target both exist

The configuration values are `when-source-and-target-directories-exist` and,
for what happens to the source when the target is kept, `when-adopting-target`.

- **Ask each time** (`prompt`): nothing until you choose (`[Choose]`).
- **Keep target, ask about source** (`adopt`, `prompt`): nothing until you
  choose to delete or archive the source.
- **Keep target, delete source** (`adopt`, `discard-source`): deletes the
  source and replaces it with a link to the target (`[Keep target]`). The
  source's contents are gone for good.
- **Keep target, archive source** (`adopt`, `archive-source`): moves the source
  into the archive folder, then links it to the target (`[Archive]`). The
  archive folder is `.homelight-archive` beside the source unless
  `archive-root` says otherwise, so `~/.cache/uv` goes to
  `~/.cache/.homelight-archive/uv`.
- **Leave both as they are** (`leave-unchanged`): nothing (`[Left as is]`).
- **Delete both, start empty** (`discard`): deletes both, creates an empty
  target and links the source to it (`[Delete]`). Both contents are gone for
  good.

A one-time choice offers the same outcomes, for one relocation and the next
apply only.

## Scripting

Three commands print one line of JSON on stdout and never prompt:

- `homelight status --json`: the state of each configured relocation, as `{"schema": 1, "configured": true, "configPath": "...", "relocations": [...]}`. Without a configuration file at the default path, `configured` is false and `relocations` is empty.
- `homelight plan --json`: the actions that would converge each relocation, with conflicts and diagnostics. It changes nothing.
- `homelight apply --json --yes`: plans and, if nothing is blocked or in conflict, applies that plan and prints the result of each action. If the plan is blocked or has a conflict, it prints the plan instead and changes nothing.

`--yes` is required with `apply --json`; it confirms the plan the command computes. Without `--json`, `apply` opens the TUI review whether or not `--yes` is given.

Each response is a JSON object whose first property is `"schema": 1`. The number changes when a change could break a script that reads the current shape.

Exit codes:

- `0`: success. `plan --json` exits 0 even when the plan has conflicts or blocked actions; read `conflicts` and `blocked`.
- `1`: a configuration error (one line on stderr, nothing on stdout), or `apply` was blocked, met a conflict or had a failed action (JSON on stdout).
- `2`: a usage error, such as an unknown option or `apply --json` without `--yes` (message on stderr).
- `70`: an internal error, a bug in HomeLight (one line on stderr).

## More help

- This guide online: https://github.com/big-sw-little-sw/homelight/blob/main/docs/user-guide.md
- `homelight --help` lists the commands and options.
- `homelight guide` prints this guide, for example to read with
  `homelight guide | less`.
