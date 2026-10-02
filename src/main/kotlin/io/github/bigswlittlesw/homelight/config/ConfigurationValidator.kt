package io.github.bigswlittlesw.homelight.config

import java.nio.file.Path

/** Rejects a draft whose relocations are unsafe together: overlapping paths, duplicate targets, or an incomplete archive decision. */
fun validateConfiguration(draft: ConfigurationDraft) {
    val relocations = draft.relocations
    require(relocations.isNotEmpty()) { "Choose at least one relocation" }
    val targets = HashSet<Path>()
    for (relocation in relocations) {
        val source = normalized(relocation.sourcePath)
        val target = normalized(relocation.targetPath)
        require(!intersects(source, target)) { "source and target paths overlap: $source" }
        require(targets.add(target)) { "duplicate target path: $target" }
        require(relocation.whenAdoptingTarget != WhenAdoptingTarget.ARCHIVE_SOURCE || relocation.sourceArchiveRoot != null) {
            "archive-source requires source-archive-root"
        }
    }
    for (left in relocations) for (right in relocations) {
        if (left === right) continue
        require(
            !intersects(left.sourcePath, right.sourcePath) && !intersects(left.sourcePath, right.targetPath)
                    && !intersects(left.targetPath, right.sourcePath) && !intersects(left.targetPath, right.targetPath),
        ) { "relocation paths overlap: " + left.sourcePath + " and " + right.sourcePath }
    }
}

private fun intersects(left: Path, right: Path): Boolean {
    val first = normalized(left)
    val second = normalized(right)
    return first.startsWith(second) || second.startsWith(first)
}

private fun normalized(path: Path): Path = path.toAbsolutePath().normalize()
