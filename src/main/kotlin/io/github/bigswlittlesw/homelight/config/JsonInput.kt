package io.github.bigswlittlesw.homelight.config

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecodingException

// JSON input shared by configuration files and candidate lists. kotlinx rejects malformed JSON, unknown keys
// and wrong value types; this file adds the duplicate-key check and turns kotlinx's offset into a position.

/** Comments and trailing commas ease hand editing. Both options are experimental in kotlinx 1.11. */
@OptIn(ExperimentalSerializationApi::class)
private val INPUT = Json {
    allowComments = true
    allowTrailingComma = true
}

/**
 * Rejected input. `line` and `column` are one-based; the column counts UTF-16 characters. `path` is dotted,
 * e.g. `homelight.relocations[0]`, and empty for the document or when unknown (duplicate keys).
 */
internal class JsonInputException(val line: Int, val column: Int, val path: String, override val message: String) :
    RuntimeException(message)

/** Decodes `text`, or throws [JsonInputException] with a message free of Kotlin and Java names. */
@OptIn(ExperimentalSerializationApi::class) // JsonDecodingException with its offset and path
internal fun <T> decodeJson(deserializer: DeserializationStrategy<T>, text: String): T {
    val value = try {
        INPUT.decodeFromString(deserializer, text)
    } catch (e: JsonDecodingException) {
        // The hint, when present, names kotlinx builder options, so it is dropped.
        val path = e.path?.removePrefix("$")?.removePrefix(".").orEmpty()
        throw failure(text, e.offset, path, e.shortMessage + if (path.isEmpty()) "" else " at $path")
    }
    duplicateKey(text)?.let { (offset, key) -> throw failure(text, offset, "", "Duplicate key '$key'") }
    return value
}

private fun failure(text: String, position: Int, path: String, message: String): JsonInputException {
    // kotlinx reports the end of input as offset -1.
    val offset = if (position in 0..text.length) position else text.length
    val lineStart = text.lastIndexOf('\n', offset - 1) + 1
    val line = text.substring(0, offset).count { it == '\n' } + 1
    return JsonInputException(line, offset - lineStart + 1, path, message)
}

/**
 * The offset and text of the first key repeated within one object, or null. kotlinx keeps the last value of a
 * repeated key, in classes and in `JsonObject` alike, so this scans the text. It runs only on input kotlinx
 * accepted, so the JSON is well formed. Keys compare as written: `"a"` and `"a"` are not detected as equal.
 */
private fun duplicateKey(text: String): Pair<Int, String>? {
    val keys = ArrayDeque<MutableSet<String>?>() // One entry per open object; null for an array.
    var expectingKey = false
    var i = 0
    while (i < text.length) {
        when (text[i]) {
            '{' -> { keys.addLast(HashSet()); expectingKey = true }
            '[' -> { keys.addLast(null); expectingKey = false }
            '}', ']' -> keys.removeLast()
            ',' -> expectingKey = keys.lastOrNull() != null
            '/' -> i = if (text[i + 1] == '/') text.indexOf('\n', i).let { if (it < 0) text.length else it }
            else text.indexOf("*/", i + 2) + 1
            '"' -> {
                val start = i++
                while (text[i] != '"') i += if (text[i] == '\\') 2 else 1
                val key = text.substring(start + 1, i)
                val open = keys.lastOrNull()
                // Like kotlinx, point at the key's first character rather than its quote.
                if (expectingKey && open != null && !open.add(key)) return start + 1 to key
                expectingKey = false
            }
        }
        i++
    }
    return null
}
