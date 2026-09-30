# Directory permission investigation handoff

Date: 2026-09-24 (America/Los_Angeles). Baseline: `018cb0e`.
Scope: [#25](https://github.com/big-sw-little-sw/homelight/issues/25).
The coordinator's ticket comment and [#11](https://github.com/big-sw-little-sw/homelight/issues/11)
were read, including comments. #11 integration work was not started.

## Result and decision required

Confirmed on this host: staged relocation replaces restrictive directory modes
with creation defaults. An empty `0700` source published as `0755`; nested
`0710` and empty `0500` directories also published as `0755`. The shared copier
already uses defaults while populating staging. Atomic publication is not the
cause.

**No production correction was made.** The requested policy stop applies:
existing documentation and tests do not establish a POSIX preservation contract
or specify what to do when permission operations are unsupported. The external
proposal's mirroring requirement is not an adopted HomeLight guarantee.

Coordinator decision: **Should staged relocation require preservation of the
nine POSIX directory permission bits, refusing publication when either endpoint
cannot support it, or should relocation remain allowed there with provider
defaults and an explicit limitation?** Recommend requiring preservation and
failing before publication when it cannot be established. This is a proposed
policy, not an implemented or accepted contract. It narrows platform behavior
and therefore was not selected implicitly.

The new tests characterize current behavior, including the deficiency. Passing
tests do **not** mean permissions are now preserved. Change the directory-mode
expectations to preservation assertions after the policy is decided. #25 stays
open for coordinator review and any authorized correction.

## Existing contract and evidence

Read repository `AGENTS.md`, `CONTEXT.md`, `docs/agents/issue-tracker.md`,
`docs/decisions.md`, relevant product-spec/architecture sections,
[homefree-comparison.md](homefree-comparison.md), both tickets, and these code paths:

- [ReconciliationExecutor](../../src/main/java/io/github/bigswlittlesw/homelight/reconcile/ReconciliationExecutor.java):
  `migrateDirectoryForPublication`, `CopyVisitor`, `verifyCopy`, cleanup, source
  replacement, action guards and execution-result handling.
- [ReconciliationPlanner](../../src/main/java/io/github/bigswlittlesw/homelight/reconcile/ReconciliationPlanner.java),
  action/plan types and
  [PathInspector](../../src/main/java/io/github/bigswlittlesw/homelight/fs/PathInspector.java).
- Existing executor and CLI apply tests, including publication, conservative
  stale cleanup, per-action drift, failed recovery and repeat application.

There is no `docs/adr/` directory in this checkout; the existing decisions are in
`docs/decisions.md`.

| Property | Existing behavior / guarantee boundary |
| --- | --- |
| Directory POSIX permissions | Source modes are neither read, copied nor verified. Root, nested and empty directories use `createDirectory`/`createDirectories` without attributes. No explicit preservation promise was found. |
| Staging accessibility | Staging root, operation directory and copy root use creation defaults. UUID names and locks establish operation bookkeeping; they do not impose owner-only access. Existing staging ancestors are not chmodded. |
| Regular files | `Files.copy(..., NOFOLLOW_LINKS)` without `COPY_ATTRIBUTES`. Verification checks regular-file kind and size, not bytes or modes. `0600` data and `0700` executable files retained their modes on this host. This is provider behavior, not a portable metadata guarantee. |
| Symlinks | Copied as links, without following descendants; literal link destinations are verified. Relative, dangling and absolute external-directory links retained their values in the fixtures. Link modes/metadata have no explicit preservation guarantee. |
| ACLs | No explicit ACL copy, comparison or preservation promise. Equal POSIX bits do not establish equivalent ACL access. |
| Ownership | No explicit owner/group preservation or comparison. Matching permission bits would not establish matching user/group identities. No ownership-feature work was performed. |
| Timestamps / extended attributes | No explicit directory preservation or verification. Incidental provider behavior must not be promoted to a guarantee. |
| Special mode bits | setuid, setgid and sticky bits are outside Java's nine `PosixFilePermission` bits; no preservation promise was found or tested. |

Java 25 specifies that ordinary
[`Files.copy`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/Files.html#copy(java.nio.file.Path,java.nio.file.Path,java.nio.file.CopyOption...))
need not copy attributes; even `COPY_ATTRIBUTES` is platform dependent.
[`PosixFileAttributeView`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/attribute/PosixFileAttributeView.html)
exposes the nine owner/group/other read, write and execute bits.
[`Files` permission operations](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/file/Files.html#getPosixFilePermissions(java.nio.file.Path,java.nio.file.LinkOption...))
can throw `UnsupportedOperationException` when that view is absent.

## Controlled tests and findings

Added [StagedPermissionTest](../../src/test/java/io/github/bigswlittlesw/homelight/reconcile/StagedPermissionTest.java),
nine test invocations. All fixtures are inside JUnit `@TempDir`; restrictive
populated fixtures restore owner access in `finally` for cleanup. No real home
data, external users, concurrency, or privileged filesystem operations were used.

| Case | Observed result |
| --- | --- |
| Empty source root, `0700` and `0500` | Successful publication/linking, target gets creation defaults (`0755` here). |
| Root `0700`, nested `0710`, empty nested `0500` | All published directory modes equal a separately created default-mode control. At the publication callback, the source is still a real directory with its original modes. |
| Regular files and symlinks | File contents/modes retained in tested cases; link values unchanged, external fixture directory/content/mode untouched. Successful repeat plans only `NoOp`. |
| Copying under read-only `0500` source root and nested directory | The actual private `CopyVisitor`, invoked synchronously, creates writable default-mode staged directories and copies the child. No source chmod occurs. |
| Unreadable nested directory (`0000`) | Migration fails, replacement remains pending, target absent, source modes/content retained. New operation is cleaned; unrelated staging entry survives. |
| Unwritable staging (`0500`) | Fails before publication; source and unrelated staging entry survive, staging mode unchanged. |
| Populated read-only source (`0500`) | Publication succeeds, source deletion fails, result is `FAILED_RECOVERY`. Both copies retain contents in this single-file fixture; replan requires the normal both-directories decision. Source is not chmodded. |
| Restrictive stale copy (`0500`) | Cleanup cannot remove its child; new migration fails with source intact and target absent. Undeletable stale contents and mode remain. |
| Unsupported POSIX operations | A temporary ZIP filesystem exposes no POSIX view; get/set operations throw `UnsupportedOperationException`. The existing shared copier still copies contents and empty directories because it never calls those operations. |

The staging test uses reflection only to reach the existing private visitor; it
reproduces the executor's operation/copy creation calls. It measures actual
copier behavior without adding a production seam. It is **not** a live observation
of the entire locked staging operation or a cross-user access test. Operation
creation behavior is additionally established by reading the production calls.
The fixtures' private ancestors prevent the observed mode widening from proving
that another user actually obtained access. Such access depends on ancestor
permissions, identities and ACLs.

The ZIP case exercises the shared `CopyDirectory` path, **not** staged atomic
publication on a non-POSIX platform. Windows/NFS behavior cannot be inferred from it.

## Failure and correction constraints

Production code is unchanged, including this sequence:

1. Guard source-directory/target-absent states and target-local staging.
2. Establish real directories, conservatively clean stale staging, probe atomic move.
3. Create operation bookkeeping, acquire lock, copy and verify.
4. Recheck action states, atomically publish, clean the operation.
5. In a separate guarded action, prepare the source link, delete the source tree,
   then atomically install the link.

Existing stale-cleanup checks include operation naming, marker/lock shape,
allowed entries, absence of symlinks, target absence, file-store match and lock
availability. These checks were not broadened. Cleanup is not transactional:
deletion can remove earlier entries before a restrictive child fails. Likewise,
source deletion can be partial in a larger tree after publication; the single-file
read-only test does not establish rollback for arbitrary trees.

A future correction cannot simply chmod copied directories on entry: a `0500`
destination can prevent copying children. Applying final modes also affects
cleanup after verification/publication failures. A bounded correction should
keep the operation private during copying, apply/verify final directory modes
before the atomic move, and handle cleanup of restrictive operation-owned copies
without changing source permissions or relaxing stale-ownership checks. Preserve
the existing guard and publication order. No such changes are authorized by this
handoff alone.

Currently no directory permission operation is attempted, so there is no
existing setter-failure recovery path to validate. A future implementation needs
targeted read/set/verification failure tests. `execute` catches `IOException` and
`IllegalStateException`, not general `UnsupportedOperationException`; merely
adding permission setters could therefore bypass normal failed-action reporting.
Any future unsupported-operation handling should be confined to the new
permission boundary rather than changing unrelated executor failure semantics.

## Verification

Environment: macOS `26.5.2`, `aarch64`, unprivileged UID 501, local temporary
filesystem, process umask `022`. Tests ran with Temurin **25.0.3+9**; Surefire XML
confirms the Java home/version. Bare `mvn` selected Homebrew Java 26, so every
test invocation explicitly selected the already installed Java 25. No toolchain,
Maven configuration or dependency change was made.

Commands used:

```sh
env JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home mvn -o -Dtest=ReconciliationExecutorTest,ApplyCommandTest test
env JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home mvn -o -Dtest=StagedPermissionTest test
env JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home mvn -o -Dtest=StagedPermissionTest,ReconciliationExecutorTest,ApplyCommandTest test
env JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home mvn -o clean test
git diff --check
```

- Existing focused tests: **22 passed**, no failures/errors/skips.
- Initial minimal preservation assertion: **1 failure**, expected `0700`, actual
  `0755`, with an otherwise successful empty-root relocation. The original method
  `preservesRestrictiveSourceRootMode` was replaced by explicitly named current-
  behavior characterizations after identifying the policy boundary. Its failing
  assertion was `assertEquals(PosixFilePermissions.fromString("rwx------"),
  Files.getPosixFilePermissions(target))`.
- Final focused run: **31 passed**, no failures/errors/skips.
- Java 25 clean suite: **243 passed** (234 baseline + 9 new), no failures/errors/skips.
- `git diff --check` and explicit `--no-index --check` checks of the two new,
  untracked deliverables passed.

No tests were disabled. POSIX cases explicitly skip where the view is absent;
denial cases explicitly skip if the process can bypass the intended denial.
Neither condition caused a skip here. Existing tests have their own platform
assumptions, so this is not a claim that the full suite is portable.

Untested: Linux, Windows, NFS/remote mounts, cross-filesystem relocation,
cross-user access, inherited ACLs, owner/group changes, timestamps, xattrs,
special mode bits and concurrent external mutation. Copy verification remains
size/structure/link-value based, not byte equality or a locked source snapshot.

Only this handoff and the new test file were added. Unrelated local files were
preserved. No commit or push. Stop here; coordinator review/policy resolution
precedes any permission correction or #11 integration session.
