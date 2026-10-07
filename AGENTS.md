# Workspace Conventions

Scope: this repository is a Kotlin and Gradle (Kotlin DSL) workspace targeting JVM 25 and GraalVM native Linux binaries. These are the repository's coding conventions.

## Documentation comments

Use KDoc (`/** */`) with Markdown for API documentation. Prefer backticks for inline code, `[Type.member]` for references, blank lines between paragraphs, and `-` for lists.

Do not add documentation comments that merely restate names, types, or obvious behavior. Document public contracts, invariants, constraints, and non-obvious decisions.

## Code style principles

These are judgment calls, not mechanical find-and-replace rules. Apply the principle, not merely its most literal form.

1. **`data class` with `val` properties is the default for pure data types.** Use a sealed interface or sealed class for a closed hierarchy.

2. **A factory belongs on the type it constructs only when it is a genuine smart constructor.** It builds one instance of type X purely from data that is already X's own information, as a companion `of`/`from` or a top-level function next to X. It does not belong there when it orchestrates multiple sources or transforms a collection into a differently shaped collection. Test before moving a function: would it create divergent change or feature envy? If yes, keep it elsewhere.

3. **Invariants belong in an `init` block,** or in a private constructor behind a factory when construction must normalize or can fail. Exception: a deliberately partial, nullable-per-field intermediate representation should not enforce invariants that only make sense after final resolution.

4. **Use nullable types, not `Optional`.** Prefer `?.`, `?:`, `let` and early returns, but do not chain them into puzzles. Do not use `!!` without a comment saying why the value cannot be null.

5. **Prefer immutability.** Use `val` by default. Expose read-only `List`/`Map`/`Set` in APIs and never a mutable collection that callers could change. When a type keeps a collection it was given, copy it with `toList()`, `toSet()` or `toMap()`, not `java.util.List.copyOf`; the read-only type is the guarantee, so tests do not cast to `MutableList` to prove immutability. Derive values with `copy`. No out-parameters: functions return their results; accumulators stay local to the function that builds them. In the TUI, a line builder returns `List<Line>`, or a small `Anchored(lines, anchor)` when it also needs an anchor, instead of filling a `MutableList<Line>`.

6. **When interpreting one input through several independent alternative shapes,** prefer small, named recognizers that return `T?`, combined with `?:`. Retain direct guards when checks are sequential, interdependent, or clearer that way.

7. **For meaningful dispatch over a closed type hierarchy,** use a sealed type and an exhaustive `when` with no `else` branch, so a new subtype is a compile error. A single local type check need not become a `when`. Treat nullability separately and explicitly.

8. **Use default and named arguments instead of overloads.** Write an extension function only when it reads as a domain operation on its receiver, not to scatter a type's behavior across files. Prefer top-level functions to an `object` that only holds utilities.

9. **Keep visibility narrow.** Default to `private` or `internal`. Do not mark a class or member `open` unless something subclasses it. Do not use `@JvmStatic`, `@JvmField`, `@JvmName`, `@JvmRecord` or hand-written Java-style accessors unless a Java or JVM consumer needs them: `main`, picocli-annotated fields, or a Native Image constraint. Say which in a comment.

10. **Use Kotlin's string functions.** Where exact whitespace semantics matter for validation, use the documented Java-semantics helpers in `JavaStrings.kt` rather than adding new ones.

11. **Make concurrency ownership explicit.** Each thread, executor, lock, and piece of shared mutable state has one clear owner that starts and stops it. Coroutines are not adopted yet (issue #10 decides); keep the existing threads and locks.

12. **Avoid unnecessary classes and files.** Kotlin allows several top-level declarations per file: keep related, non-public types and functions in the file of their primary public type when that makes the layout clearer. Nest a type only when its enclosing type conceptually owns it. Do not accumulate unrelated declarations in one file merely to reduce file count.

13. **Keep Native Image builds reflection-free.** Never add `kotlin-reflect`. Prefer compile-time serialization (kotlinx.serialization) or streaming parsers over reflection-based mapping.

## User guide

`docs/user-guide.md` is end-user documentation shown inside the app. Plain language, names as shown on screen, no internal names or history. Any ticket that changes what users see updates the guide in the same PR, and the walkthrough covers the guide diff.

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
