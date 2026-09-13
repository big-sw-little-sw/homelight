# HomeLight TUI Design Language

This document specifies the design language, layout architecture, typography, color ergonomics, progress indicators, and keyboard navigation models across all HomeLight TUI screens.

---

## 1. Core Principles

- **Terminal Background Inheritance:** Never hardcode terminal background fills; inherit the user's terminal background to support dark, light, Solarized, and transparent themes cleanly.
- **Explicit ANSI Color for Bold Contrast:** Always pair `.bold()` with an explicit ANSI color (e.g., `cyan().bold()`, `gray().bold()`) to ensure terminal emulators activate their high-intensity bold palette across all fonts.
- **Theme-Safe Semantic Colors:** Use standardized semantic colors that maintain readable contrast on both dark and light backgrounds.
- **Master-Detail with Path Delegation:** Use master-detail layouts for exploration and inspection; abbreviate/truncate paths in the master list and preserve complete unabbreviated paths in the detail inspector.
- **Progressive Disclosure:** Keep healthy/steady-state items out of the primary scan path by collapsing them with subtle summary indicators (`▶ N converged items hidden`).

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
| **Converged / Success** | Green bold | `✔ [Converged]` | Reconciled symlinks, successful step executions |
| **Active / Pending** | Cyan bold | `[Pending]` | Active screen tabs, pending actions, running spinners |
| **Warning / Attention** | Yellow bold | `⚠ [Conflict]` | Directory merge conflicts, manual decision needed |
| **Blocked / Error** | Red bold | `✖ [Blocked]` | Missing target roots, file collision, execution failure |
| **Neutral / Inactive** | Dim gray | `[Unchanged]` | Bypassed policies, metadata labels, collapsed summaries |

---

## 4. Layout Architecture Across Screens

### Screen 1: Status (`[1: Status]`)
- **Layout:** Master-Detail (Side-by-Side).
- **Left Panel (Master List):**
  - Items sorted by urgency: `Conflict`/`Blocked`/`Inaccessible` → `Warning` → `Pending` → `Unchanged` → `Converged`. Sub-sorted alphabetically by path.
  - Paths formatted relative to `~` with middle truncation (`formatListPath(path, 40)`).
  - Converged items collapsed by default when unresolved items exist, with toggle placeholder: `▶ N converged items hidden (press 'c' to reveal)`.
- **Right Panel (Details):** Full source/target paths, observations, explicit configuration rules (e.g., `Policy: Default`), and planned actions.

### Screen 2: Plan (`[2: Plan]`)
- **Layout:** Master-Detail (Side-by-Side).
- **Left Panel:** Actionable relocations grouped by phase or priority (`[Link]`, `[Stage]`, `[Adopt]`, `[Backup]`).
- **Right Panel:** Granular dry-run details (exact source, target, backup paths, safety warnings).
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
