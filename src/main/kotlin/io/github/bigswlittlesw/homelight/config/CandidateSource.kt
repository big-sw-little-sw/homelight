package io.github.bigswlittlesw.homelight.config

/** Attribution only. The shared location is never opened or used as a resolution root. */
@JvmRecord
data class CandidateSource(val kind: Kind, val location: String) {
    init {
        if (location.isJavaBlank()) {
            throw IllegalArgumentException("Source location is required")
        }
    }

    enum class Kind { BUNDLED, SHARED }
}
