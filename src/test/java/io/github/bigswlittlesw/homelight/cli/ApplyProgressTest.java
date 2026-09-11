package io.github.bigswlittlesw.homelight.cli;

import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApplyProgressTest {
    @Test
    void identifiesTheSourceAndTargetOfCopyActions() {
        var source = Path.of("/home/user/cache");
        var target = Path.of("/local/cache");

        assertEquals("Copy " + source + " → " + target,
                ApplyProgress.actionText(new ReconciliationAction.CopyDirectory(source, target)));
    }

    @Test
    void identifiesTheDirectoryDiscardedByDeleteActions() {
        var directory = Path.of("/home/user/cache");

        assertEquals("Discard contents at " + directory,
                ApplyProgress.actionText(new ReconciliationAction.DeleteDirectory(directory)));
    }
}
