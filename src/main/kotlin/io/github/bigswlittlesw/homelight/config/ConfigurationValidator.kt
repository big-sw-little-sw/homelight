package io.github.bigswlittlesw.homelight.config

import java.nio.file.Path

/** Rejects a draft with no relocations or whose relocations are unsafe together; see [relocationProblem]. */
fun validateConfiguration(draft: ConfigurationDraft) {
    require(draft.relocations.isNotEmpty()) { "Choose at least one relocation" }
    relocationProblem(draft.relocations)?.let { throw IllegalArgumentException(it.message) }
}

/** A broken relocation rule, reported against the [source] path of the relocation it concerns. */
internal data class RelocationProblem(val source: Path, val message: String)

/**
 * The first problem that makes `relocations` unsafe together, or null.
 *
 * Each relocation is checked alone first: its source and target must not overlap, and archive-source needs an
 * archive root. Then each pair is checked in order: no shared target, and no overlap among any of their paths.
 * A pair is reported against its earlier relocation. A shared target is also an overlap; it only gets a clearer
 * message.
 */
internal fun relocationProblem(relocations: List<Relocation>): RelocationProblem? {
    for (relocation in relocations) {
        val source = normalized(relocation.sourcePath)
        if (intersects(source, relocation.targetPath)) {
            return RelocationProblem(relocation.sourcePath, "source and target paths overlap: $source")
        }
        if (lacksArchiveRoot(relocation.whenAdoptingTarget, relocation.sourceArchiveRoot)) {
            return RelocationProblem(relocation.sourcePath, "archive-source requires source-archive-root")
        }
    }
    for ((index, left) in relocations.withIndex()) {
        for (right in relocations.drop(index + 1)) {
            val target = normalized(left.targetPath)
            if (target == normalized(right.targetPath)) {
                return RelocationProblem(left.sourcePath, "duplicate target path: $target")
            }
            if (intersects(left.sourcePath, right.sourcePath) || intersects(left.sourcePath, right.targetPath)
                || intersects(left.targetPath, right.sourcePath) || intersects(left.targetPath, right.targetPath)
            ) {
                return RelocationProblem(left.sourcePath, "relocation paths overlap: ${left.sourcePath} and ${right.sourcePath}")
            }
        }
    }
    return null
}

/** Whether one path contains the other, compared absolute and normalized. */
internal fun intersects(left: Path, right: Path): Boolean {
    val first = normalized(left)
    val second = normalized(right)
    return first.startsWith(second) || second.startsWith(first)
}

private fun normalized(path: Path): Path = path.toAbsolutePath().normalize()
