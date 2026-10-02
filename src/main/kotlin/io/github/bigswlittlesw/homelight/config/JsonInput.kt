package io.github.bigswlittlesw.homelight.config

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecodingException

// JSON input shared by configuration files and candidate lists. kotlinx owns the format: it rejects malformed
// JSON, unknown keys, missing required keys, wrong value types and unknown enum values, and keeps the last value
// of a repeated key. This file only turns kotlinx's offset into a position and keeps its messages readable.

/** Comments and trailing commas ease hand editing. Both options are experimental in kotlinx 1.11. */
@OptIn(ExperimentalSerializationApi::class)
private val INPUT = Json {
    allowComments = true
    allowTrailingComma = true
}

/**
 * Rejected input. `line` and `column` are one-based, and zero when kotlinx gives no offset (missing keys,
 * unknown enum values). The column counts UTF-16 characters. `path` is dotted, e.g. `homelight.relocations[0]`,
 * and empty for the document.
 */
internal class JsonInputException(val line: Int, val column: Int, val path: String, override val message: String) :
    RuntimeException(message)

/**
 * Decodes `text`, or throws [JsonInputException]. Messages name file classes by their `@SerialName`, so they
 * hold no Kotlin or Java names.
 */
@OptIn(ExperimentalSerializationApi::class) // JsonDecodingException with its offset and path
internal fun <T> decodeJson(deserializer: DeserializationStrategy<T>, text: String): T =
    try {
        INPUT.decodeFromString(deserializer, text)
    } catch (e: JsonDecodingException) {
        // The hint names kotlinx builder options, so it is dropped.
        val path = e.path?.let(::dotted).orEmpty()
        // kotlinx reports the end of input as offset -1.
        val offset = if (e.offset < 0) text.length else e.offset
        throw failure(text, offset, path, located(escaped(e.shortMessage), path))
    } catch (e: SerializationException) {
        // Missing keys and unknown enum values: kotlinx gives the path inside the message, and no offset.
        val message = e.message.orEmpty()
        val match = PATH_SUFFIX.find(message)
        val path = match?.groupValues?.get(1)?.let(::dotted).orEmpty()
        val detail = escaped(match?.let { message.substring(0, it.range.first) } ?: message)
        throw JsonInputException(0, 0, path, located(detail, path))
    }

private val PATH_SUFFIX = Regex(" at path:? (\\$\\S*)$")

private fun dotted(path: String): String = path.removePrefix("$").removePrefix(".")

private fun located(message: String, path: String): String = if (path.isEmpty()) message else "$message at $path"

/** A message can quote the offending input character, which can be a control character such as a newline. */
private fun escaped(message: String): String =
    message.map { if (it.isISOControl()) "\\u%04x".format(it.code) else "$it" }.joinToString("")

private fun failure(text: String, offset: Int, path: String, message: String): JsonInputException {
    val position = offset.coerceAtMost(text.length)
    val lineStart = text.lastIndexOf('\n', position - 1) + 1
    val line = text.substring(0, position).count { it == '\n' } + 1
    return JsonInputException(line, position - lineStart + 1, path, message)
}
