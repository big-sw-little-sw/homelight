package io.github.bigswlittlesw.homelight.config

import java.nio.file.Path

/**
 * One attributed occurrence, including its original spelling and literal reason.
 * The record index is one-based. Consumers must escape control characters when
 * displaying text; reasons are not terminal markup or commands.
 * Structural locations use zero-based indices, e.g. `apps[1].directories[0]`.
 */
data class CandidateDefinition(
    val sourcePath: Path, val source: CandidateSource, val recordIndex: Int,
    val location: String, val originalPath: String,
    val app: String?, val advice: Advice?, val reason: String?,
) {
    init {
        require(
            sourcePath.isAbsolute && sourcePath == sourcePath.normalize()
                    && recordIndex >= 1 && !location.isJavaBlank(),
        ) { "Definition requires normalized absolute identity and location" }
        CandidateParser.validatePath(originalPath)
        require((app == null || !app.isJavaBlank() && app == app.javaStrip()) && (reason == null || !reason.isJavaBlank())) {
            "Optional text must be nonblank; app must be trimmed"
        }
    }

    enum class Advice { CONSIDER, USUALLY_UNNECESSARY }
}
