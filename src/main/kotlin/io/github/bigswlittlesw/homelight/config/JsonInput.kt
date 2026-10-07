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
 *
 * `message` keeps kotlinx's words. `problem` says the same in plain parts when kotlinx's message is one HomeLight
 * recognizes, and is null otherwise, such as for an unknown key.
 */
internal class JsonInputException(
    val line: Int, val column: Int, val path: String, override val message: String, val problem: JsonProblem? = null,
) : RuntimeException(message)

/** A rejection in plain words, for a reader who edits the file by hand. */
internal sealed interface JsonProblem {
    /** Text that is not JSON. `words` follow the position, such as `should start with "{" but starts with "h"`. */
    data class Syntax(val words: String) : JsonProblem

    /** Valid JSON of the wrong kind at the path, such as `expected` `text` and `found` `a number`. */
    data class WrongKind(val expected: String, val found: String) : JsonProblem

    /** A required key absent from the object at the path. Each file object requires at most one key. */
    data class MissingKey(val key: String) : JsonProblem
}

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
        val problem = lexerProblem(e.shortMessage, text, offset)
        throw failure(text, offset, path, located(escaped(e.shortMessage), path), problem)
    } catch (e: SerializationException) {
        // Missing keys and unknown enum values: kotlinx gives the path inside the message, and no offset.
        val message = e.message.orEmpty()
        val match = PATH_SUFFIX.find(message)
        val path = match?.groupValues?.get(1)?.let(::dotted).orEmpty()
        val detail = match?.let { message.substring(0, it.range.first) } ?: message
        val missing = MISSING_KEY.matchEntire(detail)?.let { JsonProblem.MissingKey(it.groupValues[1]) }
        throw JsonInputException(0, 0, path, located(escaped(detail), path), missing)
    }

private val PATH_SUFFIX = Regex(" at path:? (\\$\\S*)$")

private fun dotted(path: String): String = path.removePrefix("$").removePrefix(".")

private fun located(message: String, path: String): String = if (path.isEmpty()) message else "$message at $path"

/** A message can quote the offending input character, which can be a control character such as a newline. */
private fun escaped(message: String): String =
    message.map { if (it.isISOControl()) "\\u%04x".format(it.code) else "$it" }.joinToString("")

private fun failure(
    text: String, offset: Int, path: String, message: String, problem: JsonProblem?,
): JsonInputException {
    val position = offset.coerceAtMost(text.length)
    val lineStart = text.lastIndexOf('\n', position - 1) + 1
    val line = text.substring(0, position).count { it == '\n' } + 1
    return JsonInputException(line, position - lineStart + 1, path, message, problem)
}

// kotlinx's messages, recognized by their wording in kotlinx 1.11. A message none of these recognizes keeps
// kotlinx's words, so a change in kotlinx's wording makes a message less plain, never wrong.

private val MISSING_KEY = Regex("Field '(.+)' is required for type with serial name '.*', but it was missing")

/** What kotlinx's lexer `message` about the input at `offset` means, or null. */
private fun lexerProblem(message: String, text: String, offset: Int): JsonProblem? =
    wrongKind(message, text, offset) ?: syntax(message)?.let { JsonProblem.Syntax(it) }

private val EXPECTED_TOKEN = Regex("Expected (.+?) '(.)', but had '(.*)' instead", RegexOption.DOT_MATCHES_ALL)

/** What kotlinx expects where a value starts, in plain words. Another value there is valid JSON of the wrong kind. */
private val VALUE_EXPECTED = mapOf(
    "start of the object" to "an object in { }", "start of the array" to "a list in [ ]", "quotation mark" to "text",
)

private val TEXT_EXPECTED = listOf("Expected beginning of the string", "Expected string literal")

private fun wrongKind(message: String, text: String, offset: Int): JsonProblem? {
    val found = kindAt(text, offset) ?: return null
    val expected = EXPECTED_TOKEN.matchEntire(message)?.groupValues?.get(1)?.let(VALUE_EXPECTED::get)
        ?: "text".takeIf { TEXT_EXPECTED.any(message::startsWith) }
        ?: return null
    return JsonProblem.WrongKind(expected, found)
}

/** The kind of the JSON value that starts at `offset`, in plain words, or null when none starts there. */
private fun kindAt(text: String, offset: Int): String? {
    val first = text.getOrNull(offset) ?: return null
    return when {
        first == '{' -> "an object"
        first == '[' -> "a list"
        first == '"' -> "text"
        first == '-' || first.isDigit() -> "a number"
        else -> listOf("true", "false", "null").firstOrNull { text.startsWith(it, offset) }
    }
}

private fun syntax(message: String): String? =
    expectedToken(message) ?: unseparated(message) ?: trailing(message) ?: openComment(message) ?: badEscape(message)

private fun expectedToken(message: String): String? {
    val (_, token, found) = EXPECTED_TOKEN.matchEntire(message)?.destructured ?: return null
    return when (found) {
        "EOF" -> "should have ${shown(token)} but the file ends there"
        "\n", "\r" -> "should have ${shown(token)} but the line ends there"
        else -> "should start with ${shown(token)} but starts with ${shown(found)}"
    }
}

private val UNSEPARATED = Regex("Expected end of the (object|array) or comma")

private fun unseparated(message: String): String? = UNSEPARATED.matchEntire(message)?.let { match ->
    "should start with a comma or " + shown(if (match.groupValues[1] == "object") "}" else "]")
}

private val TRAILING = Regex("Expected EOF after parsing, but had (.+) instead", RegexOption.DOT_MATCHES_ALL)

private fun trailing(message: String): String? =
    TRAILING.matchEntire(message)?.let { "should be the end of the file but has " + shown(it.groupValues[1]) }

private fun openComment(message: String): String? =
    if (!message.startsWith("Expected end of the block comment")) null
    else "should close a comment with \"*/\" but the file ends there"

private val BAD_ESCAPE = Regex("Invalid escaped char '(.)'")

private fun badEscape(message: String): String? = BAD_ESCAPE.matchEntire(message)?.let { match ->
    "has a backslash before " + shown(match.groupValues[1]) +
        ", which JSON does not allow; write \\\\ for one backslash"
}

/** A character as a sentence quotes it; a double quote is named, since double quotes surround the others. */
private fun shown(text: String): String = if (text == "\"") "a double quote (\")" else "\"" + escaped(text) + "\""
