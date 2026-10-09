# Lighten TUI Design

The current rules for the full-screen TUI. Decisions and the alternatives they
rejected are in [decisions.md](decisions.md); this file holds only what the TUI
must do now. Update it in the same PR as any change to the contract.

## 1. Principles

- **TamboUI first.** Use a TamboUI widget or feature when one does the job, and
  delete our own equivalent. Write our own code only where TamboUI cannot express a
  rule, and say why in a comment.
- **Plain language.** Help, labels and messages use everyday words, not internal
  names, configuration keys or enum values. The configuration's "policy" is a
  **rule** on screen.
- **Say the next step.** After a save, a re-check or a finished apply, say what to
  do next when there is something to do, for example `Saved. 1 relocation will
  change: press a to review and apply.`
- **Dialogs for short questions, screens for work.** A dialog asks one question or
  confirms one action over the current screen and always returns to exactly where
  the user was. A screen holds multi-step work with its own navigation and one
  plainly labelled way back that keeps the user's place.
- **Safety stays explicit.** Only `y` confirms filesystem changes or a file
  replacement. Enter never does. Saving never applies. Applying never saves.

## 2. Screens and navigation

One persistent TamboUI application. JSON commands share reconciliation behavior,
not screens.

| Screen | Purpose | Way back |
| --- | --- | --- |
| Workspace | Current state of every relocation, one-time choices, entry to everything else | It is the home screen |
| Review | The exact plan to apply, then progress and results in place | `1`, or `n`/Esc before confirming |
| Configuration | Create or edit the configuration file | Esc from the list closes it |
| Browse | Suggested directories to add, inside Configuration | Esc returns to Configuration |
| Help | Two tabs: This screen (place, purpose, step and keys) and Guide (the user guide) | Esc, `q`, `?` or F1 returns where you were |

Header: `⌂ LIGHTEN` followed by the numbered destinations, for example
`[1: Workspace]  [2: Review]`. The second slot reads `[2: Results]` while results
are retained and `[Review unavailable]` when the plan cannot be reviewed. During a
run the header shows only `[Applying]`. Configuration and Browse show as
`[Configuration]` and `[Configuration › Browse]`.

Entry points:

- `lighten`, `status`, `plan` and `apply` open Workspace. `apply` never starts
  changes without review.
- `lighten init` and `lighten config` open Configuration: an existing file is
  loaded for editing, a missing one starts a new file. The header says
  `existing file` or `new file`.
- From Workspace, `e` opens Configuration; with no configuration, `i` does.

## 3. Keys and focus

- Key set: TamboUI's `standard` bindings (arrows, Home/End, PageUp/PageDown). No
  vim letters, so every printable key types into a focused text field.
- Focus is TamboUI's `FocusManager` with a fixed id per focusable element. Tab and
  Shift-Tab move through every control on the screen in order (list, then each
  field, then back). Inside a form, ↑/↓ also move between fields.
- Esc goes back one level: from a field to its list, from a list to close the
  screen, from a dialog to cancel it. At Workspace's list it does nothing. Esc
  never exits.
- `q` quits from any list. Inside a text field it types `q`. In
  Configuration, `q` and Esc from its list close it: at once when the draft
  equals the file as opened, otherwise after asking before discarding. With
  one-time choices not applied yet, or during an apply, `q` asks first (see
  Quit).
- One app key handler, keyed by the focused id, handles what TamboUI elements
  leave unhandled and always reports the key as handled.
- The mouse is captured, for its wheel only. Wheel up and down scroll the pane
  under the pointer (over a list, they move its selection) and never change
  focus or a tab; sideways scrolling, clicks, drags and taps do nothing. With
  capture on, selecting text takes the terminal's bypass modifier: Shift-drag
  in WezTerm and Ghostty, Option-drag in iTerm2. The guide says so. TamboUI
  turns capture off when Lighten exits, on every exit path.

Global keys on Workspace and Review: `1` Workspace, `2` Review or Results, `r`
check again, `q` quit. `2` never starts changes.

### Quit

`q` (or Ctrl-C) exits at once unless something would be lost:

- **One-time choices not applied yet** (Workspace or Review): a dialog asks
  first. The count reads `1 choice` or `2 choices`:

  ```
  Quit Lighten?
  You have 2 choices that are not applied yet. Quitting forgets them.
  Press n to go back. You can keep choosing, or press a to review and apply.
  To make a choice the rule, select its relocation and press s.
  y: Quit · n/Esc: Go back
  ```

  A plan with no one-time choices does not ask: the next run plans it again.
- **During an apply:** see §6.

Once Lighten is set to exit when the apply finishes, the help stops showing
`q: Quit`, because `q` then does nothing.

## 4. Visual language

### Color

Lighten paints its own dark background on the whole screen and uses the
**Harbor** palette as exact RGB colors. Unless `COLORTERM` is `truecolor` or
`24bit`, each role falls back to the basic ANSI color of the same hue (ANSI
names: white is light gray, bright black is dark gray). Views name roles only;
one palette file maps roles to colors. Headings inside panes are bold body text.

| Role | Color | Basic | Used for |
| --- | --- | --- | --- |
| background | `#1b1d22` | black | Whole screen |
| text | `#d8dbe2` | white | Body text |
| brand | `#7fd1c7` | cyan | `⌂ LIGHTEN` |
| focus | `#6cb6e8` | bright blue | Focused pane border, `❯` pointer, running spinner, active header slot, scrollbar thumb |
| ok | `#7cc79a` | green | Done, in sync, the chosen value, gauge fill |
| change | `#7fd1c7` | cyan | Rows that apply will change |
| warn | `#e6b55c` | yellow | Needs a choice, data will be deleted or replaced |
| error | `#e76f6f` | red | Failed, blocked |
| dialog | `#b39cf0` | magenta | Dialog border and title |
| dim | `#6f7a88` | bright black | Help lines, inactive borders, pending steps, secondary notes |

Color may carry meaning on its own when the same information is also on screen
another way (text, a glyph or a count). Aim for pleasing colors, not only safe
ones.

### Layout and glyphs

- Master-detail panes: about 45% list, the rest details. The focused pane has a
  thick border (`┏━┓`) in the focus color; others have a plain border (`┌─┐`)
  and are dim. The border shape shows focus without color. Lists, Browse and
  Review's plan sit in a TamboUI panel, because their elements offer no
  thick border.
- `❯` marks the selected row or focused field. A text field shows its cursor.
- Glyphs: `✔` done/in sync, `⠋…⠏` running (TamboUI `Spinner`), `○` pending,
  `✖` failed/blocked, `⚠` needs attention, `─` left as is, `⚡` will change.
- Marks come in three sets that share one rule: an empty circle means nothing
  has happened to the row yet or it is not included; filled, or `✔`, means it
  has. Every set uses bare marks. Whether a row is one of many shows in
  behaviour: choosing one choice clears the others, and the Help for the
  choice keys says so.
  - Progress (Review, Applying, Results): `○` not run yet, spinner running,
    `✔` done, `✖` failed.
  - Included or not (Browse): `●` added, `○` not added, `⊘` ignored by
    you, `−` can't be added, and on a category or app heading `◐` some
    added. `⊘` is the empty circle struck through: not included, on purpose.
    Its shape differs from every other mark, and its note says `ignored by
    you`, so it reads without color. A group row gets a mark only when
    the group itself can be selected and acted on; a heading that is only a
    label gets none. Review, Applying and Results relocation rows keep their
    progress marks: those are status, not selection.
  - One of several choices (Workspace Details): `●` chosen, `○` not
    chosen. The chosen choice is green and bold, focused or not; the focused
    one has the `❯` pointer. Without color, `●` and bold mark the choice and
    `❯` marks focus.
- Every path on screen, Paths sections included, shows the home directory as
  `~`; so do the CLI's messages to people. Only the home directory becomes `~`:
  a path under a `source-root` elsewhere shows in full. JSON keeps every path
  in full. Configuration's fields show the file's text as written.
- Word-wrap prose; wrap paths by character only when they cannot break.
- Scrollbars appear only when content overflows. No numeric line counters.
- In-sync relocations are hidden when others exist; `c` toggles them. The list
  title says how many and which key shows them: `Relocations · c: show 2 in
  sync`, or `c: hide 2 in sync` while shown. Workspace rows are grouped by
  urgency: needs a choice, blocked or can't read; warning; changes; left as
  is; in sync. Within a group they keep the configuration file's order, the
  order Configuration's list shows. A row that changes group, for example
  after a choice, moves and stays selected.
- Ignored sources come last, below in sync, as a group that starts closed
  each run. Its heading is a row, `i: show 2 ignored` (`i: hide 2 ignored`
  while open), in dim text; `i` opens and closes it from anywhere on the
  Workspace, and Help lists `i`. While open, each ignored source follows as
  `[Ignored] ~/path`, in the file's order. The heading can be selected: its
  Details say what ignoring means and how to open the group, and `x` does
  nothing there.

### Dialogs

TamboUI `dialog()`: double border in the dialog color, centered both ways, sized
to its content with one cell of padding, never covering the header or help lines.
While a dialog is open, everything behind it renders non-focusable and loses its
focus highlight, so only the dialog looks active. Dialog keys: `y` confirms,
`n`/Esc cancel, and every other key is ignored.

### Choices and fields

- Every choice among fixed values is a TamboUI `Select`: `‹ Keep target, archive
  source ›`, ←/→ to change. Labels sit beside their fields, in a fixed column.
- Text fields are TamboUI text inputs: ←/→, Home/End, Backspace, Delete, Ctrl-U
  clears. `[` and `]` type normally.

### Help area

Two lines at the bottom, specific to the focused element: navigation first, then
commands. Each binding appears once. Never advertise a key that does nothing now.
Every screen offers `?: Help` before `q`. In a text field `?` types a question
mark, so there the help lines offer `F1: Help` instead; F1 opens Help on every
screen.

Each screen builds its help in one function (`ScreenHelp`): its place (such as
`Configuration › Target root`), one purpose line for its current state, its step,
and its keys. The help lines and the Help screen both read it, so they cannot
disagree. Keys left out of the help lines for room (PageUp/PageDown, Home/End,
`[`/`]`, `←` back, `c` in the list title, `i` on the ignored group's heading)
are marked Help-only there.

### Help screen

`?` (or F1) opens a full-screen **Help** screen from any screen. Header
`⌂ LIGHTEN  [Help]`, then a TamboUI tab bar with two tabs:

1. **This screen**: a pane titled with the place, the purpose line, then
   `Step: Configure › Workspace › Review › Apply › Results` with the current
   step bold in the focus color (Configuration and Browse are Configure;
   Applying is Apply). Then the heading `Keys on <place>`, the line "They work
   after you go back (Esc or q). In Help they do nothing.", and the keys in two
   groups, "Move around" and "Do", each title directly above its keys, as
   `key  description`. Help's own keys are only on its help lines.

   A key's `description` defaults to its help-line `action`. Where the short
   label needs its screen to make sense, the description names what the key
   acts on and whether it asks first, for example `↑/↓  Select a relocation`
   or `q  Quit Lighten; asks first if choices are not applied or changes are
   running`. One `KeyHint` holds both, so the two places share one source.
2. **Guide**: `docs/user-guide.md` as packaged in the build, rendered with
   TamboUI's Markdown element in the palette's colors. The guide is the only
   copy of its text; nothing in the code repeats it.

Help opens on This screen, except from the empty Workspace before there is a
configuration file: then it opens on Guide, and that Workspace says `New to
Lighten? Press ? to read the guide.` (with no relocations it says `Press ? for
help.`). From Configuration it opens on This screen, text field or not.

Keys: Tab and ←/→ switch tabs (not `1`/`2`); ↑/↓, PageUp/PageDown, Home/End and
`[`/`]` scroll the open tab, and each tab keeps its scroll position. Esc, `q`,
`?` and F1 all go back exactly where the user was, with focus and selection
kept. As in less, man and other help screens, `q` never quits from Help and
never opens a screen's discard question. Ctrl+C quits through the usual path, as
everywhere: the quit question with unapplied choices, the discard question over
a Configuration draft, the exit-when-finished dialog during an apply. Every
other key does nothing. An apply keeps running behind Help.

Help lines: `↑/↓: Scroll · PageUp/PageDown: Page · Home/End: Top/bottom` and
`Tab/←/→: Other tab · Esc/q: Back to <screen>`, where `<screen>` is the screen
in the This screen pane's title, for example `Back to Configuration`. `?` and
F1 also go back but are not listed: they are how the reader opened Help. When
the open tab has nothing to scroll, the first line is empty: every scroll key is
left out, as on any screen.

The tab bar shows the open tab bold in the focus color and the other dim
(`TabsElement` highlight style). Bold marks the open tab and the current step
without color too: Lighten keeps bold in the basic palette, and neither it nor
TamboUI drops styles for `NO_COLOR`, so no extra marker is needed.


TamboUI moves focus on Tab before any handler sees it, so the open tab follows
focus: the open tab's pane has that tab's focus id and the tab bar has the
other's.

The mouse wheel scrolls the open tab and never switches it (see §3 Keys and
focus).

`lighten guide` prints the same guide as Markdown; `lighten --help` ends
with its online address.

## 5. Workspace

Summary rows count relocations:
`4 relocations · ⚡ 2 to change · ⚠ 0 need a choice · ✖ 0 blocked` and
`✔ 2 in sync · ─ 0 left as is`. A risk row appears only when non-zero:
`Of these: 1 with warnings · 1 deletes data`.

A relocation **deletes data** when apply removes content that is not kept
anywhere else: deleting the source while keeping the target, deleting both, or
deleting the original source an interrupted replacement left behind. A Move does
not: it replaces the source with a link only after the copy at the target is
checked, and its **Will do** line says so.

The details pane answers, in this order:

1. **Now:** what is there, for example `Now: both ~/.cache/uv and its target are
   directories.` Keep link, unreadable and missing cases explicit.
2. **Decision:** one line, only when a rule governs the case observed now (both
   exist, or only the target exists) or a choice is set. It says what will
   happen and where that comes from: `Decision: ask each time (your
   configuration)` or `Decision: keep target, delete source (your choice, this
   run only)`. Rows no rule governs (Move, Link, In sync, blocked, can't read) have
   no Decision line. A choice is for the next apply only and is cleared by any
   re-check, save or apply.
3. **Will do:** the consequence of the current rule or choice. Without a choice:
   `Will do: nothing until you choose.` A blocked row adds `Problem: …` with the
   reason, such as `Problem: /scratch/archive is a file, not a folder.` when a
   folder a step would create or work in is something else. Overlapping
   relocations block only themselves, each naming the other: `Problem:
   ~/a contains ~/a/b, which is also a relocation.` A move whose staging
   folder is on another filesystem than its target is blocked before Review,
   naming both paths and the `staging-root` setting. When one of the
   row's choices plans without that folder, the next line is `Or choose an
   option below that doesn't need this folder.` A row that deletes data adds `⚠ This deletes data for good.`
4. **Choices:** the choices that apply, only when one is needed. While archiving
   is only offered, its choice names the destination: `Move the source to
   ~/.cache/.lighten-archive/… and replace it with a link to the target.`
5. **Paths:** source, target and current link destination, each once.
   `Archive:` appears only when the rule or choice archives the source, and
   `Left behind:` only when apply deletes what an interrupted replacement left.

`s: Always do this` is shown, on the navigation line beside the choice keys, while
the selected relocation has a choice that differs from its rule; a choice the rule
already makes has nothing to save. It opens a dialog that says the rule in words
(`From now on, for ~/.cache/uv,` / `when the source and the target both exist:
keep target, delete source.`), that `y` saves it in the configuration file (named),
that comments in the file are not kept and that nothing on disk changes until
apply. A rule that deletes data (Keep target, delete source; Delete both, start
empty) adds a `⚠` line in the warning color saying it deletes for good whenever
it applies, `lighten apply --yes` included. `y` writes this relocation's rule fields to the file through Configuration's
replace (refused if the file changed since it was read), then checks again, so the
Decision line reads `(your configuration)` and the choice is gone, and says the
next step. Focus returns to the list with Details at the top, as after a save in
Configuration, so the Decision line is in view. A refused save keeps the choice and says so below the panes. In Details
the help line shows `Esc: Back`; `Tab` and `←` go back too and are Help-only, so the
line fits 80 columns. Help › This screen lists `s` under Do all the same.

**Ignoring a source.** An ignored source is one the user told Lighten to leave
alone, for example because another tool such as Stow manages it. Lighten plans
nothing for it and never offers to add it, but always shows it: in the
Workspace's ignored group and in Browse. A path can't be both a relocation and
ignored; the loader refuses such a file (`relocations[1].source-path and
ignored-source-paths[0] are both ~/b. A path can't be both a relocation and
ignored: remove it from one of the two lists.`), and so does every save.

- `x: Ignore` on a relocation opens a dialog, `Ignore ~/.cache/uv?`: Lighten
  will stop managing it, `y` moves it from relocations to
  ignored-source-paths in the configuration file (named), the whole file is
  rewritten and comments are not kept, and nothing on disk changes. When the
  relocation is linked now (`The source link already points to the
  target.`), a `⚠` line in the warning color says the link and the files at
  the target stay as they are, and how to undo the move by hand: `rm` the
  link, then `mv` the target back.
- `x: Stop ignoring` on an ignored row asks `Stop ignoring ~/x?` the same
  way, adding that Lighten manages it only once it is added as a relocation.
- Saving checks again, which forgets one-time choices. While another
  relocation has one, both dialogs add `This also forgets your other one-time
  choices.` The ignored relocation's own choice goes with it, so it alone
  does not add the line.
- `y` saves through the same path as `s` (refused if the file changed since
  it was read: `Not saved: the configuration file changed after Lighten read
  it. Press r to read the file again, then x again; that forgets one-time
  choices.`), checks again and says the next step. `n`/Esc cancel.
- `x` sits on the navigation line beside `s`; Help lists it under Do. In
  Details with choices the line has no room, so there it is Help-only; it
  works from both panes. It is not offered while results are kept.
- An ignored row's Details: `Ignored by you`, that Lighten plans nothing for
  it and leaves it as it is, how to undo (`x`), and its path.
- Ignoring the last relocation is allowed: a file that only ignores paths
  saves and loads, and the Workspace then has no relocations.

Below the panes, when review is unavailable, one line says why: `Choose what to do
for each relocation marked Choose.` or `Fix the blocked paths; see Details.`

`e: Edit` opens Configuration on the file whenever it loads. A file Lighten
cannot read is fixed by hand: `e` is not offered, and `lighten init` and
`config` refuse it with the explanation below.

A file Lighten cannot read fills the Workspace's only pane with what is wrong
and how to fix it, and Help's purpose line repeats it:

```
Lighten can't read ~/.lighten.json
It isn't valid JSON: line 1, column 1 should start with "{" but starts with "l".

To fix it: open the file in a text editor, correct that line, then press r to check again.
To start over: rename or delete the file, then press r. Lighten then offers i to create a new one.
```

The path is the file in use, `--config` included. Text that is not JSON gets
plain words with its line and column. A missing key (`target-root is
missing. Add it under "lighten".`) and a value of the wrong kind (`Line 2:
relocations[0].source-path should be text, but it is a number.`) get plain
words too, with the line but no column, as do an unknown key (`Line 2:
relocations[0] has an unknown setting "existing". Check its spelling or
remove it.`) and a bad rule value (`relocations[0].when-only-target-exists
can't be "sometimes". Use one of: prompt, adopt-target.`). The loader's own
checks (a relative path, a blank path) keep their words. A problem with no
line says `correct that setting`. The help lines offer only `r`, `?` and `q`.
The CLI prints the same lines on stderr, with `run the command again` for `press r` and `run lighten
init` for `press r … i`.

Empty states: no configuration (offer `i: Create configuration`), no relocations
(press `e` to add them), all in sync, left as is by rule, blocked (state the
repair).

## 6. Review, applying and results

**Review** lists the exact plan in a TamboUI list: each relocation is a
heading, its action rows under it, as Browse lays out apps (§8).
Summary: `5 planned changes · 2 delete or replace data`. `y` confirms a plan
with changes; `n`/Esc/`1` cancel and keep the Workspace state. A plan with no
changes says `No changes to apply`, has no confirmation, and Enter/`1`/Esc
return.

```
┏Plan━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓
┃ ✔ ~/.cache/tool-a                ┃
┃   ✔ Copy to target and check     ┃
┃❯  ⠙ Replace source with a link ⚠ ┃
┃ ─ ~/.npm (in sync)               ┃
```

- `❯` has its own one-cell column, in the focus color; the selected row is
  bold. A heading is the relocation's progress mark and its path in bold. Its
  action rows follow, their marks two cells further in, so headings stand
  apart without `▼`, guides or colour. An in-sync relocation is one dim
  heading, `─` and its path. At 80 columns the longest label, `Replace source
  with a link ⚠`, fits beside the scrollbar.
- Every row is selectable. Details (titled `Details`) show a relocation row's
  path, its Decision line in the Workspace's words (§5), and its paths (Source,
  Target, and Archive when a step archives); an action row shows the action and
  its paths. The Decision line is the one-time choice the plan was reviewed
  with, `Decision: keep target, delete source (your choice, this run only)`, or
  else the saved rule for the reviewed case, `Decision: keep target, archive
  source (your configuration)`. Starting the review captures the choices with
  the reviewed plan, so Results still name a choice after the apply forgets it.
- The list takes ↑/↓, PageUp/PageDown and Home/End. Every other key goes to the
  app first: → opens Details, ← does nothing in the list, and Enter keeps its
  screen meaning. Relocations do not collapse. The wheel moves the selection
  one row and clicks do nothing, as on the other lists (§3).

**Applying** updates the same list in place:

- Each relocation heading carries its own progress mark: spinner while any of
  its actions runs, `✔` when all are done, `✖` if any failed, `○` otherwise.
  The marks show status, not selection (§4). Action rows use the same glyphs.
- The selection stays where the user put it. It never follows running steps.
- Header line `Applying. Leave Lighten running until it finishes.`, then a
  TamboUI line gauge (thick style) and one count line: `3 of 8 changes done ·
  2 running · 0 failed`. In-sync relocations appear as one row,
  `─ ~/.npm (in sync)`.
- Progress is per action. Never imply byte progress or rollback.
- `q` opens the quit dialog: **Keep running** (default) or **Exit when it
  finishes**. Changes always run to completion, including on failure. Results
  are not kept after exit. After **Exit when it finishes**, two lines below the
  help say so and `q: Quit` is no longer shown.

**Results** keep the list with final marks, the gauge and the count line, which
then ends with what did not run: `3 of 8 changes done · 1 failed · 4 not run`.
On finish the selection moves once to the first failure, or else the last
completed action. Messages distinguish a plan
refused before any change (`Nothing changed: the disk no longer matches the
reviewed plan. Check again.`), a step whose guard found something other than the
plan (`Stopped: a step found something different from the plan. The steps after
it did not run. See the failed step's details, then press r to check again.`;
it does not guess when the disk changed), any other stop, counting the changes made
(`Stopped after 2 changes. Check the failed and not-run steps, then check
again.`, or with none `Stopped. Nothing was changed. Check the failed step,
then check again.`) and success
(`Done. Checked again; results are kept until you check again.`). Results stay
available through `2` until `r` or exit.

A failed step's Details start with its problem in plain words, in the error
colour: the path, what is there, what Lighten expected and what to do, such as
`/scratch/archive/tool-b already exists as a file. Lighten expected nothing
there. Move or remove it.` Paths show home as `~`. The sentence does not say to
press r: the headline does. It is the only text for the failure, so it keeps
everything the executor's text had for a bug report: every path, what was
expected and found, and the system's reason (`Lighten couldn't change ~/x: no
space left on device.`). A failure Lighten cannot name, such as an internal
error, shows its text as it is.

A completed copy that left out sockets says so in place of its text, in the
same colour. It names one (`Skipped ~/.local/share/zed/zed-stable.sock;
programs recreate it.`) and counts several (`Skipped 3 sockets; programs
recreate them.`). `apply --json` keeps the executor's text, which names them
all. A copy that reaches a named pipe or device file fails with `~/x/ipc is
a named pipe; Lighten can't move it, so it threw the copy away and moved
nothing. Remove it, or move this folder yourself.`

## 7. Configuration

The draft is the configuration file's own shape, validated by the same loader the
app uses. `~` and `${USER}` stay as written, and settings the screen does not
show are kept. `ignored-source-paths` changes only through Browse's `x`; the
list does not show it.

- Left list (`Storage and relocations`): `Storage locations`, then each
  relocation by source as written (`~/.m2`). `a` adds a row with the source
  root filled in and focuses its Source; `d` removes the selected row; `b`
  opens Browse; Enter, → or Tab move to the fields.
- With no relocations, the list says how to add one, in dim text under
  `Storage locations`: `No directories yet.`, then `Press b to pick from 59
  built-in suggestions (JetBrains, pip, Cargo, Conan, …), or a to type one
  yourself.` The count is the built-in list's directories, as Browse counts
  them, and the examples are the first app of each of its first four
  categories; both come from the list, never from the code. While a field
  has focus, where `b` and `a` type, it reads `Esc, then b to pick …`.
- **First run.** A new file opens on Target root. The first time focus goes
  from the storage locations' fields to the list (Esc, or Tab past the last
  field) with both roots valid and no relocations yet, Browse opens by
  itself, with a two-line note over its Lists lines: `Pick what to move:
  Space adds.`, then `Rather type a path yourself? Press Esc, then a.` Each
  line fits 80 columns. Moving between the storage
  locations' fields does not open it, so Source root and a list of your own
  can be set first. Esc returns to the list. It happens once per
  Configuration; an existing file, even one with no relocations, opens on
  its list and never opens Browse by itself.
- Right, top: the selected item's fields, one row each, the label in an
  18-cell column beside the value (at 80x24 a field is 32 cells wide). Text
  fields are TamboUI text inputs; Both exist and Only target are TamboUI
  `Select`s (`‹ Ask each time ›`, ←/→ change). ↑/↓ move between fields, Esc
  goes back to the list. A value longer than its field scrolls sideways while
  typing, to keep the cursor in view, and shows its start again once the field
  loses focus.
- Right, bottom: **Details**, the focused field's help (in a text field also
  `Esc, then s to save.`), then a **Resolved** section with each path as the
  loader reads it (home as `~`, updated as you type), or why it cannot. It always
  shows a field's whole value, and scrolls with the wheel, so long paths never
  push a field away.
- Every path must be full or start with `~/`; anything else reads `Use a full
  path, or one starting with ~/`.
- Storage locations fields: **Source root** (default `~`), **Target root**,
  **Suggestion list** (placeholder `optional; adds to built-in list`, which
  fits the 32-cell field at 80 columns) with help "Lighten already includes
  59 suggestions for common tools (JetBrains, pip, Cargo, Conan, …). Use this
  field only to add a list of your own, for example one shared by your team.
  Both lists are merged; yours wins where they overlap.", count and examples
  as in the empty list.
- Relocation fields: **Source**, **Target** (blank derives it from the target
  root; a source outside the source root needs one), **Both exist**, **Only
  target**, **Archive root** (blank means the default beside the source).
- **Both exist** values: Ask each time · Keep target, delete source · Keep
  target, archive source · Keep target, ask about source · Leave both as they
  are · Delete both, start empty. **Only target** values: Ask each time · Keep
  target, link source. A rule left at "Ask each time" is not written to the file.
  Delete both, start empty shows a warning in Details.
- Under the header: file path, `existing file`/`new file`, and `N unsaved
  changes`: each storage location that differs from the file as opened, each
  relocation added, removed or edited, and each path ignored or no longer
  ignored. Changing a field back is no change.
- `s` saves from the list or a Select (in a text field it types). The draft is
  checked first: the first field the loader would reject is selected and named
  (`Not saved. Storage locations › Target root: …`), and overlapping
  relocations are refused as a whole. A new file is created directly.
  Replacing an existing file asks first: `Replace ~/.lighten.json?`, noting
  that comments are not kept. Save refuses if the file changed since it was
  loaded, keeps the draft and says so: `Not saved: the configuration file
  changed after Configuration opened it. Your changes are still here. To start
  again from the file, press q, then y, then e.` The write is atomic.
- After saving: return to Workspace, check again, and say the next step below
  the panes, for example `Saved. 1 relocation will change: press a to review
  and apply.` It stays until the next key the Workspace handles.
- Help lines never carry a field's note, so both stay one row each at 80
  columns on every focus. In a text field they offer `F1: Help`.

## 8. Browse

The feature is the **suggestion list**: the built-in list and your list. The
screen never says "candidate" or "draft".

- Two **Lists** lines at the top, always: `Built-in list · 9 suggestions` and
  `Your list · ~/team/suggestions.json · 4 suggestions · file updated 28 Sep`
  (the file's modification time, read with the list). Without a list of your
  own the second line says so. A list that was not used says why on its line
  (`not used: file not found`); `i` opens **Suggestion lists** with the full
  detail.
- When Browse opens by itself on a first run (§7), its note sits above the
  Lists lines until Browse closes. Its Help purpose always ends `Anything
  missing: press Esc, then a to type it.`
- The suggestions are a TamboUI list in a panel titled **Browse**, in three
  levels. Each category (`JVM`, `Python`, …) is a heading; apps no list
  gives a category are under `Other tools`. Each app is a heading under its
  category, two cells in, and its directories follow, one per row, two
  cells further in. Directories no list gives an app are under
  `Other directories`, a heading at the categories' level. A heading has a
  mark (`●` all added, `◐` some, `○` none, `−` none can be), its name in
  bold, and in dim text at the notes column how many of the directories
  beneath it that can be added are added (`1 of 2 added`), or `can't add`.
  The indents let headings stand apart without colour. A path shows at most
  28 cells, and the notes column starts after it, for headings and rows
  alike; at 80 columns that leaves 40 cells for a note beside the scrollbar:

  ```
  ┃ ◐ Python                            1 of 3 added                ┃
  ┃   ◐ uv                              1 of 2 added                ┃
  ┃❯    ● .local/share/uv                                           ┃
  ┃     ○ .local/share/uv/tools                                     ┃
  ┃   ○ pixi                            0 of 1 added                ┃
  ┃     ○ .pixi/envs                    not created yet             ┃
  ┃ ○ Other directories                 0 of 1 added                ┃
  ┃     − link-cache                    already a link              ┃
  ```

  Categories and apps keep the order they first appear in, built-in list
  first; `Other tools`, then `Other directories`, come last.

  `❯` is one cell; the selected row is bold. Groups do not
  collapse. The selection follows an item: checking again, `u`, `f`, adding
  and removing never move it to another row. When the selected row is hidden,
  the row at its place is selected and stays selected. Rows keep the place
  they were first listed in.
- Marks: `○` not in the configuration, `●` in it (saved earlier or added
  now), `−` cannot be added. Space toggles; removing a row only edits the
  configuration on screen, and `s` in Configuration writes it. A row taken out
  stays listed as `○` until Browse closes, even when no list suggests it.
  `e` on a `●` row edits it in Configuration.
- `x` on a directory ignores it (`x: Ignore`): a `●` row moves from the
  relocations to the ignored paths, as on the Workspace, and any other row
  joins them. On a `⊘` row `x` stops ignoring it (`x: Stop ignoring`), and it
  shows as `○` until Browse closes. Like Space, `x` changes only the
  configuration on screen; `s` writes it. An ignored row is `⊘`, notes
  `ignored by you`, is never hidden, can't be added (Space does nothing and is
  not offered), and is listed even when no list suggests it (under Other
  directories). Its Details start `Ignored by you` and say to press `x` to
  stop ignoring it.
- Space on a `○` or `◐` heading, a category's or an app's, adds every shown directory under it that can be added,
  each as it would be one by one, so one that overlaps is skipped; on `●` it
  takes them all out, and on `−` it does nothing. Each directory is one unsaved change. When
  rows were skipped, a line says so: `Added 3. Skipped 1 that overlaps
  ~/.cache.`, `Skipped 1 that can't be added.`, `Skipped 1 you ignored.`
  Ignored rows are not counted in a heading's `1 of 2 added`. Enter on a
  heading does nothing.
- Row notes, plain: `checking…`, `not created yet`, `already a link`, `not a
  directory`, `can't read: <reason>`, `usually not needed`. A note that only
  says the directory is not there yet (`not created yet`, `checking…`) is
  dim; `already a link` and `usually not needed` are in the text color, and
  problems (`can't read: …`, `not a directory`) in the warning color.
- When both lists name a directory, your list wins: its app group and advice
  show on the row. When both lists name an app with different categories,
  your list's category wins; an app your list names without one keeps the
  built-in list's. Details shows every list's advice, yours first.
- A directory is hidden only when every list that names it marks it usually
  not needed, and is counted (`1 usually not needed, hidden`); `u` shows them.
  Rows already in the configuration, and ignored rows, are never hidden.
- A **count line** under the Lists lines says how many listed directories
  are found on this machine: the last check saw a directory or a link there.
  A link counts, as a directory Lighten has moved is one. It reads
  `Checking this machine…` until every row is checked, then `12 found on this
  machine`, with the hidden count after a ` · ` on the same line, so the list
  keeps its rows at 80x24. `f` (`f: Found only`, `f: Show all`) shows only
  the found directories, and those in the configuration, which are never
  hidden, as with `u`; their `●` and note set them apart. The line then adds
  `, plus 2 in your configuration` when such rows are not found, else `, only
  these shown`. The hidden count then counts only found ones. A heading with
  nothing shown beneath it is not listed, and a heading's `1 of 2 added`
  counts only its shown directories. Found rows keep their list order: nothing sorts them first.
  `f` sits on the navigation help line, as the other line is full at 80
  columns; Help lists it under Do. It is offered when something is found,
  and always while on, so it can be turned off. With nothing found and `f`
  on, the list reads `None found on this machine. Press f to show every
  suggestion.`
- While `f` is on, Space on a heading adds only the found directories under
  it, and Help says so (`Add every directory under it found on this machine
  that can be added`). The line after it names what it skipped: `Added 2.
  Skipped 3 not found on this machine; f shows all.` Taking a group out is
  unchanged: every directory in the configuration under it is shown.
  Browse opens with `f` off, on a first run too.
- `r` is **Check again**: it reads the lists again and rows read `checking…`
  until checked. It never changes the configuration.
- Discovery never blocks the screen and never lists directory contents. Size
  reads `not estimated` and ownership `not evaluated` until those features exist.
- Enter on a directory opens **Details**: whether it is in the configuration,
  state, full path, overlap with other suggestions (`Also suggested, inside
  it: …`), then **Suggested by** with each list's group, advice and reason, or
  `No list suggests it.` A list's caution follows its reason on its own line,
  `⚠ Caution: …`, in `warn`; the sign keeps it visible without color.
- The mouse wheel over the list moves its selection a row; over Details or
  Suggestion lists it scrolls them. Clicks do nothing.

## 9. Wording

| Thing | Words on screen |
| --- | --- |
| Policy | rule |
| `prompt` or missing | Ask each time |
| Workspace badges | `[Choose]` needs a choice, `[Blocked]`, `[Can't read]`, `[Warning]`, `[Move]`, `[Keep target]`, `[Link]`, `[Archive]`, `[Delete]`, `[Left as is]`, `[In sync]`, and `[Ignored]` for an ignored source |
| Actions | Create parent folder · Create target folder · Copy to target and check · Replace source with a link · Link source to target · Fix source link · Archive source · Delete folder · Already in sync · Leave as is |

All screen text lives in one TUI wording file.

## 10. Verification

Every UI change ships rendered captures at 80x24 and 120x30 and checks resizing
both ways. Tests drive a real session over temporary configuration files and
assert rendered screens and key handling. Passing tests are not UX acceptance;
user-visible changes need the user's walkthrough.
