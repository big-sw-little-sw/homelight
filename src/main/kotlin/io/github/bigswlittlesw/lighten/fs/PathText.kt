package io.github.bigswlittlesw.lighten.fs

import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.FileSystemLoopException
import java.nio.file.InvalidPathException
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.NotLinkException
import java.nio.file.Path

/**
 * Words that name paths, kept as [Path]s until shown: people see the home directory as `~` ([shown]), while JSON,
 * exception messages and logs keep every path in full ([toString]).
 *
 * The parts are [String]s and [Path]s, in order: `PathText("duplicate target path: ", target)`.
 */
class PathText(vararg parts: Any) {
    private val parts: List<Any> = parts.toList()

    init {
        require(this.parts.all { it is String || it is Path }) { "PathText takes strings and paths: ${this.parts}" }
    }

    /** For screens and the CLI's human-readable output. */
    fun shown(): String = render(::displayPath)

    /** For JSON and logs: each path in full. */
    override fun toString(): String = render(Path::toString)

    operator fun plus(more: String): PathText = PathText(*(parts + more).toTypedArray())

    private fun render(path: (Path) -> String): String =
        parts.joinToString("") { if (it is Path) path(it) else it as String }

    override fun equals(other: Any?): Boolean = other is PathText && other.parts == parts

    override fun hashCode(): Int = parts.hashCode()
}

/**
 * [path] with the home directory shown as `~`, as every path on screen and in the CLI's human-readable output is.
 * Only the home directory becomes `~`: a path under another root, such as `source-root`, stays in full.
 */
internal fun displayPath(path: Path, home: Path? = homeDirectoryOrNull()): String =
    if (home != null && path.startsWith(home) && home.nameCount > 0) "~" + path.toString().substring(home.toString().length)
    else path.toString()

/**
 * The home directory, which `~` names: the `HOME` variable when it is a full path, else the JVM's `user.home` when
 * it is one, else null.
 *
 * `HOME` comes first because the user's shell and other tools use it. Also, the static musl binary cannot find a
 * user who comes from LDAP or SSSD, and the JVM then sets `user.home` to `?`. `user.home` is the fallback for an
 * environment without `HOME`, or with a relative one. When neither is a full path, Lighten does not guess: the
 * caller says so.
 */
internal fun homeDirectoryOrNull(
    environment: String? = System.getenv("HOME"), property: String? = System.getProperty("user.home"),
): Path? = fullPath(environment) ?: fullPath(property)

private fun fullPath(value: String?): Path? =
    try {
        value?.let(Path::of)?.takeIf { it.isAbsolute }?.normalize()
    } catch (_: InvalidPathException) {
        null
    }

/**
 * The system's words for [exception], for text that names its paths separately: a [FileSystemException]'s reason,
 * which leaves out its paths, else its message. The JDK throws the common file exceptions without a reason, so they
 * get plain words here. A Java type name means nothing to users, so it is never shown.
 */
internal fun systemReason(exception: Throwable): String {
    if (exception !is FileSystemException) return exception.message ?: NO_REASON
    return exception.reason ?: when (exception) {
        is NoSuchFileException -> "not found"
        is AccessDeniedException -> "permission denied"
        is FileAlreadyExistsException -> "already exists"
        is DirectoryNotEmptyException -> "the directory is not empty"
        is NotDirectoryException -> "not a directory"
        is NotLinkException -> "not a link"
        is FileSystemLoopException -> "its links loop"
        else -> NO_REASON
    }
}

private const val NO_REASON = "the system gave no reason"
