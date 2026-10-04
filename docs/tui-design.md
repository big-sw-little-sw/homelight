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
  configuration changes, `q` and Esc-to-close ask before discarding.
- One app key handler, keyed by the focused id, handles what TamboUI elements
  leave unhandled and always reports the key as handled.
- No mouse capture, so the terminal's own text selection keeps working.

Global keys on Workspace and Review: `1` Workspace, `2` Review or Results, `r`
check again, `q` quit. `2` never starts changes.

## 4. Visual language

### Color

HomeLight paints its own dark background on the whole screen and uses the
**Harbor** palette as exact RGB colors. On a terminal that does not report full
color (`COLORTERM`), each role falls back to its nearest basic ANSI color. Views
name roles only; one palette file maps roles to colors.

| Role | Color | Used for |
| --- | --- | --- |
| background | `#1b1d22` | Whole screen |
| text | `#d8dbe2` | Body text |
| brand | `#7fd1c7` | `⌂ HOMELIGHT` |
| focus | `#6cb6e8` | Focused pane border, `❯` pointer, running spinner, active header slot |
| ok | `#7cc79a` | Done, in sync, the chosen value, gauge fill |
| change | `#7fd1c7` | Rows that apply will change |
| warn | `#e6b55c` | Needs a choice, data will be deleted or replaced |
| error | `#e76f6f` | Failed, blocked |
| dialog | `#b39cf0` | Dialog border and title |
| dim | `#6f7a88` | Help lines, inactive borders, pending steps, secondary notes |

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
- In-sync relocations are hidden when others exist; `c` toggles them and a count
  says how many are hidden. Urgent rows (blocked, needs a choice) sort first.

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

## 5. Workspace

Summary rows count relocations:
`4 relocations · ⚡ 2 to change · ⚠ 0 need a choice · ✖ 0 blocked` and
`✔ 2 in sync · ─ 0 left as is`. A risk row appears only when non-zero:
`Of these: 1 with warnings · 1 deletes or replaces data`.

The details pane answers, in this order:

1. **Now:** what is there, for example `Now: both ~/.cache/uv and its target are
   directories.` Keep link, unreadable and missing cases explicit.
2. **Your rule:** the rule for this observed case, for example `Your rule: keep
   target, ask about source.`
3. **Your choice (not saved):** a `Select` of the choices that apply, only when
   one is needed. A choice is for the next apply only and is cleared by any
   re-check, save or apply.
4. **Will do:** the consequence of the current rule or choice, with destructive
   effects stated plainly. Without a choice: `Will do: nothing until you choose.`
5. **Paths:** source, target, archive and current link destination, each once.

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
  runs, `✔` when all are done, `✖` if any failed. Action rows use the same glyphs.
- The selection stays where the user put it. It never follows running steps.
- Header line `Applying. Leave HomeLight running until it finishes.`, then a
  TamboUI line gauge (thick style) and one count line: `3 of 8 changes done ·
  2 running · 0 failed`. In-sync relocations appear as `─ ~/.npm (in sync)`.
- Progress is per action. Never imply byte progress or rollback.
- `q` opens the quit dialog: **Keep running** (default) or **Exit when it
  finishes**. Changes always run to completion, including on failure. Results
  are not kept after exit.

**Results** keep the tree with final marks. On finish the selection moves once to
the first failure, or else the last completed action. Messages distinguish a plan
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
  **Candidate list (optional)** with help "A file of directories to suggest, for
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
| Workspace badges | `[Choose]` needs a choice, `[Blocked]`, `[Can't read]`, `[Check]` warning, `[Move]`, `[Keep target]`, `[Link]`, `[Archive]`, `[Delete]`, `[Left as is]`, `[In sync]` |
| Actions | Create parent folder · Create target folder · Copy to target and check · Replace source with a link · Link source to target · Fix source link · Archive source · Delete folder · Already in sync · Leave as is |

All screen text lives in one TUI wording file.

## 10. Verification

Every UI change ships rendered captures at 80x24 and 120x30 and checks resizing
both ways. Tests drive a real session over temporary configuration files and
assert rendered screens and key handling. Passing tests are not UX acceptance;
user-visible changes need the user's walkthrough.
