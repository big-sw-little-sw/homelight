package io.github.bigswlittlesw.homelight.config

import io.github.bigswlittlesw.homelight.config.CandidateDiagnostic.Kind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * Strict, atomic parsing of already-read UTF-8 JSON contents for either source kind.
 * Shared I/O and its deadlines belong to the caller, not this lexical boundary.
 *
 * A shared list is written by someone else, so input is bounded: its size before decoding, and its records,
 * groups and string lengths after. Nesting needs no separate limit: the fixed file shape rejects any value
 * nested deeper than a record as a wrong type, before reading into it.
 */
class CandidateParser {
    fun parse(source: CandidateSource, root: Path, contents: ByteArray): CandidateCatalog.Snapshot {
        @Suppress("NAME_SHADOWING")
        val root = normalizedSourceRoot(root)
        if (contents.size > MAX_BYTES) {
            return failure(source, root, Kind.LIMIT, "Input exceeds 1 MiB UTF-8 limit")
        }
        val text = try {
            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(contents)).toString()
        } catch (e: CharacterCodingException) {
            return failure(source, root, Kind.ENCODING, "Input is not valid UTF-8")
        }
        return try {
            val file = decodeJson(CandidateListFile.serializer(), text)
            CandidateCatalog.Snapshot.of(source, root, Reader(source, root).definitions(file), listOf())
        } catch (e: JsonInputException) {
            // The message names the dotted path where kotlinx knows it.
            rejected(root, Invalid(CandidateDiagnostic(source, Kind.SYNTAX, 0, e.line, e.column, e.path, "", e.message)))
        } catch (e: Invalid) {
            rejected(root, e)
        }
    }

    internal fun failure(source: CandidateSource, root: Path, kind: Kind, message: String): CandidateCatalog.Snapshot =
        rejected(root, Invalid(CandidateDiagnostic(source, kind, 0, 0, 0, "", "", message)))

    private fun rejected(root: Path, e: Invalid): CandidateCatalog.Snapshot =
        CandidateCatalog.Snapshot.of(e.diagnostic.source, root, listOf(), listOf(e.diagnostic))

    /** Rejects the whole source with one diagnostic. */
    private class Invalid(val diagnostic: CandidateDiagnostic) : RuntimeException(diagnostic.message)

    /**
     * Applies the domain rules to the decoded file: limits, path safety and nonblank text. Its diagnostics
     * have no line or column: kotlinx keeps no positions once decoded, so they name the record by `location`
     * and `key` instead.
     */
    private class Reader(val source: CandidateSource, val root: Path) {
        private val definitions = ArrayList<CandidateDefinition>()

        fun definitions(file: CandidateListFile): List<CandidateDefinition> {
            if (file.apps == null && file.directories == null) {
                throw invalid(Kind.SCHEMA, 0, "", "", "At least one of apps or directories is required")
            }
            val apps = file.apps.orEmpty()
            if (apps.size > MAX_APPS) throw invalid(Kind.LIMIT, 0, "", "apps", "Too many app groups")
            apps.forEachIndexed { i, app ->
                val location = "apps[$i]"
                val name = bounded(app.name, 0, location, "name")
                if (name.isJavaBlank() || name != name.javaStrip()) {
                    throw invalid(
                        Kind.SCHEMA, 0, location, "name", "App label must not be blank or have leading or trailing whitespace",
                    )
                }
                append(app.directories, name, "$location.directories")
            }
            file.directories?.let { directories -> append(directories, null, "directories") }
            return definitions
        }

        private fun append(directories: List<DirectoryFile>, app: String?, location: String) {
            if (directories.size > MAX_RECORDS - definitions.size) {
                throw invalid(Kind.LIMIT, 0, location, "directories", "Too many records across catalog")
            }
            directories.forEachIndexed { i, directory ->
                val index = definitions.size + 1
                val record = "$location[$i]"
                val path = bounded(directory.path, index, record, "path")
                val resolved = try {
                    resolveCandidatePath(root, path)
                } catch (e: IllegalArgumentException) {
                    // Both resolve's own failures and Path.of's InvalidPathException carry a message.
                    throw invalid(Kind.UNSAFE_PATH, index, record, "path", e.message!!)
                }
                val reason = directory.reason?.let { bounded(it, index, record, "reason") }
                if (reason != null && reason.isJavaBlank()) {
                    throw invalid(Kind.SCHEMA, index, record, "reason", "Reason must not be blank")
                }
                definitions.add(CandidateDefinition(resolved, source, index, record, path, app, directory.advice, reason))
            }
        }

        private fun bounded(value: String, index: Int, location: String, key: String): String {
            if (value.codePointCount(0, value.length) > MAX_STRING_CHARACTERS) {
                throw invalid(Kind.LIMIT, index, location, key, "String exceeds 4096 characters")
            }
            return value
        }

        private fun invalid(kind: Kind, index: Int, location: String, key: String, message: String): Invalid =
            Invalid(CandidateDiagnostic(source, kind, index, 0, 0, location, key, message))
    }

    companion object {
        const val MAX_BYTES = 1_048_576
        const val MAX_RECORDS = 10_000
        const val MAX_APPS = 10_000
        const val MAX_STRING_CHARACTERS = 4_096
    }
}

private val EXPANSION = Regex("\\$(?:\\{|[A-Za-z_])")

internal fun normalizedSourceRoot(root: Path): Path {
    require(root.isAbsolute) { "Source root must be absolute" }
    return root.normalize()
}

internal fun validateCandidatePath(path: String) {
    val unsafe = path.isJavaBlank() || path.startsWith("/") || path.startsWith("~") || path.contains("\\")
        || path.matches(Regex("^[A-Za-z][A-Za-z0-9+.-]*:.*"))
        || path.indexOf('*') >= 0 || path.indexOf('?') >= 0 || path.indexOf('[') >= 0 || path.indexOf(']') >= 0
        || path.any { it.isISOControl() }
        || EXPANSION.containsMatchIn(path)
    require(!unsafe) { "Path must be a literal portable relative path" }
    require(path.split("/").none { it == ".." }) { "Parent path components are forbidden" }
    val relative = Path.of(path)
    require(!relative.isAbsolute && relative.normalize().toString().isNotEmpty()) {
        "Path must name a strict descendant of the source root"
    }
}

internal fun resolveCandidatePath(root: Path, path: String): Path {
    validateCandidatePath(path)
    val resolved = root.resolve(path).normalize()
    require(resolved != root && resolved.startsWith(root)) { "Path must name a strict descendant of the source root" }
    return resolved
}

// The candidate list format. Every class has a serial name because kotlinx puts it in its error messages.

@Serializable
@SerialName("candidate-list")
internal data class CandidateListFile(val apps: List<AppFile>? = null, val directories: List<DirectoryFile>? = null)

@Serializable
@SerialName("app")
internal data class AppFile(val name: String, val directories: List<DirectoryFile>)

@Serializable
@SerialName("directory")
internal data class DirectoryFile(val path: String, val advice: CandidateDefinition.Advice? = null, val reason: String? = null)
