package io.github.bigswlittlesw.homelight.cli;

import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.toolkit.app.InlineApp;
import dev.tamboui.toolkit.elements.TreeElement;
import dev.tamboui.tui.InlineTuiConfig;
import dev.tamboui.widgets.tree.TreeNode;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationPlan;
import io.github.bigswlittlesw.homelight.reconcile.RelocationPlan;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.text;

/// Renders an inspectable reconciliation plan in an interactive terminal.
final class InlinePlanView {
    private InlinePlanView() {
    }

    static boolean show(ReconciliationPlan plan) {
        var app = new PlanApp(plan);
        var thread = Thread.ofVirtual().name("homelight-plan-ui").start(app::runApplication);
        if (!app.awaitStart()) {
            return false;
        }
        try {
            thread.join();
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static final class PlanApp extends InlineApp {
        private final ReconciliationPlan plan;
        private final CountDownLatch started = new CountDownLatch(1);
        private final AtomicReference<Throwable> startupFailure = new AtomicReference<>();
        private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1,
                Thread.ofVirtual().name("homelight-plan-ui-", 0).factory());

        private PlanApp(ReconciliationPlan plan) {
            this.plan = plan;
        }

        private void runApplication() {
            try {
                run();
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

        @Override
        protected int height() {
            return Math.max(3, plan.relocations().size() * 3 + 3);
        }

        @Override
        protected InlineTuiConfig configure(int height) {
            return InlineTuiConfig.builder(height).clearOnClose(false).scheduler(scheduler).build();
        }

        @Override
        protected void onStart() {
            started.countDown();
            runner().schedule(() -> runner().runOnRenderThread(this::quit), Duration.ofMillis(150));
        }

        @Override
        protected dev.tamboui.toolkit.element.Element render() {
            var tree = new TreeElement<Style>();
            plan.relocations().forEach(relocation -> tree.add(node(relocation)));
            var heading = plan.hasBlockedActions() ? "Plan cannot be applied" : "Plan";
            var footer = plan.hasBlockedActions() ? "Apply is unavailable." : "No changes have been made.";
            return column(text(heading).bold(), tree.highlightSymbol("").highlightStyle(Style.EMPTY)
                    .nodeRenderer(item -> text(item.label()).style(item.data()).ellipsis()), text(footer).dim());
        }

        private TreeNode<Style> node(RelocationPlan relocation) {
            var configured = relocation.relocation();
            var root = TreeNode.of(configured.sourcePath().getFileName() + " → " + configured.targetPath().getFileName(),
                    Style.EMPTY.fg(Color.CYAN).bold()).expanded();
            root.add(TreeNode.of(label(relocation), style(relocation)).leaf());
            return root;
        }

        private static String label(RelocationPlan relocation) {
            var blocked = relocation.actions().stream().filter(ReconciliationAction.Blocked.class::isInstance)
                    .map(ReconciliationAction.Blocked.class::cast).findFirst();
            if (blocked.isPresent()) {
                return "Unavailable: " + blocked.orElseThrow().reason();
            }
            if (relocation.actions().stream().anyMatch(ReconciliationAction.ReplaceDirectoryWithSymlink.class::isInstance)) {
                return "Adopt target and replace source with a link";
            }
            if (relocation.actions().stream().anyMatch(ReconciliationAction.DeleteDirectory.class::isInstance)) {
                return "Warning: permanently discard both directory trees";
            }
            if (relocation.actions().stream().anyMatch(ReconciliationAction.NoOp.class::isInstance)) {
                return "Already configured";
            }
            return switch (relocation.outcome()) {
                case CONVERGED -> "Will converge after apply";
                case UNCHANGED -> "Left unchanged";
                case UNRESOLVED -> "Needs a decision";
            };
        }

        private static Style style(RelocationPlan relocation) {
            return relocation.outcome() == io.github.bigswlittlesw.homelight.reconcile.RelocationOutcome.CONVERGED
                    ? Style.EMPTY.fg(Color.GREEN) : Style.EMPTY.fg(Color.YELLOW);
        }

        @Override
        protected void onStop() {
            scheduler.shutdownNow();
        }
    }
}
