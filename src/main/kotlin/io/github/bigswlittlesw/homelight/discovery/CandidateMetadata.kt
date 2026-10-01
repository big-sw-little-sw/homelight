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
import java.util.Objects
import java.util.Optional

/**
 * Only root resolution, no-follow attributes and raw link text. No directory
 * enumeration or target probes. Rechecks detect replacements best-effort; Java
 * path-based operations do not provide atomic containment under concurrent renames.
 */
// Package-private in Java. Internal keeps it out of the public Kotlin API; its members stay unmangled
// because the Java tests in this package call them.
internal class CandidateMetadata(private val access: Access) {
    constructor() : this(Access())

    @Throws(IOException::class)
    fun anchor(root: Path): Anchor {
        val physical = access.realPath(root)
        val attributes = access.attributes(physical)
        if (!attributes.isDirectory) throw IOException("Root is not a directory: $root")
        return Anchor(root, physical, attributes)
    }

    fun inspect(anchor: Anchor, candidate: Path, generation: Long): CandidateObservation {
        if (!candidate.startsWith(anchor.lexical) || candidate == anchor.lexical) {
            throw IllegalArgumentException("Candidate must be below the chosen root")
        }
        val guards = ArrayList<Guard>()
        guards.add(Guard(anchor.physical, anchor.attributes))
        val diagnostics = ArrayList<Diagnostic>()
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
                val attributes: BasicFileAttributes
                try {
                    attributes = access.attributes(current)
                } catch (e: NoSuchFileException) {
                    checkGuards(guards)
                    checkAnchor(anchor)
                    diagnostics.add(Diagnostic(current, Reason.MISSING, e.toString()))
                    return observation(candidate, Kind.MISSING, Optional.empty(), generation, diagnostics)
                }
                if (attributes.fileKey() == null
                    && diagnostics.stream().noneMatch { d -> d.reason == Reason.ALIAS_UNCERTAINTY }
                ) {
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
                val kind: Kind
                var target: Optional<Path> = Optional.empty()
                if (attributes.isSymbolicLink) {
                    kind = if (leaf) Kind.LINK else Kind.BLOCKED_BY_LINK
                    if (leaf) target = Optional.of(access.readLink(current))
                    diagnostics.add(
                        Diagnostic(
                            current, Reason.SYMLINK_EXCLUDED,
                            "Link destination not inspected",
                        ),
                    )
                } else if (!leaf && !attributes.isDirectory) {
                    kind = Kind.BLOCKED_BY_NON_DIRECTORY
                    diagnostics.add(
                        Diagnostic(
                            current, Reason.NOT_DIRECTORY,
                            "Intermediate component is not a directory",
                        ),
                    )
                } else if (!leaf) {
                    continue
                } else {
                    kind = if (attributes.isDirectory) Kind.DIRECTORY
                    else if (attributes.isRegularFile) Kind.REGULAR_FILE else Kind.OTHER
                }
                checkGuards(guards)
                checkAnchor(anchor)
                return observation(candidate, kind, target, generation, diagnostics)
            }
            throw IllegalArgumentException("Empty relative candidate path")
        } catch (e: Exception) {
            // Java's multi-catch of IOException and SecurityException; anything else propagates.
            if (e !is IOException && e !is SecurityException) throw e
            val reason = if (e is Changed) Reason.CHANGED
            else if (e is AccessDeniedException || e is SecurityException) Reason.ACCESS_DENIED else Reason.IO_ERROR
            diagnostics.add(Diagnostic(candidate, reason, e.toString()))
            return observation(
                candidate, if (e is Changed) Kind.UNKNOWN else Kind.INACCESSIBLE,
                Optional.empty(), generation, diagnostics,
            )
        }
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

    @JvmRecord
    data class Anchor(val lexical: Path, val physical: Path, val attributes: BasicFileAttributes)

    @JvmRecord
    private data class Guard(val path: Path, val attributes: BasicFileAttributes)

    private class Changed(path: Path) : IOException("Filesystem identity changed: $path")

    // Deliberately excludes directory enumeration and file-content operations.
    open class Access {
        @Throws(IOException::class)
        open fun realPath(path: Path): Path = path.toRealPath()

        @Throws(IOException::class)
        open fun attributes(path: Path): BasicFileAttributes =
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)

        @Throws(IOException::class)
        open fun readLink(path: Path): Path = Files.readSymbolicLink(path)
    }

    private companion object {
        fun same(before: BasicFileAttributes, after: BasicFileAttributes): Boolean =
            before.isDirectory == after.isDirectory && before.isRegularFile == after.isRegularFile
                    && before.isSymbolicLink == after.isSymbolicLink
                    && Objects.equals(before.fileKey(), after.fileKey())
                    && before.creationTime() == after.creationTime()
                    && (!before.isSymbolicLink || before.lastModifiedTime() == after.lastModifiedTime())

        fun observation(
            path: Path, kind: Kind, target: Optional<Path>,
            generation: Long, diagnostics: List<Diagnostic>,
        ): CandidateObservation = CandidateObservation(path, kind, target, generation, Instant.now(), false, diagnostics)
    }
}
