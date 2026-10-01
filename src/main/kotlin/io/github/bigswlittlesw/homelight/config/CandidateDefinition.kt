package io.github.bigswlittlesw.homelight.config

import java.nio.file.Path
import java.util.Optional

/**
 * One attributed occurrence, including its original spelling and literal reason.
 * Record, line and column numbers are one-based. Consumers must escape control
 * characters when displaying text; reasons are not terminal markup or commands.
 * Structural locations use zero-based indices, e.g. `apps[1].directories[0]`.
 */
@JvmRecord
data class CandidateDefinition(
    val sourcePath: Path, val source: CandidateSource, val recordIndex: Int,
    val line: Int, val column: Int, val location: String, val originalPath: String,
    val app: Optional<String>, val advice: Optional<Advice>, val reason: Optional<String>,
) {
    init {
        if (!sourcePath.isAbsolute || sourcePath != sourcePath.normalize()
            || recordIndex < 1 || line < 1 || column < 1 || location.isJavaBlank()
        ) {
            throw IllegalArgumentException("Definition requires normalized absolute identity and location")
        }
        CandidateParser.validatePath(originalPath)
        if (app.filter { s -> s.isJavaBlank() || s != s.javaStrip() }.isPresent
            || reason.filter { s -> s.isJavaBlank() }.isPresent
        ) {
            throw IllegalArgumentException("Optional text must be nonblank; app must be trimmed")
        }
    }

    enum class Advice { CONSIDER, USUALLY_UNNECESSARY }
}
