# HomeLight TUI Design Language

This document specifies the design language, layout architecture, typography, color ergonomics, progress indicators, and keyboard navigation models across all HomeLight TUI screens.

---

## 1. Core Principles

- **Terminal Background Inheritance:** Never hardcode terminal background fills; inherit the user's terminal background to support dark, light, Solarized, and transparent themes cleanly.
- **Explicit ANSI Color for Bold Contrast:** Always pair `.bold()` with an explicit ANSI color (e.g., `cyan().bold()`, `gray().bold()`) to ensure terminal emulators activate their high-intensity bold palette across all fonts.
- **Theme-Safe Semantic Colors:** Use standardized semantic colors that maintain readable contrast on both dark and light backgrounds.
- **Master-Detail with Path Delegation:** Use master-detail layouts for exploration and inspection; abbreviate/truncate paths in the master list and preserve complete unabbreviated paths in the detail inspector.
- **Progressive Disclosure:** Keep healthy/steady-state items out of the primary scan path by collapsing them with subtle summary indicators (`▶ N in sync items hidden`).

---

## 2. Header & Navigation Structure

All full-screen views share a unified top header anatomy:

```text
⌂ HOMELIGHT   [1: Status]   [2: Plan]   [3: Apply]   [4: Config]
Config:      ~/.config/homelight/homelight.yaml
Target Root: /Volumes/Storage/user
```

### Component Rules
- **Brand Anchor:** `⌂ HOMELIGHT` styled in `cyan().bold()`.
  - The house glyph `⌂` (`\u2302`) provides universal monospace font compatibility and reliable 1-cell width.
- **Screen Navigation Tabs:** Numbered square-bracket tabs (`[1: Status]`, `[2: Plan]`, etc.).
  - **Active Tab:** `cyan().bold()` (or `red().bold()` on configuration error, e.g., `[1: Status (Error)]`).
  - **Inactive Tabs:** `gray().dim()`.
- **Metadata Block:** Key-value rows immediately following the tab strip:
  - Field labels (`Config:      `, `Target Root: `) styled in `gray().dim()`.
  - Paths formatted using `$HOME` abbreviation (`~`).

---

## 3. Color Palette & Semantics

| Semantic State | Color / Style | Glyph / Badge | Usage |
| :--- | :--- | :--- | :--- |
| **In Sync / Success** | Green bold | `✔ [In Sync]` | Reconciled symlinks, successful step executions |
| **Active / Pending** | Cyan bold | `[Migrate]` / `[Pending]` | Active screen tabs, planned migrations, running spinners |
| **Warning / Attention** | Yellow bold | `⚠ [Conflict]` | Directory merge conflicts, manual decision needed |
| **Blocked / Error** | Red bold | `✖ [Blocked]` | Missing target roots, file collision, execution failure |
| **Neutral / Inactive** | Dim gray | `[Skipped]` | Bypassed policies, metadata labels, collapsed summaries |

---

## 4. Layout Architecture Across Screens

### Screen 1: Status (`[1: Status]`)
- **Layout:** Master-Detail (Side-by-Side).
- **Left Panel (Master List):**
  - Items sorted by urgency: `Conflict`/`Blocked`/`Inaccessible` → `Warning` → `Pending` → `Skipped` → `In Sync`. Sub-sorted alphabetically by path.
  - Paths formatted relative to `~` with middle truncation (`formatListPath(path, 40)`).
  - In-sync items collapsed by default when unresolved items exist, with toggle placeholder: `▶ N in sync items hidden (press 'c' to reveal)`.
- **Right Panel (Details):** Full source/target paths, observations, explicit configuration rules (e.g., `Policy: Default`), and planned actions.

### Screen 2: Plan (`[2: Plan]`)
- **Layout:** Master-Detail (Side-by-Side).
- **Left Panel:** Actionable relocations grouped by phase or priority (`[Link]`, `[Migrate]`, `[Adopt]`, `[Backup]`).
- **Right Panel:** Granular dry-run details (exact source, target, backup paths, safety warnings) and interactive conflict resolution choices.
- **Interaction:** Direct execution trigger via `Enter` or `a` immediately switches to `[3: Apply]`.

### Screen 3: Apply (`[3: Apply]`)
- **Layout:** Split Checklist & Live Log.
- **Left Panel:** Step-by-step progress checklist with live status glyphs.
- **Right Panel:** Streaming execution output, byte transfer metrics, or failure diagnostics for the focused step.

### Screen 4: Config (`[4: Config]`)
- **Layout:** Master-Detail Split or Full-Width Editor.
- **Left Panel:** Settings categories (`Global Defaults`, `Storage Roots`, `Relocations`, `Managed Links`).
- **Right Panel:** Resolved values, inheritance indicators, and documentation.

### Onboarding Wizard (`homelight init`)
- **Layout:** Single-Column Card / Form.
- Guided step-by-step path discovery, storage root selection, and policy prompts. Handoffs directly to `[2: Plan]` upon completion.

---

## 5. Progress Indicators & Execution Language

### Discrete Step Glyphs
- `○` (`\u25CB`, Dim Gray): Pending / Queued.
- `⠋ ⠙ ⠹ ⠸ ⠼ ⠴ ⠦ ⠧ ⠇ ⠏` (Cyan Bold): In-progress / Running spinner.
- `✔` (`\u2714`, Green Bold): Completed successfully.
- `✖` (`\u2716`, Red Bold): Step failed or blocked.
- `⚠` (`\u26A0`, Yellow Bold): Warning / confirmation required.
- `─` (`\u2500`, Dim Gray): Skipped by policy.

### Aggregate Progress Bars
For multi-relocation batches and data copies:
```text
Applying: [██████████████░░░░░░░░░░] 58% (7/12 actions)
```
- Completed track (`█`): Cyan bold (active) or Green bold (completed).
- Remaining track (`░`): Dim gray.

---

## 6. Navigation, Routing & Lifecycle

- **Default Launch (`homelight`):**
  - **Config Present:** Opens directly on `[1: Status]` for instant verification.
  - **Config Missing:** Launches directly into `Init` / Setup Wizard with clear cancellation (`Esc`/`q`).
- **Direct Workflow Transitions:**
  - `p` / `2` on Status advances to `[2: Plan]`.
  - `Enter` / `a` on Plan advances to `[3: Apply]`.
  - Completion on Apply provides `Enter: Back to Status  ·  q: Quit`.
- **Keyboard Cheat Sheet:** Standardized single-row footer across all views (`↑/↓/j/k: Select  ·  ...  ·  q: Quit`).

---

## 7. Interactive Selection & Focus Switching

### Selection & Focus Indicator Rules
- **Mutually Exclusive Options (Single-Choice Radio Buttons):**
  - Selected / Chosen: `(●)` (`\u25CF` inside parentheses, Cyan/Green bold).
  - Unselected: `(○)` (`\u25CB` inside parentheses, Dim gray).
  - Used for conflict resolution strategies (e.g., Adopt, Discard, Leave Unmanaged) where options are strictly mutually exclusive.
- **Multi-Select Options (Checkboxes):**
  - Selected / Checked: `[x]`.
  - Unselected / Unchecked: `[ ]`.
  - Used for batch operations and independent multi-item toggles.
- **Active Cursor Pointer:**
  - Cursor Glyph: `❯` (`\u276F`, Cyan bold).
  - Renders adjacent to the currently focused row or interactive option in the active pane.
  - Paired with bold text styling for the focused element.

### Master-Detail Focus State Machine (`PaneFocus`)
Interactive dual-pane views (such as `[2: Plan]`) maintain an explicit focus state:
- **Panel Border Focus Framing:**
  - Active panel border renders in bright cyan (`cyan()`), providing immediate pre-attentive focus framing.
  - Inactive panel border dims to muted dark gray (`darkGray()`).
  - Panel titles remain clean without textual annotations (e.g., `Plan` and `Plan Details`).
- `PaneFocus.MASTER`: Focus resides on the left master list of items.
  - Active cursor `❯` in the left pane is rendered in `cyan().bold()`.
- `PaneFocus.DETAIL`: Focus resides on the right detail pane's interactive options.
  - Inactive cursor `❯` in the left pane dims to `gray()`, and active cursor in the detail pane renders in `cyan().bold()`.

### Action & Outcome Label Alignment
- To prevent user confusion between machine engine states and operational intent, detail views display user-facing action labels (`Action: Migrate`, `Action: Adopt`, `Action: Discard`, `Action: In Sync`, `Action: Skipped`, `Action: Conflict`) strictly matching the master list badges.
- Internal reconciliation outcome enums (`CONVERGED`, `UNRESOLVED`) are reserved for machine serialization (`--json`) and are not exposed as raw text in interactive TUI views.

### Diagnostic & Warning Formatting
- Diagnostics and safety warnings in detail inspectors are formatted using human-readable semantic glyphs:
  - `⚠ <message>` for warnings and destructive confirmations.
  - `✖ <message>` for blocking errors.
- Raw machine enum identifiers (e.g., `[DIRECTORIES_DISCARDED]`) must not be displayed in TUI views.

### Keyboard Navigation & Focus Transitions
- **In `PaneFocus.MASTER`:**
  - `↑` / `↓` / `k` / `j`: Move selection across master list items.
  - `→` / `l`: Move focus to `PaneFocus.DETAIL` (only permitted when the selected item has interactive choices; `Tab` supported as fallback).
  - `Space`: Quick-cycle or trigger default resolution for conflict items.
  - `c`: Toggle visibility of collapsed in-sync items.
  - `a` / `Enter`: Advance to `[3: Apply]` when all items are resolved.
  - `q`: Quit application.
- **In `PaneFocus.DETAIL`:**
  - `↑` / `↓` / `k` / `j`: Move selection across available resolution options (`detailSelectedIndex`).
  - `Space` / `Enter`: Select the highlighted resolution option, updating `(●)` radio state and immediately recalculating dry-run actions.
  - `←` / `h`: Return focus to `PaneFocus.MASTER` (`Esc` supported as fallback).
  - `q`: Quit application.

### Dynamic Footer Shortcuts
The bottom footer cheat sheet adapts dynamically to the active `PaneFocus`:
- **Master Focus:**
  ```text
  ↑/↓/j/k: Select  ·  →/l: Details  ·  c: Toggle In Sync  ·  r: Refresh  ·  a: Apply  ·  1: Status  ·  q: Quit
  ```
- **Detail Focus:**
  ```text
  ↑/↓/j/k: Choose Option  ·  Space/Enter: Select  ·  ←/h: Back  ·  q: Quit
  ```

### Real-Time Recalculation & Visual Feedback
Selecting a resolution in the detail pane immediately:
1. Recalculates dry-run reconciliation actions for the relocation.
2. Updates the master list badge (e.g., from `⚠ [Conflict]` to `[Adopt]`, `[Discard]`, or `— [Skipped]`).
3. Refreshes summary counters (e.g., `⚡ N migrate`, `✔ N in sync`, `— N skipped`).
4. Re-evaluates readiness to apply changes.

### Detail Pane Multi-Line Wrapping & Hanging Indents
- Long conflict reasons and resolution descriptions are wrapped at word boundaries (~55–60 characters) with hanging indents (8-space indentation for descriptions).
- Preserves clean visual hierarchy and prevents horizontal text clipping against right panel borders.

### Selection Retention by Path Identity
- Interactive decision changes preserve cursor selection on the modified relocation by its unique source path.
- Resolving a conflict anchors the cursor to the resolved item across model recalculations and list re-evaluations, avoiding unwanted jumps to other items.

### Explicit Visibility Gating
- Items resolved to `[Skipped]` (e.g., *Leave source and target unmanaged*) represent active planning decisions and remain visible in the master list alongside other planned actions.
- Only steady-state `[In Sync]` items are collapsed by default when `c` toggle is inactive.
