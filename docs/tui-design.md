# HomeLight TUI Design

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

Header: `⌂ HOMELIGHT` followed by the numbered destinations, for example
`[1: Workspace]  [2: Review]`. The second slot reads `[2: Results]` while results
are retained and `[Review unavailable]` when the plan cannot be reviewed. During a
run the header shows only `[Applying]`. Configuration and Browse show as
`[Configuration]` and `[Configuration › Browse]`.

Entry points:

- `homelight`, `status`, `plan` and `apply` open Workspace. `apply` never starts
  changes without review.
- `homelight init` and `homelight config` open Configuration: an existing file is
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
- `q` quits from any list. Inside a text field it types `q`. With unsaved
  configuration changes, `q` and Esc-to-close ask before discarding; Esc from
  Configuration's first fields closes at once only when nothing was typed and
  there are no relocations. With
  one-time choices not applied yet, or during an apply, `q` asks first (see
  Quit).
- One app key handler, keyed by the focused id, handles what TamboUI elements
  leave unhandled and always reports the key as handled.
- No mouse capture, so the terminal's own text selection keeps working.

Global keys on Workspace and Review: `1` Workspace, `2` Review or Results, `r`
check again, `q` quit. `2` never starts changes.

### Quit

`q` (or Ctrl-C) exits at once unless something would be lost:

- **One-time choices not applied yet** (Workspace or Review): a dialog asks
  first. The count reads `1 choice` or `2 choices`:

  ```
  Quit HomeLight?
  You have 2 choices that are not applied yet. Quitting forgets them.
  Press n to go back. You can keep choosing, or press a to review and apply.
  y: Quit · n/Esc: Go back
  ```

  A plan with no one-time choices does not ask: the next run plans it again.
- **During an apply:** see §6.

Once HomeLight is set to exit when the apply finishes, the help stops showing
`q: Quit`, because `q` then does nothing.

## 4. Visual language

### Color

HomeLight paints its own dark background on the whole screen and uses the
**Harbor** palette as exact RGB colors. Unless `COLORTERM` is `truecolor` or
`24bit`, each role falls back to the basic ANSI color of the same hue (ANSI
names: white is light gray, bright black is dark gray). Views name roles only;
one palette file maps roles to colors. Headings inside panes are bold body text.

| Role | Color | Basic | Used for |
| --- | --- | --- | --- |
| background | `#1b1d22` | black | Whole screen |
| text | `#d8dbe2` | white | Body text |
| brand | `#7fd1c7` | cyan | `⌂ HOMELIGHT` |
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
  heavy border in the focus color; others are light and dim.
- `❯` marks the selected row or focused field. A text field shows its cursor.
- Glyphs: `✔` done/in sync, `⠋…⠏` running (TamboUI `Spinner`), `○` pending,
  `✖` failed/blocked, `⚠` needs attention, `─` left as is, `⚡` will change.
- Paths on screen show the home directory as `~`. Paths sections in details show
  the full absolute path.
- Word-wrap prose; wrap paths by character only when they cannot break.
- Scrollbars appear only when content overflows. No numeric line counters.
- In-sync relocations are hidden when others exist; `c` toggles them. The list
  title says how many and which key shows them: `Relocations · c: show 2 in
  sync`, or `c: hide 2 in sync` while shown. Urgent rows (blocked, needs a
  choice) sort first.

### Dialogs

TamboUI `dialog()`: double border in the dialog color, centered both ways, sized
to its content with one cell of padding, never covering the header or help lines.
While a dialog is open, everything behind it renders non-focusable and loses its
focus highlight, so only the dialog looks active. Dialog keys: `y` confirms,
`n`/Esc cancel, and every other key is ignored.

### Choices and fields

- Every choice among fixed values is a TamboUI `Select`: `‹ Keep target, archive
  source ›`, ←/→ to change. At 80 columns the label sits above the value.
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
`[`/`]`, `←` back, `c` in the list title) are marked Help-only there.

### Help screen

`?` (or F1) opens a full-screen **Help** screen from any screen. Header
`⌂ HOMELIGHT  [Help]`, then a TamboUI tab bar with two tabs:

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
   or `q  Quit HomeLight; asks first if choices are not applied or changes are
   running`. One `KeyHint` holds both, so the two places share one source.
2. **Guide**: `docs/user-guide.md` as packaged in the build, rendered with
   TamboUI's Markdown element in the palette's colors. The guide is the only
   copy of its text; nothing in the code repeats it.

Help opens on This screen, except from the empty Workspace before there is a
configuration file: then it opens on Guide, and that Workspace says `New to
HomeLight? Press ? to read the guide.` (with no relocations it says `Press ? for
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
without color too: HomeLight keeps bold in the basic palette, and neither it nor
TamboUI drops styles for `NO_COLOR`, so no extra marker is needed.


TamboUI moves focus on Tab before any handler sees it, so the open tab follows
focus: the open tab's pane has that tab's focus id and the tab bar has the
other's.

The mouse wheel only scrolls. HomeLight does not capture the mouse, so a
terminal in its alternate screen sends the wheel as ↑/↓ and a trackpad's
sideways drift as ←/→, in quick bursts. A ← or → that comes within 150 ms of
another arrow the app sees is taken as part of such a burst and ignored, so it
neither switches Help's tab nor moves between panes; a separate press still
does. Lists take ↑/↓ themselves, so there the app sees only bursts of ←/→.

`homelight guide` prints the same guide as Markdown; `homelight --help` ends
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
   reason. A row that deletes data adds `⚠ This deletes data for good.`
4. **Choices:** the choices that apply, only when one is needed. While archiving
   is only offered, its choice names the destination: `Move the source to
   ~/.cache/.homelight-archive/… and replace it with a link to the target.`
5. **Paths:** source, target and current link destination, each once.
   `Archive:` appears only when the rule or choice archives the source, and
   `Left behind:` only when apply deletes what an interrupted replacement left.

`s: Always do this` (shown when a choice is set) opens a dialog that explains the
rule in words and says it is saved to the configuration file, that comments in
the file are not kept and that nothing on disk changes until apply. `y` writes
this relocation's rule fields to the file (same save path as Configuration), then
re-checks and says the next step.

Below the panes, when review is unavailable, one line says why: `Choose what to do
for each relocation marked Choose.` or `Fix the blocked paths; see Details.`

Empty states: no configuration (offer `i: Create configuration`), no relocations,
all in sync, left as is by rule, blocked (state the repair).

## 6. Review, applying and results

**Review** lists the exact plan as a tree: relocation rows, each with its action
rows. Summary: `5 planned changes · 2 delete or replace data`. `y` confirms a
plan with changes; `n`/Esc/`1` cancel and keep the Workspace state. A plan with
no changes says `No changes to apply`, has no confirmation, and Enter/`1`/Esc
return.

**Applying** updates the same tree in place:

- Each relocation row carries its own mark: spinner while any of its actions
  runs, `✔` when all are done, `✖` if any failed, `○` otherwise. Action rows
  use the same glyphs. A relocation row starts at the left edge with its mark
  (`✔ ~/.cache/uv`); its action rows sit two cells in, after the `❯` pointer
  slot. An in-sync relocation is one row at the left edge; when selected, `❯`
  takes its mark's cell.
- The selection stays where the user put it. It never follows running steps.
- Header line `Applying. Leave HomeLight running until it finishes.`, then a
  TamboUI line gauge (thick style) and one count line: `3 of 8 changes done ·
  2 running · 0 failed`. In-sync relocations appear as one row,
  `─ ~/.npm (in sync)`.
- Progress is per action. Never imply byte progress or rollback.
- `q` opens the quit dialog: **Keep running** (default) or **Exit when it
  finishes**. Changes always run to completion, including on failure. Results
  are not kept after exit. After **Exit when it finishes**, two lines below the
  help say so and `q: Quit` is no longer shown.

**Results** keep the tree with final marks, the gauge and the count line, which
then ends with what did not run: `3 of 8 changes done · 1 failed · 4 not run`.
On finish the selection moves once to the first failure, or else the last
completed action. Messages distinguish a plan
refused before any change (`Nothing changed: the disk no longer matches the
reviewed plan. Check again.`), a stop partway (`Stopped after some changes. Check
the failed and not-run steps, then check again.`) and success (`Done. Checked
again; results are kept until you check again.`). Results stay available through
`2` until `r` or exit.

## 7. Configuration

The draft is the configuration file's own shape, validated by the same loader the
app uses. `~` and `${USER}` stay as written.

- Left list: `Storage locations`, then each relocation by source as written
  (`~/.m2`). `a` adds a row with the source root filled in; `d` removes the
  selected row; `b` opens Browse.
- Storage locations fields: **Source root** (default `~`), **Target root**,
  **Suggestion list (optional)** with help "A file of directories to suggest, for
  example one shared across machines. Built-in suggestions are always included."
  A Resolved section shows each as an absolute path, updated as you type.
- Relocation fields: **Source**, **Target** (blank derives it from the target
  root; a source outside the source root needs one), **Both exist** and **Only
  target** as Selects, **Archive root** (blank means the default beside the
  source). Field help explains the consequence of the focused field.
- **Both exist** values: Ask each time · Keep target, delete source · Keep
  target, archive source · Keep target, ask about source · Leave both as they
  are · Delete both, start empty. **Only target** values: Ask each time · Keep
  target, link source. A rule left at "Ask each time" is not written to the file.
- Header: file path, `existing file`/`new file`, and `N unsaved changes` (the
  draft compared with the file as loaded).
- `s` saves. A new file is created directly. Replacing an existing file asks
  first: `Replace ~/.homelight.json?`, noting that comments are not kept. Save
  refuses if the file changed since it was loaded, keeps the draft and says so.
  The write is atomic.
- After saving: return to Workspace, check again, and say the next step.

## 8. Browse

- Two **Lists** lines at the top, always: the built-in list (`built in · 9
  suggestions`) and the candidate list (location, count, `file updated 28 Sep`
  from the file's modification time). A list that failed says why on its line;
  `i` shows full detail.
- One row per directory, grouped by app. Markers: `[ ]` not in the
  configuration, `[x]` in it (saved earlier or added now), `−` cannot be added.
  Space toggles; removing a row only edits the draft.
- Row notes, plain: `checking…`, `not created yet`, `already a link`, `not a
  directory`, `can't read: <reason>`, `usually not needed`.
- When both lists name a directory, the candidate list wins: its app group and
  advice show on the row; Details also shows the built-in advice.
- Directories every applicable list marks usually not needed are hidden and
  counted; `u` shows them. Rows already in the configuration are never hidden.
- `r` checks again: rows read `checking…` until checked. A refresh never changes
  the configuration draft.
- Discovery never blocks the screen and never lists directory contents. Size
  reads `not estimated` and ownership `not evaluated` until those features exist.
- Enter opens a directory's details: state, list attribution, full path, overlap
  with other suggestions.

## 9. Wording

| Thing | Words on screen |
| --- | --- |
| Policy | rule |
| `prompt` or missing | Ask each time |
| Workspace badges | `[Choose]` needs a choice, `[Blocked]`, `[Can't read]`, `[Warning]`, `[Move]`, `[Keep target]`, `[Link]`, `[Archive]`, `[Delete]`, `[Left as is]`, `[In sync]` |
| Actions | Create parent folder · Create target folder · Copy to target and check · Replace source with a link · Link source to target · Fix source link · Archive source · Delete folder · Already in sync · Leave as is |

All screen text lives in one TUI wording file.

## 10. Verification

Every UI change ships rendered captures at 80x24 and 120x30 and checks resizing
both ways. Tests drive a real session over temporary configuration files and
assert rendered screens and key handling. Passing tests are not UX acceptance;
user-visible changes need the user's walkthrough.
