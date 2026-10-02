package io.github.bigswlittlesw.homelight.discovery

import io.github.bigswlittlesw.homelight.discovery.CandidateObservation.Diagnostic
import io.github.bigswlittlesw.homelight.discovery.CandidateObservation.Kind
import io.github.bigswlittlesw.homelight.discovery.CandidateObservation.Reason
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant

/**
 * Only root resolution, no-follow attributes and raw link text. No directory
 * enumeration or target probes. Rechecks detect replacements best-effort; Java
 * path-based operations do not provide atomic containment under concurrent renames.
 */
internal class CandidateMetadata(private val access: Access = Access()) {
    fun anchor(root: Path): Anchor {
        val physical = access.realPath(root)
        val attributes = access.attributes(physical)
        if (!attributes.isDirectory) throw IOException("Root is not a directory: $root")
        return Anchor(root, physical, attributes)
    }

    fun inspect(anchor: Anchor, candidate: Path, generation: Long): CandidateObservation {
        require(candidate.startsWith(anchor.lexical) && candidate != anchor.lexical) {
            "Candidate must be below the chosen root"
        }
        val guards = mutableListOf(Guard(anchor.physical, anchor.attributes))
        val diagnostics = mutableListOf<Diagnostic>()
        try {
            checkAnchor(anchor)
            if (anchor.lexical != anchor.physical || anchor.attributes.fileKey() == null) {
                diagnostics.add(
                    Diagnostic(
                        candidate, Reason.ALIAS_UNCERTAINTY,
                        "Root alias or unavailable file identity; lexical identities are not physical deduplication",
                    ),
                )
            }
            val relative = anchor.lexical.relativize(candidate)
            var current = anchor.physical
            for (i in 0 until relative.nameCount) {
                checkGuards(guards)
                current = current.resolve(relative.getName(i))
                val attributes = try {
                    access.attributes(current)
                } catch (e: NoSuchFileException) {
                    checkGuards(guards)
                    checkAnchor(anchor)
                    diagnostics.add(Diagnostic(current, Reason.MISSING, e.toString()))
                    return observation(candidate, Kind.MISSING, null, generation, diagnostics)
                }
                if (attributes.fileKey() == null && diagnostics.none { it.reason == Reason.ALIAS_UNCERTAINTY }) {
                    diagnostics.add(
                        Diagnostic(
                            current, Reason.ALIAS_UNCERTAINTY,
                            "Stable file identity unavailable; replacements may not be detectable",
                        ),
                    )
                }
                guards.add(Guard(current, attributes))
                checkGuards(guards)
                val leaf = i == relative.nameCount - 1
                val target = if (leaf && attributes.isSymbolicLink) access.readLink(current) else null
                val kind = when {
                    attributes.isSymbolicLink -> {
                        diagnostics.add(Diagnostic(current, Reason.SYMLINK_EXCLUDED, "Link destination not inspected"))
                        if (leaf) Kind.LINK else Kind.BLOCKED_BY_LINK
                    }
                    !leaf && !attributes.isDirectory -> {
                        diagnostics.add(
                            Diagnostic(current, Reason.NOT_DIRECTORY, "Intermediate component is not a directory"),
                        )
                        Kind.BLOCKED_BY_NON_DIRECTORY
                    }
                    !leaf -> continue
                    attributes.isDirectory -> Kind.DIRECTORY
                    attributes.isRegularFile -> Kind.REGULAR_FILE
                    else -> Kind.OTHER
                }
                checkGuards(guards)
                checkAnchor(anchor)
                return observation(candidate, kind, target, generation, diagnostics)
            }
            error("unreachable: a candidate strictly below the root has at least one name")
        } catch (e: IOException) {
            return failed(candidate, e, generation, diagnostics)
        } catch (e: SecurityException) {
            return failed(candidate, e, generation, diagnostics)
        }
    }

    private fun failed(
        candidate: Path, failure: Exception, generation: Long, diagnostics: MutableList<Diagnostic>,
    ): CandidateObservation {
        val reason = when (failure) {
            is Changed -> Reason.CHANGED
            is AccessDeniedException, is SecurityException -> Reason.ACCESS_DENIED
            else -> Reason.IO_ERROR
        }
        diagnostics.add(Diagnostic(candidate, reason, failure.toString()))
        return observation(
            candidate, if (failure is Changed) Kind.UNKNOWN else Kind.INACCESSIBLE, null, generation, diagnostics,
        )
    }

    private fun checkAnchor(anchor: Anchor) {
        if (access.realPath(anchor.lexical) != anchor.physical
            || !same(anchor.attributes, access.attributes(anchor.physical))
        ) {
            throw Changed(anchor.lexical)
        }
    }

    private fun checkGuards(guards: List<Guard>) {
        for (guard in guards) {
            if (!same(guard.attributes, access.attributes(guard.path))) throw Changed(guard.path)
        }
    }

    data class Anchor(val lexical: Path, val physical: Path, val attributes: BasicFileAttributes)

    private data class Guard(val path: Path, val attributes: BasicFileAttributes)

    private class Changed(path: Path) : IOException("Filesystem identity changed: $path")

    /**
     * Deliberately excludes directory enumeration and file-content operations.
     * Open so tests can inject filesystem replacements and failures between probes.
     */
    open class Access {
        open fun realPath(path: Path): Path = path.toRealPath()

        open fun attributes(path: Path): BasicFileAttributes =
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)

        open fun readLink(path: Path): Path = Files.readSymbolicLink(path)
    }
}

private fun same(before: BasicFileAttributes, after: BasicFileAttributes): Boolean =
    before.isDirectory == after.isDirectory && before.isRegularFile == after.isRegularFile
            && before.isSymbolicLink == after.isSymbolicLink
            && before.fileKey() == after.fileKey()
            && before.creationTime() == after.creationTime()
            && (!before.isSymbolicLink || before.lastModifiedTime() == after.lastModifiedTime())

private fun observation(
    path: Path, kind: Kind, target: Path?, generation: Long, diagnostics: List<Diagnostic>,
): CandidateObservation = CandidateObservation(path, kind, target, generation, Instant.now(), false, diagnostics)
