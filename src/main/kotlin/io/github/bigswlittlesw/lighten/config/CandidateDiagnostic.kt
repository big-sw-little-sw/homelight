package io.github.bigswlittlesw.lighten.config

/**
 * `line` and `column` are one-based, and zero when unknown. Only a [Kind.SYNTAX] diagnostic, made while
 * decoding, can have a position. Missing keys and unknown advice values have none. `key` is empty when no key of
 * the format applies. A message is data: escape it before showing it. `location` names the record or list at
 * fault with zero-based indices. It is empty when the whole list fails.
 *
 * [Kind.SYNTAX] covers everything the JSON reader rejects: malformed JSON, unknown or missing
 * keys, values of the wrong type and unknown advice values. Its location is the dotted path
 * kotlinx.serialization reports, which can be the key at fault.
 */
data class CandidateDiagnostic(
    val source: CandidateSource, val kind: Kind, val recordIndex: Int,
    val line: Int, val column: Int, val location: String, val key: String, val message: String,
) {
    init {
        require(recordIndex >= 0 && line >= 0 && column >= 0 && !message.isJavaBlank()) {
            "Invalid diagnostic location or message"
        }
    }

    enum class Kind { SYNTAX, SCHEMA, UNSAFE_PATH, LIMIT, ENCODING, RESOURCE }
}
