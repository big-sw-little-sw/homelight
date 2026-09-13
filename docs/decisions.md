# HomeLight Design Decisions

This document records decisions that affect implementation direction. Add entries when a product or architecture choice is made, especially when an alternative is intentionally rejected.

## 2026-08-29: Use a library-oriented core

HomeLight will use a small presentation-independent reconciliation library with a thin Picocli CLI. It will not start as a framework-based application.

The core should remain usable by future CLI, GUI, and automation consumers.

## 2026-08-29: Keep integrations behind adapters

Configuration storage, Git, HTTP, and other external integrations belong behind narrow adapters. The reconciliation engine operates on desired state and inspected filesystem state, not on Stow, Git, HTTP, or a particular dotfile repository.

## 2026-08-29: Defer framework and plugin infrastructure

Dependency injection, runtime scanning, and a general plugin framework are deferred. They would add complexity to the safety-critical path and are not needed for the initial product.

## 2026-08-29: Design for native-image compatibility

GraalVM Native Image is a future aspiration. The project will favor explicit construction, standard Java APIs, isolated serialization, and minimal reflection. Native-image verification should be added before the architecture becomes difficult to change.

## 2026-09-07: Prefer guided CLI over desktop GUI

_Superseded by “Use a full-screen TUI as the primary human interface” below._

HomeLight will focus on a command-line interface with guided prompts rather than a desktop GUI (such as JavaFX).

HomeLight targets quota-constrained Linux workstations and remote environments where SSH and headless access are standard. Guided CLI workflows such as discovery in `init` retain a single-binary distribution with minimal GraalVM Native Image reachability friction.

Desktop GUI alternatives (JavaFX, Gluon Substrate, webview wrappers) are deferred because they introduce substantial build complexity, native graphics bindings, OS packaging overhead, and requirement for display forwarding over remote sessions.

## 2026-09-07: Combine non-interactive and guided CLI in a single binary

_Superseded by “Keep TUI and JSON automation in one binary without dual human presentation” below._

HomeLight will provide both non-interactive commands and guided prompts within the same executable.

The Picocli CLI layer is the primary entry point and routing mechanism. It supports automation through flags such as `--yes` and `--json`, and invokes prompts only for interactive workflows.

## 2026-09-07: Maintain a single Maven module with logical package boundaries

The project will remain a single Maven module with clear package boundaries (`domain`, `reconcile`, `fs`, `config`, `cli`) instead of splitting into a multi-module Maven build upfront.

For a solo developer, logical package boundaries provide clean architectural separation and decoupled unit testing without the build maintenance, multi-POM configuration, and refactoring friction of multi-module builds.

## 2026-09-07: Use plain Java 25 and targeted libraries instead of application frameworks

HomeLight will use plain Java 25 with targeted libraries (Picocli for command routing, JLine 3 / TamboUI for terminal interactions, SnakeYAML Engine / Jackson for serialization) rather than a full-stack application framework like Spring Boot 4 or Quarkus.

A filesystem utility does not require runtime dependency injection, classpath scanning, or server-oriented lifecycle management. Plain libraries ensure fast startup, low binary footprint, straightforward unit tests, and seamless GraalVM Native Image compilation.

## 2026-09-12: Use a full-screen TUI as the primary human interface

Running `homelight` starts one persistent full-screen TamboUI application. `init`, `config`, `plan`, `apply`, and `status` are screens within that application, and the corresponding command names deep-link to those screens.

The earlier guided inline CLI approach created separate short-lived terminal applications for plan display, conflict resolution, confirmation, and progress. That lifecycle made focus, rendering, and workflow continuity unreliable. A single application lifecycle and retained workflow state match the product's need to compare relocations, edit decisions, review an exact plan, and observe execution progress in place.

TamboUI remains the presentation toolkit because its high-level layout, widget, focus, and styling primitives allow a distinctive interface without rebuilding terminal infrastructure. It remains isolated behind the TUI adapter because its snapshot API is experimental.

## 2026-09-12: Keep TUI and JSON automation in one binary without dual human presentation

The full-screen TUI is the only human-oriented presentation. Existing guided, inline, and plain-text human renderers will be removed rather than maintained as a compatibility layer.

JSON commands remain prompt-free automation interfaces. `--json` bypasses TamboUI completely; responses use versioned contracts and stable exit codes. `apply --json` requires `--yes`, which confirms an already-resolved plan but never supplies missing policy decisions. A non-interactive invocation without `--json` fails clearly.

## 2026-09-12: Apply the exact reviewed plan

The TUI retains the exact structured plan shown during review. Immediately before application, HomeLight preflights the plan's expected filesystem state. State drift marks the plan stale and requires re-planning. HomeLight never recomputes and substitutes an unreviewed plan after the user confirms application.

## 2026-09-12: Establish a HomeLight-specific visual language

The TUI must be intentionally designed around HomeLight's relocation workflows. It will not use a generic dashboard-card composition, gratuitous gradients, excessive borders, decorative clutter, or canned interface copy. Hierarchy, typography, spacing, color, keyboard behavior, empty and failure states, and narrow-terminal behavior are part of the product contract.

The complete visual language, color ergonomics, progress indicators, and screen layouts are codified in [docs/tui-design.md](tui-design.md).

## 2026-09-08: Keep initial reconciliation stateless and directory-only

The initial planner manages directories only. A configured file source is blocked until file relocation has a complete, separately designed state model.

HomeLight does not persist an ownership registry in the initial implementation. It recognizes an already-correct configured symlink structurally, creates absent targets, and requires an explicit conflict resolution before adopting or replacing unknown existing state. This avoids recovery, staleness, and lifecycle complexity while preserving fail-closed behavior.

## How to add decisions

Use this format:

```markdown
## YYYY-MM-DD: Short decision title

Decision and rationale.

Alternatives considered, if relevant.
```
