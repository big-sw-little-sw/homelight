# Lighten

Lighten moves directories out of a home directory to storage and leaves a link in each one's place. Its desired state for each managed directory is a real target directory and a source symlink to it.

Each term is the word users see (the user guide's "Words to know" and the screens), then the code's name for it. Use the user's word in the guide, the TUI and new docs.

## Configuration

**Relocation**:
One directory Lighten manages: a source, a target and its rules.
Code: `Relocation`; in the file, `RelocationFile` under `relocations`.
_Avoid_: mapping, migration, entry

**Source**:
Where programs look for the directory, usually in the home directory. After a move it is a link to the target.
Code: `Relocation.sourcePath`, `source-path`.

**Target**:
Where the directory's contents live, in storage.
Code: `Relocation.targetPath`, `target-path`; derived from `source-root` and `target-root` when absent (`derivedTarget`).
_Avoid_: destination

**Target root, source root**:
Where storage is, and the folder sources are usually in (default `~`). A relocation without a target keeps its place under the source root, inside the target root.
Code: `target-root`, `source-root` in `LightenFile`.

**Rule**:
A saved setting that decides what happens for one observed state of a relocation, every time. "Ask each time" is the default.
Code: `WhenOnlyTargetExists`, `WhenSourceAndTargetDirectoriesExist`, `WhenAdoptingTarget`; "Ask each time" is `PROMPT`.
_Avoid_: policy (in user-facing text)

**Ignored**:
A directory the user told Lighten to leave alone. Lighten plans nothing for it but still lists it.
Code: `ignored-source-paths`, `LightenConfiguration.ignoredSourcePaths`.
_Avoid_: excluded, skipped

## Planning and applying

**Plan**:
The steps Lighten would take for each relocation, made from what is on disk now and the rules. Making it changes nothing.
Code: `ReconciliationPlan` of `RelocationPlan`s; a step is a `ReconciliationAction`; made by `ReconciliationPlanner`.

**Choose**:
A relocation whose rule is "Ask each time" in its current state, so the plan needs a decision before it can be applied.
Code: `ReconciliationConflict`; badge `PlanBadge.CONFLICT`.
_Avoid_: conflict (in user-facing text)

**One-time choice**:
A decision for one relocation that holds for the next apply only. Checking again or applying forgets it; saving it makes it the rule.
Code: `DecisionChoice`, held by `LightenSession`.
_Avoid_: override, resolution

**Blocked**:
A relocation that cannot be done as things are, for example because a file is where a folder must go. Fixing the cause and checking again clears it.
Code: `ReconciliationAction.Blocked`; badge `PlanBadge.BLOCKED`.

**Review**:
The screen listing every step of the plan before anything changes. `y` applies exactly that plan.
Code: `ApplyModel.Reviewed`, `ReviewedExecution`.

**Apply**:
Carrying out a reviewed plan. Each step checks the disk is as planned; a difference stops it.
Code: `ReviewedExecution`, `ReconciliationExecutor`.
_Avoid_: execute, run (in user-facing text)

**Check again**:
Looking at the disk and configuration again and making a new plan (`r`).
Code: `LightenSession.refresh`.
_Avoid_: refresh, re-plan (in user-facing text)

**In sync**:
A relocation whose target is a real directory and whose source is the correct link to it. Planning again does nothing.
Code: `RelocationOutcome.CONVERGED`; badge `PlanBadge.IN_SYNC`.
_Avoid_: completed move, migrated, converged (in user-facing text)

**Left as is**:
A relocation deliberately not changed because its rule or choice says "Leave both as they are".
Code: `WhenSourceAndTargetDirectoriesExist.LEAVE_UNCHANGED`, `RelocationOutcome.UNCHANGED`; badge `PlanBadge.SKIPPED`.
_Avoid_: no-op, preserved

**Keep target**:
Treating an existing target directory as the one to keep. When the source is a directory too, it also needs a decision about the source: delete or archive.
Code: `ADOPT`, `ADOPT_TARGET`; badge `PlanBadge.ADOPT` when the source is deleted, `PlanBadge.LINK` when there is no source.
_Avoid_: adopt (in user-facing text), adopt source

**Archive**:
Moving the source into an archive folder, by default `.lighten-archive` beside the source, so it can be moved back. Opposed to delete, which removes it for good.
Code: `WhenAdoptingTarget.ARCHIVE_SOURCE`, `Relocation.archiveRoot`, `ReconciliationAction.ArchiveDirectory`; badge `PlanBadge.BACKUP`.
_Avoid_: backup

**Staging**:
A Lighten-owned folder on the target's filesystem, by default `.lighten-staging` beside the target, where a directory is copied and checked before it is put in place with a rename.
Code: `staging-root`, `Staging.kt`, `ReconciliationAction.MigrateDirectoryForPublication`.
_Avoid_: temporary directory, transaction journal

## Suggestions

**Suggestion list**:
A list of directories Browse suggests moving. The built-in list ships with Lighten; the user's own list (optional) is a file named in `suggestion-list`.
Code: `CandidateCatalog`; a suggestion is a candidate (`CandidateDefinition`); the user's list is the shared list (`CandidateSource.Kind.SHARED`, `LightenConfiguration.sharedList`), the built-in one `CandidateCatalog.BUNDLED`.
_Avoid_: candidate list (in user-facing text)

**Category, app**:
The two levels Browse groups suggestions under: a category such as Python, then the app such as uv.
Code: `CandidateDefinition.category`, `CandidateDefinition.app`.
_Avoid_: ecosystem, group
