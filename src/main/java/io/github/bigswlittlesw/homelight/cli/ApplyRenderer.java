package io.github.bigswlittlesw.homelight.cli;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import io.github.bigswlittlesw.homelight.application.ApplyModel;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationExecutor;

import java.io.PrintWriter;
import java.util.List;

final class ApplyRenderer {
    void renderJson(ApplyModel.Result result, PrintWriter output) {
        if (result.execution().isPresent()) {
            renderJson(result.execution().orElseThrow(), output);
            return;
        }
        // Preserve known action evidence without inventing an execution or implying zero mutation.
        var relocations = result.plan().relocations().stream().map(relocation ->
                new ReconciliationExecutor.RelocationExecution(relocation, result.steps().stream()
                        .filter(step -> step.relocation() == relocation)
                        .map(step -> new ReconciliationExecutor.ActionExecution(step.action(), switch (step.status()) {
                            case COMPLETED -> ReconciliationExecutor.ActionStatus.COMPLETED;
                            case FAILED, RUNNING -> ReconciliationExecutor.ActionStatus.FAILED;
                            case PENDING -> ReconciliationExecutor.ActionStatus.PENDING;
                        }, step.message())).toList())).toList();
        JsonOutput.print(toJson(false, relocations, result.diagnostics(), result.stale()), output);
    }

    void renderJson(ReconciliationExecutor.ExecutionResult result, PrintWriter output) {
        JsonOutput.print(toJson(result.succeeded(), result.relocations(), List.of(), false), output);
    }

    private static ApplyJson toJson(boolean succeeded, List<ReconciliationExecutor.RelocationExecution> relocations,
            List<String> diagnostics, boolean stale) {
        var executed = relocations.stream().map(relocation -> new ApplyJson.RelocationJson(
                relocation.relocation().relocation().sourcePath().toString(),
                relocation.relocation().relocation().targetPath().toString(),
                relocation.outcome().value(),
                relocation.actions().stream().map(action -> new ApplyJson.ActionResultJson(
                        ActionJson.of(action.action()), action.status().name().toLowerCase(), action.message())).toList()))
                .toList();
        // `stale` and `diagnostics` appear only when there are diagnostics.
        return diagnostics.isEmpty()
                ? new ApplyJson(succeeded, executed, null, null)
                : new ApplyJson(succeeded, executed, stale, diagnostics);
    }
}

/// The `apply --json` response. Component order is the contract's field order.
record ApplyJson(boolean succeeded, List<RelocationJson> relocations,
                 @JsonInclude(Include.NON_NULL) Boolean stale, @JsonInclude(Include.NON_NULL) List<String> diagnostics) {
    record RelocationJson(String source, String target, String outcome, List<ActionResultJson> actions) {
    }

    /// The plan's action fields followed by its execution status.
    record ActionResultJson(@JsonUnwrapped ActionJson action, String status, String message) {
    }
}
