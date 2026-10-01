package io.github.bigswlittlesw.homelight.cli

import io.github.bigswlittlesw.homelight.reconcile.ReconciliationAction
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The machine-readable action contract, shared by plans and execution results.
 *
 * Plans omit `status` and `message`; execution results always set both.
 */
@Serializable
internal data class ActionJson(
    val type: String,
    val path: String,
    val destructive: Boolean,
    val target: String? = null,
    val reason: String? = null,
    val status: String? = null,
    val message: String? = null,
)

internal fun actionJson(action: ReconciliationAction): ActionJson {
    val target = when (action) {
        is ReconciliationAction.CopyDirectory -> action.target
        is ReconciliationAction.MigrateDirectoryForPublication -> action.target
        is ReconciliationAction.ArchiveDirectory -> action.target
        is ReconciliationAction.CreateSymlink -> action.target
        is ReconciliationAction.ReplaceDirectoryWithSymlink -> action.target
        is ReconciliationAction.ReplaceSymlink -> action.target
        is ReconciliationAction.EnsureDirectory, is ReconciliationAction.CreateDirectory,
        is ReconciliationAction.DeleteDirectory, is ReconciliationAction.NoOp,
        is ReconciliationAction.LeaveUnchanged, is ReconciliationAction.Blocked -> null
    }
    return ActionJson(
        action.type, action.path.toString(), action.destructive,
        target = target?.toString(), reason = (action as? ReconciliationAction.Blocked)?.reason,
    )
}

/**
 * Encodes one JSON response on a single line.
 *
 * The response classes rely on the default [Json] settings: properties in declaration order, a
 * property with a `null` default is omitted while it is `null` (`encodeDefaults = false`), and a
 * property without a default is always written. Response classes therefore give a default only
 * to optional properties.
 */
internal fun <T> encodeJson(serializer: SerializationStrategy<T>, value: T): String =
    Json.encodeToString(serializer, value)
