# HomeLight user guide

HomeLight moves bulky directories out of your home directory to storage on this
machine and leaves a link at the old path, so programs still find them there.
This guide walks through one run, says what each rule does on disk, and how to
undo a change by hand.

## How it works

1. Setup: say where storage is.
2. Workspace: see what HomeLight found and what it plans for each directory.
3. Pick a choice for anything marked as needing one, or leave the plan as is.
4. Press a to review every change. Nothing changes until you press y.
5. Apply runs the changes and shows the results. Press r to check again.

Press `?` on any screen to see these steps, every key for that screen and the
key ideas below. `Esc` or `?` closes it. In a text field, `?` types a question
mark.

## 1. Setup

Run `homelight`. Without a configuration file the Workspace says so; press `i`
to create one.

- **Source root** is where the directories are now, usually your home directory.
- **Target root** is the storage they move to, for example `/local/home/me`.
- **Shared candidate list** is optional: a file of directories your team
  suggests.

`Enter` opens the relocations table. `a` adds a directory by hand, `b` browses
directories HomeLight knows about, `v` validates and `s` saves. Saving writes
`~/.homelight.json` and nothing else: no directory moves until you apply.

## 2. Workspace

The Workspace shows each relocation with what HomeLight plans for it:

```text
⌂ HOMELIGHT  [1: Workspace]  [Review unavailable]
Config: ~/.homelight.json
4 relocations · ⚡ 2 to change · ⚠ 1 needs a choice · ✖ 0 blocked
✔ 1 in sync · ─ 0 left as is
┌Relocations · c: show 1 in sync─────────────────────┐┌Details─────────────────────────────────────────────────────────┐
│❯ [Choose] ~/.cache/both                            ││Now: both ~/.cache/both and its target are directories.        █│
│  [Move] ~/.cache/tool-a                            ││Decision: ask each time (your configuration)                   █│
│  [Archive] ~/.cache/tool-b                         ││Will do: nothing until you choose.                             █│
│                                                    ││                                                               █│
│                                                    ││  (○) Keep target, delete source                               █│
│                                                    ││Keep the target's contents. Delete the source and replace it   █│
│                                                    ││with a link to the target.                                     █│
│                                                    ││                                                               █│
│                                                    ││  (○) Keep target, archive source                              █│
│                                                    ││Keep the target's contents. Move the source to                 █│
│                                                    ││~/.cache/.homelight-archive/both and replace it with a link to █│
│                                                    ││the target.                                                    █│
│                                                    ││                                                               █│
│                                                    ││  (○) Leave both as they are                                   █│
│                                                    ││Change nothing. Source and target stay as they are.            █│
│                                                    ││                                                               █│
│                                                    ││  (○) Delete both, start empty                                 █│
│                                                    ││Delete the contents of both, then create an empty target and   ││
│                                                    ││link the source to it.                                         ││
│                                                    ││                                                               ││
│                                                    ││Paths                                                          ││
└────────────────────────────────────────────────────┘└────────────────────────────────────────────────────────────────┘
Choose what to do for each relocation marked Choose.
↑/↓: Select · Tab/→: Details · [/]: Scroll
r: Check again · ?: Help · q: Quit
```

Details say what is there now, what decides the relocation, what apply will do,
and the full paths. Relocations already in sync are hidden while others need
attention; `c` shows them.

## 3. Choices

A relocation marked `[Choose]` waits for you. Press `Tab` to move to Details,
`↑`/`↓` to pick a choice and `Enter` to select it. A choice is for the next
apply only: checking again, saving or applying forgets it. To decide every run,
set the rule in the configuration instead (see below).

## 4. Review

Press `a` to review. Review lists every step apply will take, relocation by
relocation; `⚠` marks a step that deletes or replaces data:

```text
○ ~/.cache/tool-a
❯ ○ Copy to target and check
  ○ Replace source with a link ⚠
○ ~/.cache/tool-b
  ○ Create parent folder
  ○ Archive source
  ○ Link source to target
─ ~/.cache/tool-c (in sync)
```

Nothing has changed yet. `y` applies; `n` or `Esc` goes back to the Workspace.

## 5. Apply and results

Apply runs the steps and marks each one done (`✔`) or failed (`✖`). When it
finishes, HomeLight checks the disk again. Results stay on screen until you
check again with `r`. If the disk changed between review and apply, HomeLight
changes nothing and asks you to check again.

## What each rule does on disk

What HomeLight does depends on what it finds at the source (the path in your
home directory) and the target (the path in storage).

| Found | Badge | What apply does |
|---|---|---|
| Only the source, a directory | Move | Copies the source to the target, checks the copy, then replaces the source with a link to the target. |
| Neither | Link | Creates an empty target directory and links the source to it. |
| The source already links to the target | In sync | Nothing. |
| Only the target | Choose, or Link | Follows the **Only target** rule below. |
| Both, as directories | Choose, or the rule's badge | Follows the **Both exist** rule below. |

**Only target** (`when-only-target-exists` in the configuration):

| Rule | Configuration value | What apply does |
|---|---|---|
| Ask each time | `prompt` | Nothing until you choose. |
| Keep target, link source | `adopt-target` | Links the source to the existing target. |

**Both exist** (`when-source-and-target-directories-exist`, and
`when-adopting-target` for what happens to the source when the target is kept):

| Rule | Configuration values | What apply does |
|---|---|---|
| Ask each time | `prompt` | Nothing until you choose. |
| Keep target, ask about source | `adopt`, `prompt` | Nothing until you choose to delete or archive the source. |
| Keep target, delete source | `adopt`, `discard-source` | Deletes the source and replaces it with a link to the target. The source's contents are gone for good. |
| Keep target, archive source | `adopt`, `archive-source` | Moves the source into the archive root, then links the source to the target. The archive root defaults to `.homelight-archive` beside the source, so `~/.cache/uv` is archived to `~/.cache/.homelight-archive/uv`. |
| Leave both as they are | `leave-unchanged` | Nothing. |
| Delete both, start empty | `discard` | Deletes both directories, creates an empty target and links the source to it. Both contents are gone for good. |

A one-time choice offers the same outcomes as these rules, for one relocation
and the next apply only.

## Undoing a change

HomeLight has no undo command. Before you press `y`, nothing has changed: `n`
or `Esc` leaves Review. After apply, undo by hand. Remove the relocation from
`~/.homelight.json` first, or the next run plans it again.

- **Moved or linked:** the contents are at the target. Remove the link, then
  move the target back:

  ```sh
  rm ~/.cache/uv                      # removes only the link
  mv /local/home/me/.cache/uv ~/.cache/uv
  ```

- **Archived:** the source's old contents are in the archive (Details show the
  path under **Paths**). Remove the link and move the archive back:

  ```sh
  rm ~/.cache/uv
  mv ~/.cache/.homelight-archive/uv ~/.cache/uv
  ```

  The target keeps its own contents.

- **Deleted:** a deleted source or target cannot be recovered by HomeLight.
  Restore it from a backup.

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
