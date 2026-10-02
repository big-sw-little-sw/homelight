package io.github.bigswlittlesw.homelight.config

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecodingException

// JSON input shared by configuration files and candidate lists. kotlinx owns the format: it rejects malformed
// JSON, unknown keys, missing required keys, wrong value types and unknown enum or policy names, and keeps the
// last value of a repeated key. This file only turns kotlinx's offset into a position and its message into one
// free of Kotlin names.

/** Comments and trailing commas ease hand editing. Both options are experimental in kotlinx 1.11. */
@OptIn(ExperimentalSerializationApi::class)
private val INPUT = Json {
    allowComments = true
    allowTrailingComma = true
}

/**
 * Rejected input. `line` and `column` are one-based, and zero when kotlinx gives no offset (missing keys,
 * unknown enum values, a policy object it rejected as a whole). The column counts UTF-16 characters. `path` is dotted, e.g.
 * `homelight.relocations[0]`, and empty for the document or when kotlinx gives none.
 */
internal class JsonInputException(val line: Int, val column: Int, val path: String, override val message: String) :
    RuntimeException(message)

/** Decodes `text`, or throws [JsonInputException] with a message free of Kotlin and Java names. */
@OptIn(ExperimentalSerializationApi::class) // JsonDecodingException with its offset and path
internal fun <T> decodeJson(deserializer: DeserializationStrategy<T>, text: String): T =
    try {
        INPUT.decodeFromString(deserializer, text)
    } catch (e: JsonDecodingException) {
        // The hint names kotlinx builder options, so it is dropped. A policy kotlinx decoded from a tree puts
        // the same kind of advice in the short message instead.
        val path = e.path?.let(::dotted).orEmpty()
        val message = located(translated(e.shortMessage.substringBefore("\nCheck if class")), path)
        // kotlinx gives offset -1 both for the end of input, which its message names, and for a policy object
        // it decoded as a whole, which has no position.
        val offset = if (e.offset < 0 && "'EOF'" in e.shortMessage) text.length else e.offset
        throw if (offset < 0) JsonInputException(0, 0, path, message) else failure(text, offset, path, message)
    } catch (e: SerializationException) {
        // Missing keys and unknown enum values: kotlinx gives the path inside the message, and no offset.
        val message = e.message.orEmpty()
        val match = PATH_SUFFIX.find(message)
        val path = match?.groupValues?.get(1)?.let(::dotted).orEmpty()
        val detail = translated(match?.let { message.substring(0, it.range.first) } ?: message)
        throw JsonInputException(0, 0, path, located(detail, path))
    }

private val PATH_SUFFIX = Regex(" at path:? (\\$\\S*)$")

private fun dotted(path: String): String = path.removePrefix("$").removePrefix(".")

private fun located(message: String, path: String): String = if (path.isEmpty()) message else "$message at $path"

/**
 * Removes the Kotlin names kotlinx puts in a few messages: the sealed class behind a policy, and its own
 * `JsonObject`-style element classes. A control character, which a quoted input character can be, is escaped.
 */
private fun translated(message: String): String = message
    .replace(POLYMORPHIC_SCOPE, "")
    .replace(ELEMENT_CLASS) { it.groupValues[1].lowercase() }
    .map { if (it.isISOControl()) "\\u%04x".format(it.code) else "$it" }.joinToString("")

private val POLYMORPHIC_SCOPE = Regex(" (?:and no default serializers were registered )?in the polymorphic scope of '[^']*'")
private val ELEMENT_CLASS = Regex("\\bJson(Object|Array|Primitive|Literal|Null)\\b")

private fun failure(text: String, offset: Int, path: String, message: String): JsonInputException {
    val position = offset.coerceAtMost(text.length)
    val lineStart = text.lastIndexOf('\n', position - 1) + 1
    val line = text.substring(0, position).count { it == '\n' } + 1
    return JsonInputException(line, position - lineStart + 1, path, message)
}
