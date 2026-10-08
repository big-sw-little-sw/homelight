package io.github.bigswlittlesw.homelight.config

/** Attribution only. The shared location is never opened or used as a resolution root. */
data class CandidateSource(val kind: Kind, val location: String) {
    init {
        require(!location.isJavaBlank()) { "Source location is required" }
    }

    enum class Kind { BUNDLED, SHARED }
}
