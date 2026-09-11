# TamboUI evaluation for HomeLight

Date: 2026-09-11

## Decision

TamboUI is a credible replacement for Clique **when `init` needs an interactive
selector**. It provides both a full-screen TUI and an inline event loop that can
run a guided interaction without switching to the alternate screen. It is a
better fit than pairing Clique with a separate line parser or JLine-based prompt
library.

Do not migrate the existing output merely for styling. Keep a plain `PrintWriter`
renderer as the non-TTY, `--json`, and test path. Introduce a narrow terminal
presentation adapter when guided `init` is implemented; at that point, replace
Clique's small styling/tree surface rather than retaining two presentation
libraries.

The cost is maturity: the current, documented API is `0.5.0-SNAPSHOT` and its
own project labels it experimental. Pin one coordinated version through the BOM,
and add a terminal smoke test before committing to it.

## Fit against the current CLI

HomeLight currently uses Clique `4.0.3` in two places only:

- `TerminalStyle` turns six semantic styles into ANSI strings.
- `ApplyProgress` renders a relocation/action tree and a hand-written spinner;
  it owns cursor-up and line-clear escape sequences itself.

TamboUI covers that whole visual surface: styled text, a bordered block/frame,
tree, spinner, gauge, list, and table. Its inline display can reserve a live
region while lines are printed above it, which matches apply progress and a
guided command better than HomeLight's manual cursor handling. The existing
tree's textual format is not a reason to keep Clique: it is a presentation
detail, and TamboUI's tree is more capable.

| Need | Clique 4.0.3 in HomeLight | TamboUI | Fit |
| --- | --- | --- | --- |
| Semantic colour/bold text | Yes, string styling | Yes, `Text`/Toolkit styles and CSS | Direct replacement |
| Frame/panel | Not currently used | `Block` / Toolkit `panel` | Adds capability |
| Progress and spinner | Hand-written spinner | `Gauge`, `LineGauge`, `Spinner`; inline redraw | Direct replacement, less terminal-control code |
| Hierarchical plan | `Clique.tree` renders text | Stateful tree with navigation, expand/collapse and lazy loading | Direct replacement plus interaction |
| Tables | Not currently used | Stateful selectable `Table` | Available for future plan review |
| One selection | Not supported | `Select`, selectable list/table/tree | Available |
| Many selections | Not supported | No documented `MultiSelect` widget | Compose checkboxes plus a focused list |
| Text entry | Not supported | `TextInput`, `TextArea`, form validation | Available |

TamboUI's [widget reference](https://tamboui.dev/docs/main/widgets.html) is the
source for its listed widgets, including Tree, Select, Checkbox, inputs and
progress controls. Its [Toolkit examples](https://tamboui.dev/docs/main/widgets.html#using-widgets-with-toolkit-dsl)
show the DSL forms for panel, table, gauge, tree, and text input.

## Guided CLI versus full-screen TUI

TamboUI has three relevant levels:

- `TuiRunner`: an event loop, raw key input, rendering and an alternate screen.
- Toolkit DSL: declarative elements, focus routing, key/mouse handlers and CSS
  on top of the TUI runner.
- `InlineTuiRunner` / `InlineToolkitRunner`: the same event/focus model in a
  fixed-height region in the main scrollback, with no alternate screen. It
  reads keys in raw mode and redraws on resize. The runner can print completed
  output above its live region.

The last level is the relevant one for `homelight init`: it permits a compact
picker, filter field, selected-count footer and confirm/cancel keys while
preserving preceding command output. This is an interactive sub-application,
not a blocking `readLine` prompt. The official [API-level guide](https://tamboui.dev/docs/main/api-levels.html#inline-toolkit)
documents the inline runner and its Toolkit example; the current
[implementation](https://github.com/tamboui/tamboui/blob/93afda0c5576859f45ce789b521f75a736f6ead1/tamboui-tui/src/main/java/dev/tamboui/tui/InlineTuiRunner.java#L32-L44)
confirms raw input, resize, ticks, and no alternate screen.

For a later dashboard, review UI, or file browser, the same selection component
and state model can move to full-screen Toolkit. That is the main strategic
advantage over the proposed line parser: no replacement of the interaction
model is needed when the workflow outgrows a prompt.

## Selector conclusion

Single selection is supported out of the box: `Select` shows the selected value
with left/right navigation, and list/table/tree are selectable. For a filesystem
`init` flow, prefer a selectable list with a text filter when labels may be long
or the candidate set is sizable.

There is no documented multi-select control in the official widget inventory.
Build it from a cursor-owning list plus a `CheckboxState` per candidate:

- Up/down changes the focused candidate.
- Space toggles its checkbox.
- `/` focuses a `TextInput` filter; Enter confirms; Escape cancels.
- The HomeLight adapter owns validation, select-all, paging, filtering and the
  mapping from visible labels to domain candidates.

This is still materially less work than implementing terminal input, resize,
focus, redraw, Unicode widths and terminal restoration. It is not an argument
to leak TamboUI state into reconciliation or command policy.

## Dependency and compatibility implications

HomeLight uses Java 25, so TamboUI's documented Java 8 minimum and Java 17+
recommendation are compatible. The project retains Picocli `4.7.7`; TamboUI has
an optional Picocli integration, but HomeLight does not need to subclass its
`TuiCommand`. Retaining the existing Picocli command objects and calling an
inline presentation adapter is the lower-risk integration.

For the current documented line, use:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>dev.tamboui</groupId>
      <artifactId>tamboui-bom</artifactId>
      <version>0.5.0-SNAPSHOT</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
<dependencies>
  <dependency><groupId>dev.tamboui</groupId><artifactId>tamboui-toolkit</artifactId></dependency>
  <dependency><groupId>dev.tamboui</groupId><artifactId>tamboui-jline3-backend</artifactId></dependency>
</dependencies>
```

That is intentionally illustrative, not a proposed POM change. `tamboui-toolkit`
brings core, widgets, TUI, and CSS; the JLine 3 backend is separate and brings
JLine. The project source declares these edges in its
[Toolkit build](https://github.com/tamboui/tamboui/blob/93afda0c5576859f45ce789b521f75a736f6ead1/tamboui-toolkit/build.gradle.kts#L8-L13)
and [JLine backend build](https://github.com/tamboui/tamboui/blob/93afda0c5576859f45ce789b521f75a736f6ead1/tamboui-jline3-backend/build.gradle.kts#L7-L10).
The README says the current builds are snapshots and provides the required
[Sonatype snapshot repository](https://github.com/tamboui/tamboui/blob/93afda0c5576859f45ce789b521f75a736f6ead1/README.md#L221-L252).

A released `0.4.0` `tamboui-tui` exists in Maven Central, but its artifact page
shows only core, widgets and annotations as direct dependencies. Do not mix that
release with arbitrary backend versions or current `0.5.0-SNAPSHOT` examples:
resolve all Tamboui modules from one BOM/version in a spike first.
[Maven Central's 0.4.0 POM](https://central.sonatype.com/artifact/dev.tamboui/tamboui-tui)
records the released coordinates and graph.

## Migration shape

1. Leave `apply`, JSON output, pipes and test capture on current plain output.
2. Create a CLI-only `GuidedTerminal` adapter with `isInteractive` detection.
   It receives prepared view data and returns choices; it never performs
   filesystem work or decides reconciliation policy.
3. Spike the inline multi-choice interaction in a real terminal, including
   cancel, resize, Unicode paths, no-colour, interrupted input and redirected
   stdout. Add a plain fallback before enabling it in `init`.
4. If the spike is sound, replace `TerminalStyle` and `ApplyProgress` in the
   same presentation change and remove Clique. Keep the domain-facing
   `ProgressListener` unchanged.

The official project warns that it is experimental and APIs may change in its
[README](https://github.com/tamboui/tamboui/blob/93afda0c5576859f45ce789b521f75a736f6ead1/README.md#L211-L220).
That makes an adapter and a small, pinned use of the API important. It does not
block adoption here, but it makes a wholesale rewrite premature.
