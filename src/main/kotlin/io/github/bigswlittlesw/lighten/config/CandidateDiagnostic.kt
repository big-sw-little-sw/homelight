package io.github.bigswlittlesw.lighten.config

/**
 * Line and column are one-based; zero means unavailable. Only [Kind.SYNTAX] diagnostics,
 * raised while decoding, can have a position, and missing keys and unknown advice values have
 * none. Key is empty when no schema key applies. Messages are data and require escaping for
 * display. Structural location identifies the enclosing record or collection using zero-based
 * indices; it is empty for source-wide failures.
 *
 * [Kind.SYNTAX] covers everything the JSON reader rejects: malformed JSON, unknown or missing
 * keys, values of the wrong type and unknown advice values. Its location is the dotted path
 * kotlinx reports, which can be the offending key itself.
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
