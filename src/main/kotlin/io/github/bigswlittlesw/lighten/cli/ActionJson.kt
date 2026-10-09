package io.github.bigswlittlesw.lighten.cli

import io.github.bigswlittlesw.lighten.reconcile.ReconciliationAction
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

internal fun actionJson(action: ReconciliationAction): ActionJson = ActionJson(
    action.type, action.path.toString(), action.destructive,
    target = action.destination?.toString(), reason = (action as? ReconciliationAction.Blocked)?.reason?.toString(),
)

/**
 * The version of the `status`, `plan` and `apply` JSON responses, written as each response's first
 * property. Raise it when a change could break a script that reads the current shape.
 */
internal const val JSON_SCHEMA = 1

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
