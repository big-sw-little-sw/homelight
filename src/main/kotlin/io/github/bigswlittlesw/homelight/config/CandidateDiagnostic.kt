package io.github.bigswlittlesw.homelight.config

/**
 * Locations are one-based; zero means unavailable or source-wide. Key is empty
 * when no schema key applies. Messages are data and require escaping for display.
 * Structural location identifies the enclosing record or collection using zero-based
 * indices; it is empty for source-wide failures or failures before schema association.
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
