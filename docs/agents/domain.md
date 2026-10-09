# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase. Wherever a skill says ADRs or `docs/adr/`, read `docs/decisions.md` instead: this repo keeps its decisions there, not as ADR files.

## Before exploring, read these

- **`CONTEXT.md`** at the repo root: the domain words, users' words mapped to code names.
- **`docs/decisions.md`**: the current rules, grouped by theme. Read the themes that touch the area you're about to work in. Its archive lists superseded decisions; they are history, not rules.
- **`docs/architecture.md`**: packages, dependency direction and safety invariants.
- **`docs/tui-design.md`**: the current TUI rules, when working on the TUI.

## File structure

```
/
├── CONTEXT.md
├── docs/
│   ├── decisions.md
│   ├── architecture.md
│   └── tui-design.md
└── src/
```

## Use the glossary's vocabulary

When your output names a domain concept (in an issue title, a refactor proposal, a hypothesis, a test name), use the term as defined in `CONTEXT.md`. Don't drift to synonyms the glossary explicitly avoids.

If the concept you need isn't in the glossary yet, that's a signal: either you're inventing language the project doesn't use (reconsider) or there's a real gap (note it for `/domain-modeling`).

## Flag decision conflicts

If your output contradicts a current rule in `docs/decisions.md`, say so explicitly rather than silently overriding it:

> _Contradicts "One staging operation per target" (decisions.md), but worth reopening because…_

A new or changed decision goes into `docs/decisions.md` under its theme, as its "How to add" section says, not into a new ADR file.
