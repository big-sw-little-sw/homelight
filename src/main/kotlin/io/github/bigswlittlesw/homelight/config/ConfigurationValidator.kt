package io.github.bigswlittlesw.homelight.config

import java.nio.file.Path

/** Validates relationships which make a relocation configuration unsafe. */
object ConfigurationValidator {
    @JvmStatic
    fun validate(draft: ConfigurationDraft) {
        if (draft.relocations.isEmpty()) throw IllegalArgumentException("Choose at least one relocation")
        validate(draft.relocations)
    }

    @JvmStatic
    fun validate(relocations: List<Relocation>) {
        val targets = HashSet<Path>()
        for (relocation in relocations) {
            val source = normalized(relocation.sourcePath)
            val target = normalized(relocation.targetPath)
            if (intersects(source, target)) throw IllegalArgumentException("source and target paths overlap: $source")
            if (!targets.add(target)) throw IllegalArgumentException("duplicate target path: $target")
            if (relocation.whenAdoptingTarget.filter { WhenAdoptingTarget.ARCHIVE_SOURCE == it }.isPresent
                && relocation.sourceArchiveRoot.isEmpty
            ) {
                throw IllegalArgumentException("archive-source requires source-archive-root")
            }
        }
        for (left in relocations) for (right in relocations) {
            if (left === right) continue
            if (intersects(left.sourcePath, right.sourcePath) || intersects(left.sourcePath, right.targetPath)
                || intersects(left.targetPath, right.sourcePath) || intersects(left.targetPath, right.targetPath)
            ) {
                throw IllegalArgumentException("relocation paths overlap: " + left.sourcePath + " and " + right.sourcePath)
            }
        }
    }

    private fun intersects(left: Path, right: Path): Boolean {
        val first = normalized(left)
        val second = normalized(right)
        return first.startsWith(second) || second.startsWith(first)
    }

    private fun normalized(path: Path): Path = path.toAbsolutePath().normalize()
}
