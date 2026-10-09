package io.github.bigswlittlesw.lighten.config

/** Names the list a suggestion came from. Nothing opens `location` or resolves paths against it. */
data class CandidateSource(val kind: Kind, val location: String) {
    init {
        require(!location.isJavaBlank()) { "Source location is required" }
    }

    enum class Kind { BUNDLED, SHARED }
}
