package io.github.bigswlittlesw.homelight.application;

import io.github.bigswlittlesw.homelight.config.*;
import io.github.bigswlittlesw.homelight.fs.*;
import io.github.bigswlittlesw.homelight.reconcile.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

// THROWAWAY replacement compiled instead of the production session. All observations are invented.
// Uses the real pure planner and real view models; never loads config or invokes an executor.
public class HomeLightSession {
    private Screen screen;
    private StatusModel status;
    private PlanModel plan;
    private ApplyModel apply = new ApplyModel.Idle();
    private final Map<Path, DecisionChoice> drafts = new HashMap<>();
    private final Set<Path> completed = new HashSet<>();
    private final Map<Path, PathObservation> changedPaths = new HashMap<>();
    private final List<Relocation> saved = new ArrayList<>();
    private int scenario;
    private boolean drift, blocked;
    private long next;
    public String notice = "Inspect conflict-cache, then choose what to do with its two directories.";
    public boolean sharedAvailable = true;
    public boolean[] candidates = {false, false, false};
    public String root = "/home/demo", target = "/mnt/storage", sharedPath = "/net/team/candidates.txt";

    public HomeLightSession(Path ignored, Screen screen) { this.screen = screen; reset(0); }
    public HomeLightSession(Path ignored, Screen screen, long delay) { this(ignored, screen); }
    public HomeLightSession(Path ignored, Screen screen, StatusWorkflow s, PlanWorkflow p, ConfigurationLoader c) { this(ignored, screen); }
    public Screen activeScreen() { return screen; }
    public Path configPath() { return Path.of(scenario == 4 ? "/tmp/explicit-demo.yaml" : "/home/demo/.homelight.yaml"); }
    public StatusModel statusModel() { return status; }
    public PlanModel planModel() { return plan; }
    public ApplyModel applyModel() { return apply; }
    public boolean isApplying() { return apply instanceof ApplyModel.Running; }
    public Map<String, String> overrides() { return Map.of(); }
    public String scenarioName() { return new String[]{"Returning user", "Preflight rejection", "Partial execution", "Missing default config", "Missing explicit config"}[scenario]; }
    public void setActiveScreen(Screen value) {
        if (isApplying()) return;
        if (value != Screen.APPLY && apply instanceof ApplyModel.Confirmation) apply = new ApplyModel.Idle();
        screen = value;
    }
    public boolean hasConflicts() { return plan instanceof PlanModel.Configured p && p.plan().hasConflicts(); }
    public boolean isPlanReady() { return !blocked && !isApplying() && !(apply instanceof ApplyModel.Result) && plan instanceof PlanModel.Configured p && !p.plan().hasConflicts() && !p.plan().hasBlockedActions(); }
    public void refresh() {
        if (isApplying()) return;
        if (apply instanceof ApplyModel.Result result && !result.succeeded()) blocked = true;
        apply = new ApplyModel.Idle();
        if (screen == Screen.APPLY) screen = Screen.PLAN;
        rebuild();
        notice = blocked ? "Fresh plan blocked: external obstruction remains. Use ! to reset the fixture." : "Replanned from simulated current state. Previous results cleared.";
    }
    public void resolveDecision(Relocation relocation, DecisionChoice choice) {
        if (isApplying() || apply instanceof ApplyModel.Result || blocked) return;
        drafts.put(relocation.sourcePath(), choice);
        apply = new ApplyModel.Idle(); rebuild();
        notice = "Draft: " + choice.label() + ". Saved policy unchanged.";
    }
    public boolean requestApply() {
        if (!isPlanReady()) { notice = blocked ? "Recovery is blocked by the simulated obstruction." : "Resolve the conflict before reviewing."; return false; }
        apply = new ApplyModel.Confirmation(((PlanModel.Configured) plan).plan()); screen = Screen.APPLY; return true;
    }
    public void cancelApply() { if (apply instanceof ApplyModel.Confirmation) { apply = new ApplyModel.Idle(); screen = Screen.PLAN; } }
    public CompletableFuture<Void> confirmApply(Executor ignored) { return confirmApply(); }
    public CompletableFuture<Void> confirmApply() {
        if (!(apply instanceof ApplyModel.Confirmation c) || !c.plan().hasChanges()) return CompletableFuture.completedFuture(null);
        var steps = c.plan().relocations().stream().flatMap(r -> r.actions().stream().map(a -> new ApplyModel.Step(r, a, ApplyModel.StepStatus.PENDING, "Not run"))).toList();
        if (scenario == 1) {
            drift = true;
            apply = new ApplyModel.Result(c.plan(), steps, Optional.empty(), List.of("SIMULATED preflight rejection: target appeared after review. ZERO mutations. Replan to inspect the new conflict."), true);
            rebuild();
        } else { apply = new ApplyModel.Running(c.plan(), steps); next = 0; advance(); }
        return CompletableFuture.completedFuture(null);
    }
    public void awaitExecution() { }

    public void advance() {
        if (!(apply instanceof ApplyModel.Running run) || System.currentTimeMillis() < next) return;
        next = System.currentTimeMillis() + 1100;
        var steps = new ArrayList<>(run.steps());
        for (int i = 0; i < steps.size(); i++) {
            var step = steps.get(i);
            if (step.status() == ApplyModel.StepStatus.RUNNING) {
                simulate(step.action());
                steps.set(i, new ApplyModel.Step(step.relocation(), step.action(), ApplyModel.StepStatus.COMPLETED, "Simulated completion; disk unchanged"));
            }
        }
        for (var relocation : run.plan().relocations()) {
            if (steps.stream().filter(s -> s.relocation() == relocation).allMatch(s -> s.status() == ApplyModel.StepStatus.COMPLETED) && relocation.outcome() == RelocationOutcome.CONVERGED) completed.add(relocation.relocation().sourcePath());
        }
        long changed = steps.stream().filter(s -> s.action().mutatesFilesystem() && s.status() == ApplyModel.StepStatus.COMPLETED).count();
        for (int i = 0; i < steps.size(); i++) {
            var step = steps.get(i);
            if (step.status() != ApplyModel.StepStatus.PENDING) continue;
            if (!step.action().mutatesFilesystem()) { steps.set(i, new ApplyModel.Step(step.relocation(), step.action(), ApplyModel.StepStatus.COMPLETED, "Unchanged")); continue; }
            if (scenario == 2 && changed >= 4) {
                steps.set(i, new ApplyModel.Step(step.relocation(), step.action(), ApplyModel.StepStatus.FAILED, "SIMULATED obstruction at " + step.action().path() + ". Earlier completed actions remain changed; later actions did not run. Inspect current observations before replanning."));
                finish(run.plan(), steps); return;
            }
            steps.set(i, new ApplyModel.Step(step.relocation(), step.action(), ApplyModel.StepStatus.RUNNING, "Simulating, no filesystem mutation"));
            apply = new ApplyModel.Running(run.plan(), steps); return;
        }
        finish(run.plan(), steps);
    }
    private void finish(ReconciliationPlan reviewed, List<ApplyModel.Step> steps) {
        var execution = new ReconciliationExecutor.ExecutionResult(reviewed.relocations().stream().map(r -> new ReconciliationExecutor.RelocationExecution(r,
                steps.stream().filter(s -> s.relocation() == r).map(s -> new ReconciliationExecutor.ActionExecution(s.action(), switch (s.status()) { case COMPLETED -> ReconciliationExecutor.ActionStatus.COMPLETED; case FAILED -> ReconciliationExecutor.ActionStatus.FAILED; default -> ReconciliationExecutor.ActionStatus.PENDING; }, s.message())).toList())).toList());
        apply = new ApplyModel.Result(reviewed, steps, Optional.of(execution), List.of(), false);
        rebuild();
    }
    private void simulate(ReconciliationAction action) {
        switch (action) {
            case ReconciliationAction.CreateDirectory a -> changedPaths.put(a.path(), observation(PathState.DIRECTORY));
            case ReconciliationAction.DeleteDirectory a -> changedPaths.put(a.path(), observation(PathState.ABSENT));
            case ReconciliationAction.ArchiveDirectory a -> { changedPaths.put(a.path(), observation(PathState.ABSENT)); changedPaths.put(a.target(), observation(PathState.DIRECTORY)); }
            case ReconciliationAction.MigrateDirectoryForPublication a -> changedPaths.put(a.target(), observation(PathState.DIRECTORY));
            case ReconciliationAction.CopyDirectory a -> changedPaths.put(a.target(), observation(PathState.DIRECTORY));
            case ReconciliationAction.CreateSymlink a -> changedPaths.put(a.path(), new PathObservation(PathState.SYMLINK, Optional.of(a.target()), true));
            case ReconciliationAction.ReplaceDirectoryWithSymlink a -> changedPaths.put(a.path(), new PathObservation(PathState.SYMLINK, Optional.of(a.target()), true));
            case ReconciliationAction.ReplaceSymlink a -> changedPaths.put(a.path(), new PathObservation(PathState.SYMLINK, Optional.of(a.target()), true));
            default -> { }
        }
    }
    public void reset(int value) {
        scenario = value; drafts.clear(); completed.clear(); changedPaths.clear(); saved.clear(); drift = blocked = false; candidates = new boolean[3]; sharedAvailable = true;
        apply = new ApplyModel.Idle(); screen = Screen.STATUS;
        if (scenario < 3) {
            String[] names = {"conflict-cache", "discard-cache", "stage-cache", "new-cache", "linked-cache", "unmanaged-cache"};
            for (int i = 0; i < names.length; i++) saved.add(new Relocation(Path.of("/home/demo/.cache/" + names[i]), Path.of("/mnt/storage/" + names[i]),
                    i == 1 ? Optional.of(WhenSourceAndTargetDirectoriesExist.DISCARD) : i == 5 ? Optional.of(WhenSourceAndTargetDirectoriesExist.LEAVE_UNCHANGED) : Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.of(Path.of("/mnt/archive")), Optional.of(Path.of("/mnt/staging"))));
        }
        notice = "Same fixture in both views. v switches views; ? explains the comparison."; rebuild();
    }
    public void saveCandidates() {
        saved.clear();
        String[] names = {"manual-cache", "build-cache", "team-cache"};
        for (int i = 0; i < 3; i++) if (candidates[i]) saved.add(new Relocation(Path.of(root, names[i]), Path.of(target, names[i])));
        notice = "Saved " + saved.size() + " explicitly selected relocations IN MEMORY. Nothing applied.";
        rebuild(); screen = Screen.STATUS;
    }
    public List<Relocation> savedRelocations() { return List.copyOf(saved); }
    public Optional<DecisionChoice> draft(Path path) { return Optional.ofNullable(drafts.get(path)); }
    public String savedPolicy(Path path) {
        return saved.stream().filter(r -> r.sourcePath().equals(path)).findFirst().flatMap(Relocation::whenSourceAndTargetDirectoriesExist).map(Object::toString).orElse("Ask when both directories exist");
    }
    private Relocation effective(Relocation r) {
        var choice = drafts.get(r.sourcePath());
        if (choice == null) return r;
        var both = switch (choice) { case LEAVE_UNCHANGED -> WhenSourceAndTargetDirectoriesExist.LEAVE_UNCHANGED; case DISCARD_BOTH -> WhenSourceAndTargetDirectoriesExist.DISCARD; default -> WhenSourceAndTargetDirectoriesExist.ADOPT; };
        return new Relocation(r.sourcePath(), r.targetPath(), Optional.of(both), r.whenOnlyTargetExists(),
                Optional.of(choice == DecisionChoice.ADOPT_AND_ARCHIVE_SOURCE ? WhenAdoptingTarget.ARCHIVE_SOURCE : WhenAdoptingTarget.DISCARD_SOURCE), r.sourceArchiveRoot(), r.stagingRoot());
    }
    private PathObservation observation(PathState state) { return new PathObservation(state, Optional.empty(), false); }
    private List<RelocationState> states(boolean withDraft) {
        return saved.stream().map(original -> {
            var r = withDraft ? effective(original) : original;
            String name = r.sourcePath().getFileName().toString();
            boolean linked = completed.contains(r.sourcePath()) || name.equals("linked-cache");
            var src = linked ? new PathObservation(PathState.SYMLINK, Optional.of(r.targetPath()), true) : observation(name.equals("new-cache") ? PathState.ABSENT : PathState.DIRECTORY);
            var dst = observation(linked || name.contains("conflict") || name.contains("discard") || name.contains("unmanaged") || drift && name.contains("stage") ? PathState.DIRECTORY : PathState.ABSENT);
            src = changedPaths.getOrDefault(r.sourcePath(), src);
            dst = changedPaths.getOrDefault(r.targetPath(), dst);
            return new RelocationState(r, src, dst, r.sourceArchiveRoot().map(p -> new RelocationState.ArchiveDestination(p.resolve("home/demo/.cache/" + name), observation(PathState.ABSENT))));
        }).toList();
    }
    private void rebuild() {
        if (saved.isEmpty()) { status = scenario == 4 ? new StatusModel.Invalid(configPath(), "Explicit configuration does not exist (fixture)") : new StatusModel.Unconfigured(configPath()); plan = new PlanModel.Unconfigured(configPath()); return; }
        var planner = new ReconciliationPlanner();
        var current = states(false); var base = planner.plan(current);
        var statusItems = new ArrayList<RelocationStatusItem>();
        for (int i = 0; i < current.size(); i++) { var s = current.get(i); statusItems.add(new RelocationStatusItem(s.relocation(), s.source(), s.target(), base.relocations().get(i), s.source().sourceStateForTarget(s.relocation().targetPath()))); }
        status = new StatusModel.Configured(configPath(), Path.of(target), base, statusItems, StatusSummary.from(statusItems));
        var chosen = states(true); var proposed = planner.plan(chosen); var planItems = new ArrayList<PlanRelocationItem>();
        for (int i = 0; i < chosen.size(); i++) { var s = chosen.get(i); var p = proposed.relocations().get(i); planItems.add(new PlanRelocationItem(s.relocation(), s.source(), s.target(), p, s.source().sourceStateForTarget(s.relocation().targetPath()), new PlanWorkflow().availableResolutions(s.relocation(), s, p))); }
        plan = new PlanModel.Configured(configPath(), Path.of(target), proposed, planItems, PlanSummary.from(planItems));
    }
}
