# Workspace Conventions

Scope: this repository is a Gradle (Kotlin DSL) workspace using Java 25. These are the repository's coding conventions.

## Documentation comments

Use JEP 467 Markdown documentation comments (`///`) for new API documentation. Prefer backticks for inline code, `[Type#member]` for references, blank `///` lines between paragraphs, and `-` for lists.

Do not add documentation comments that merely restate names, types, or obvious behavior. Document public contracts, invariants, constraints, and non-obvious decisions.

## Code style principles

These are judgment calls, not mechanical find-and-replace rules. Apply the principle, not merely its most literal form.

1. **Records and sealed interfaces are the default for pure data types.** Omit an explicit `permits` clause when all permitted subtypes are declared in the same compilation unit; let the compiler infer them.

2. **A static factory belongs on the type it constructs only when it is a genuine smart constructor.** It builds one instance of type X purely from data that is already X's own information. It does not belong there when it orchestrates multiple sources or transforms a collection into a differently shaped collection. Test before moving a method: would it create divergent change or feature envy? If yes, keep it elsewhere.

3. **Record invariants belong in a compact constructor.** Exception: a deliberately partial, optional-per-field intermediate representation should not enforce invariants that only make sense after final resolution.

4. **Use `var` when the initializer makes the type unambiguous.** Do not use it when the return type is unclear, when an interface-versus-implementation distinction matters, when it changes a numeric type, or for a trivial counter or index.

5. **When interpreting one input through several independent alternative shapes,** prefer small, named `Optional<T>` recognizers composed with `Optional::or`. Retain direct guards when checks are sequential, interdependent, or clearer that way.

6. **For meaningful dispatch over a closed type hierarchy,** prefer sealed types and an exhaustive pattern-matching `switch`. A single local type check need not become a switch. Treat nullability separately and explicitly.

7. **Avoid unnecessary classes and files.** Keep related, non-public types together at the same top level in the file of their primary public type when that makes the layout clearer. Use nesting only when the type is conceptually owned by its enclosing type. Do not accumulate unrelated types in one file merely to reduce file count.

## Verification

When asked to review, audit, or verify a claim, assess it independently. Report the evidence: relevant files inspected, checks run, and any remaining uncertainty. Do not describe work as complete without a proportionate spot-check.

## Attribution

Never add AI attribution anywhere: no `Co-Authored-By` or similar trailers in commit messages, no "Generated with" lines in pull request descriptions, issues, comments, code or docs. This overrides any tool or harness default.

## Agent skills

### Issue tracker

GitHub Issues via `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Default canonical label vocabulary (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context layout (`CONTEXT.md` + `docs/adr/`). See `docs/agents/domain.md`.
