package io.github.bigswlittlesw.lighten.fs

import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileSystemException
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

    private fun render(path: (Path) -> String): String = parts.joinToString("") { if (it is Path) path(it) else it as String }

    override fun equals(other: Any?): Boolean = other is PathText && other.parts == parts

    override fun hashCode(): Int = parts.hashCode()
}

/**
 * [path] with the home directory shown as `~`, as every path on screen and in the CLI's human-readable output is
 * (tui-design §4). Only the home directory becomes `~`: a path under another root, such as `source-root`, stays in
 * full.
 */
internal fun displayPath(path: Path, home: Path = Path.of(System.getProperty("user.home"))): String =
    if (path.startsWith(home) && home.nameCount > 0) "~" + path.toString().substring(home.toString().length)
    else path.toString()

/**
 * The system's words for [exception], for text that names its paths separately: a [FileSystemException]'s reason,
 * which leaves out its paths, else its message. The JDK throws some file exceptions without a reason; those that
 * matter get one, the rest their type's name, so a screenshot still says what failed.
 */
internal fun systemReason(exception: Throwable): String {
    if (exception !is FileSystemException) return exception.message ?: exception.toString()
    return exception.reason ?: when (exception) {
        is AccessDeniedException -> "permission denied"
        is DirectoryNotEmptyException -> "the folder is not empty"
        is NotDirectoryException -> "not a folder"
        is NotLinkException -> "not a link"
        else -> exception.javaClass.simpleName
    }
}
