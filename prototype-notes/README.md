# PROTOTYPE: TamboUI focus, fields and dialogs (throwaway, never merge)

Question: can the HomeLight TUI move to TamboUI 0.5.0's FocusManager, text inputs,
Select and dialogs, and delete our own focus and field code, while keeping:
Escape never exits; a focused text field takes typed keys; a dialog takes all keys;
one persistent app testable without a terminal; `q` quits from lists, types in fields.

Answer: yes, with one app key handler keyed by the focused element id.

- Esc: TamboUI never quits on Esc, but an unhandled Esc clears focus
  (EventRouter.java:365). The app handler turns Esc into "back one level".
- Text fields: Toolkit.handleTextInputKey switches on raw key codes, so letters type.
  Do not enable FormFieldElement.arrowNavigation with vim bindings (j moves focus).
  Ctrl-U needs one line.
- Dialogs: DialogElement swallows keys and centers itself, but Tab is routed before any
  element (EventRouter.java:244-279), so the screen behind must render focusable(false)
  while a dialog is open. Save and restore focusedId() around the dialog.
- ListElement/TreeElement act on keys even when unfocused (ListElement.java:786,
  TreeElement.java:763); the app handler always returns HANDLED to stop that pass.
- Auto element ids change per frame; give every focusable a fixed id. List/Tree keep
  selection in private state, so keep the instances across frames.
- FormElement does not fit: its fields are always focusable and have no per-field key
  hook. Use formField + FormState instead.
- TreeElement does not fit Browse: index-based selection shifts as discovery updates.
- DialogElement ignores child widths and padding in layout; set width by hand.

Code: src/test/kotlin/io/github/bigswlittlesw/homelight/tui/prototype/FocusPrototype.kt
Run: ./gradlew test --tests '*prototype*' ; renders in prototype-notes/renders.
ScreenCaptureTest.kt renders the pre-redesign screens at 80x24 and 120x30.
Decisions: docs/decisions.md entries dated 2026-10-04 (docs/tui-design-pass PR).
