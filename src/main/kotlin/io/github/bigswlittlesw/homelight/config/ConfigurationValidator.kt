package io.github.bigswlittlesw.homelight.config

import java.io.IOException
import java.nio.file.Files
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
 * Each relocation is checked alone first: its source and target must not overlap. Then each pair is checked in order: no shared target, and no overlap among any of their paths.
 * A pair is reported against its earlier relocation. A shared target is also an overlap; it only gets a clearer
 * message.
 */
internal fun relocationProblem(relocations: List<Relocation>): RelocationProblem? {
    for (relocation in relocations) {
        val source = normalized(relocation.sourcePath)
        if (intersects(source, relocation.targetPath)) {
            return RelocationProblem(relocation.sourcePath, "source and target paths overlap: $source")
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

/**
 * The overlap that [relocationProblem] finds only after [realSpelling], such as `/home/u/x` and `/var/home/u/x`
 * where `/home` links to `/var/home`, or null.
 *
 * It reads the filesystem, so the loader checks it and the planner, which is pure, does not. Overlap visible in the
 * paths as written is left to the planner, which reports it in the plan as before.
 */
internal fun aliasedRelocationProblem(relocations: List<Relocation>): RelocationProblem? {
    if (relocationProblem(relocations) != null) return null
    val real = relocations.map { relocation ->
        relocation.copy(sourcePath = realSpelling(relocation.sourcePath), targetPath = realSpelling(relocation.targetPath))
    }
    return relocationProblem(real)?.let { problem -> problem.copy(message = "${problem.message} (through a symlink)") }
}

/**
 * [path], absolute and normalized, with its longest existing ancestor replaced by that ancestor's real path, so that
 * different spellings of one place compare equal. The path itself is never followed: a source may be the link that
 * HomeLight created. Components that do not exist yet, and an ancestor that cannot be resolved, stay as written.
 */
internal fun realSpelling(path: Path): Path {
    val absolute = normalized(path)
    val existing = generateSequence(absolute.parent) { it.parent }.firstOrNull { Files.exists(it) } ?: return absolute
    return try {
        existing.toRealPath().resolve(existing.relativize(absolute))
    } catch (_: IOException) {
        absolute
    }
}
