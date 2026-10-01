package io.github.bigswlittlesw.homelight.cli;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction;

/// Keeps the machine-readable action contract consistent between plans and execution results.
/// `target` and `reason` appear only for the action types that have them.
record ActionJson(String type, String path, boolean destructive,
                  @JsonInclude(Include.NON_NULL) String target, @JsonInclude(Include.NON_NULL) String reason) {

    static ActionJson of(ReconciliationAction action) {
        var target = switch (action) {
            case ReconciliationAction.CopyDirectory copy -> copy.target().toString();
            case ReconciliationAction.MigrateDirectoryForPublication stage -> stage.target().toString();
            case ReconciliationAction.ArchiveDirectory archive -> archive.target().toString();
            case ReconciliationAction.CreateSymlink link -> link.target().toString();
            case ReconciliationAction.ReplaceDirectoryWithSymlink link -> link.target().toString();
            case ReconciliationAction.ReplaceSymlink link -> link.target().toString();
            default -> null;
        };
        var reason = action instanceof ReconciliationAction.Blocked blocked ? blocked.reason() : null;
        return new ActionJson(action.type(), action.path().toString(), action.destructive(), target, reason);
    }
}
