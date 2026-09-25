package io.github.bigswlittlesw.homelight.config;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/// Validates relationships which make a relocation configuration unsafe.
public final class ConfigurationValidator {
    private ConfigurationValidator() { }

    public static void validate(ConfigurationDraft draft) {
        if (draft.relocations().isEmpty()) throw new IllegalArgumentException("Choose at least one relocation");
        validate(draft.relocations());
    }

    public static void validate(List<Relocation> relocations) {
        var targets = new HashSet<Path>();
        for (var relocation : relocations) {
            var source = normalized(relocation.sourcePath());
            var target = normalized(relocation.targetPath());
            if (intersects(source, target)) throw new IllegalArgumentException("source and target paths overlap: " + source);
            if (!targets.add(target)) throw new IllegalArgumentException("duplicate target path: " + target);
            if (relocation.whenAdoptingTarget().filter(WhenAdoptingTarget.ARCHIVE_SOURCE::equals).isPresent()
                    && relocation.sourceArchiveRoot().isEmpty()) {
                throw new IllegalArgumentException("archive-source requires source-archive-root");
            }
        }
        for (var left : relocations) for (var right : relocations) {
            if (left == right) continue;
            if (intersects(left.sourcePath(), right.sourcePath()) || intersects(left.sourcePath(), right.targetPath())
                    || intersects(left.targetPath(), right.sourcePath()) || intersects(left.targetPath(), right.targetPath())) {
                throw new IllegalArgumentException("relocation paths overlap: " + left.sourcePath() + " and " + right.sourcePath());
            }
        }
    }

    private static boolean intersects(Path left, Path right) {
        var first = normalized(left);
        var second = normalized(right);
        return first.startsWith(second) || second.startsWith(first);
    }

    private static Path normalized(Path path) { return path.toAbsolutePath().normalize(); }
}
