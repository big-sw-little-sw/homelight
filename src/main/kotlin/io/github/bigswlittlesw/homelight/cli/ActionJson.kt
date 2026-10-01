package io.github.bigswlittlesw.homelight.cli

import com.fasterxml.jackson.core.JsonGenerator
import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction

/** Keeps the machine-readable action contract consistent between plans and execution results. */
internal object ActionJson {
    fun writeFields(generator: JsonGenerator, action: ReconciliationAction) {
        generator.writeStringField("type", action.type)
        generator.writeStringField("path", action.path.toString())
        generator.writeBooleanField("destructive", action.destructive)
        when (action) {
            is ReconciliationAction.CopyDirectory -> generator.writeStringField("target", action.target.toString())
            is ReconciliationAction.MigrateDirectoryForPublication -> generator.writeStringField("target", action.target.toString())
            is ReconciliationAction.ArchiveDirectory -> generator.writeStringField("target", action.target.toString())
            is ReconciliationAction.CreateSymlink -> generator.writeStringField("target", action.target.toString())
            is ReconciliationAction.ReplaceDirectoryWithSymlink -> generator.writeStringField("target", action.target.toString())
            is ReconciliationAction.ReplaceSymlink -> generator.writeStringField("target", action.target.toString())
            is ReconciliationAction.Blocked -> generator.writeStringField("reason", action.reason)
            else -> {}
        }
    }
}
