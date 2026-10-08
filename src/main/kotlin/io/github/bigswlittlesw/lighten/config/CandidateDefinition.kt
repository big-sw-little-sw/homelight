package io.github.bigswlittlesw.lighten.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Path

/**
 * One attributed occurrence, including its original spelling and literal reason.
 * The record index is one-based. Consumers must escape control characters when
 * displaying text; reasons are not terminal markup or commands.
 * Structural locations use zero-based indices, e.g. `apps[1].directories[0]`.
 *
 * `ecosystem` is the app's, as this record's list gives it; only a directory with an app can have one.
 */
data class CandidateDefinition(
    val sourcePath: Path, val source: CandidateSource, val recordIndex: Int,
    val location: String, val originalPath: String,
    val app: String?, val ecosystem: String?, val advice: Advice?, val reason: String?,
) {
    init {
        require(
            sourcePath.isAbsolute && sourcePath == sourcePath.normalize()
                    && recordIndex >= 1 && !location.isJavaBlank(),
        ) { "Definition requires normalized absolute identity and location" }
        validateCandidatePath(originalPath)
        require(
            (app == null || !app.isJavaBlank() && app == app.javaStrip())
                && (ecosystem == null || app != null && !ecosystem.isJavaBlank() && ecosystem == ecosystem.javaStrip())
                && (reason == null || !reason.isJavaBlank()),
        ) { "Optional text must be nonblank; app and ecosystem must be trimmed; an ecosystem needs an app" }
    }

    @Serializable
    @SerialName("advice")
    enum class Advice {
        @SerialName("consider") CONSIDER,
        @SerialName("usually-unnecessary") USUALLY_UNNECESSARY,
    }
}
