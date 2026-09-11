package io.github.bigswlittlesw.homelight.cli;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.toolkit.app.InlineApp;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.elements.TreeElement;
import dev.tamboui.tui.InlineTuiConfig;
import dev.tamboui.widgets.tree.TreeNode;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationExecutor;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan;
import io.github.bigswlittlesw.homelight.reconcile.RelocationPlan;

import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.text;

/// Owns the inline TamboUI lifecycle for an `apply` execution.
final class InlineApplyProgress {
    private final ApplyApp app;
    private final Thread thread;

    private InlineApplyProgress(ReconciliationPlan plan, boolean noColor, boolean awaitingConfirmation) {
        app = new ApplyApp(plan, noColor, awaitingConfirmation);
        thread = Thread.ofVirtual().name("homelight-apply-ui").start(app::runApplication);
    }

    static Optional<InlineApplyProgress> start(ReconciliationPlan plan, boolean noColor, boolean awaitingConfirmation) {
        var progress = new InlineApplyProgress(plan, noColor, awaitingConfirmation);
        return progress.app.awaitStart() ? Optional.of(progress) : Optional.empty();
    }

    boolean awaitConfirmation() {
        return app.awaitConfirmation();
    }

    void started(RelocationPlan relocation, ReconciliationAction action) {
        app.update(() -> app.started(relocation, action));
    }

    void finished(RelocationPlan relocation, ReconciliationExecutor.ActionExecution action) {
        app.update(() -> app.finished(relocation, action));
    }

    void complete(ReconciliationExecutor.ExecutionResult result) {
        app.update(() -> app.complete(result));
        try {
            thread.join();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class ApplyApp extends InlineApp {
        private static final String[] SPINNER_FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

        private final ReconciliationPlan plan;
        private final boolean noColor;
        private final int actionCount;
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch confirmation = new CountDownLatch(1);
        private final AtomicReference<Throwable> startupFailure = new AtomicReference<>();
        private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1,
                Thread.ofVirtual().name("homelight-tui-", 0).factory());
        private final IdentityHashMap<ReconciliationAction, ActionState> states = new IdentityHashMap<>();
        private int spinnerFrame;
        private boolean awaitingConfirmation;
        private boolean confirmed;

        private ApplyApp(ReconciliationPlan plan, boolean noColor, boolean awaitingConfirmation) {
            this.plan = plan;
            this.noColor = noColor;
            actionCount = plan.actions().size();
            this.awaitingConfirmation = awaitingConfirmation;
        }

        private void runApplication() {
            try {
                super.run();
            } catch (Throwable exception) {
                startupFailure.set(exception);
                started.countDown();
            }
        }

        private boolean awaitStart() {
            try {
                return started.await(2, TimeUnit.SECONDS) && startupFailure.get() == null;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private void update(Runnable action) {
            runner().runOnRenderThread(action);
        }

        private boolean awaitConfirmation() {
            if (!awaitingConfirmation) {
                return true;
            }
            try {
                confirmation.await();
                return confirmed;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        @Override
        protected int height() {
            return Math.max(1, plan.relocations().size() + actionCount);
        }

        @Override
        protected InlineTuiConfig configure(int height) {
            return InlineTuiConfig.builder(height)
                    .tickRate(Duration.ofMillis(80))
                    .clearOnClose(false)
                    .scheduler(scheduler)
                    .build();
        }

        @Override
        protected void onStart() {
            started.countDown();
        }

        @Override
        protected Element render() {
            spinnerFrame++;
            if (!awaitingConfirmation) {
                return executionTree();
            }
            var question = noColor ? text("Apply this plan? [y/N]").bold()
                    : text("Apply this plan? [y/N]").yellow().bold();
            return column(executionTree(), question, text("y apply  n or q cancel").dim())
                    .onKeyEvent(event -> {
                        if (event.isChar('y')) {
                            awaitingConfirmation = false;
                            confirmed = true;
                            confirmation.countDown();
                        } else if (event.isChar('n') || event.isChar('q')) {
                            confirmation.countDown();
                            quit();
                        }
                        return dev.tamboui.toolkit.event.EventResult.HANDLED;
                    });
        }

        private void started(RelocationPlan relocation, ReconciliationAction action) {
            states.put(action, ActionState.RUNNING);
        }

        private void finished(RelocationPlan relocation, ReconciliationExecutor.ActionExecution action) {
            states.put(action.action(), ActionState.from(action.status()));
        }

        private void complete(ReconciliationExecutor.ExecutionResult result) {
            runner().schedule(() -> runner().runOnRenderThread(this::quit), Duration.ofMillis(160));
        }

        @Override
        protected void onStop() {
            scheduler.shutdownNow();
        }

        private Element executionTree() {
            var tree = new TreeElement<Style>();
            plan.relocations().forEach(relocation -> tree.add(treeRoot(relocation)));
            return tree
                    .highlightSymbol("")
                    .highlightStyle(Style.EMPTY)
                    .nodeRenderer(node -> text(node.label()).style(node.data()).ellipsis());
        }

        private TreeNode<Style> treeRoot(RelocationPlan relocation) {
            var configured = relocation.relocation();
            var root = TreeNode.of(configured.sourcePath().getFileName() + " → " + configured.targetPath().getFileName(),
                    noColor ? Style.EMPTY : Style.EMPTY.fg(Color.CYAN).bold()).expanded();
            for (var action : relocation.actions()) {
                var state = states.getOrDefault(action, ActionState.PENDING);
                root.add(TreeNode.of(actionLabel(action, state), actionStyle(state)).leaf());
            }
            return root;
        }

        private String actionLabel(ReconciliationAction action, ActionState state) {
            var marker = switch (state) {
                case PENDING -> "○";
                case RUNNING -> SPINNER_FRAMES[spinnerFrame % SPINNER_FRAMES.length];
                case COMPLETED -> "✓";
                case FAILED -> "✗";
            };
            return marker + " " + ApplyProgress.actionText(action);
        }

        private Style actionStyle(ActionState state) {
            if (noColor) {
                return Style.EMPTY;
            }
            return switch (state) {
                case PENDING -> Style.EMPTY.dim();
                case RUNNING -> Style.EMPTY.fg(Color.CYAN).bold();
                case COMPLETED -> Style.EMPTY.fg(Color.GREEN);
                case FAILED -> Style.EMPTY.fg(Color.RED).bold();
            };
        }

        private enum ActionState {
            PENDING,
            RUNNING,
            COMPLETED,
            FAILED;

            private static ActionState from(ReconciliationExecutor.ActionStatus status) {
                return switch (status) {
                    case PENDING -> PENDING;
                    case COMPLETED -> COMPLETED;
                    case FAILED -> FAILED;
                };
            }
        }
    }
}
