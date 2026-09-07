package io.github.bigswlittlesw.homelight.config;

import io.smallrye.config.ConfigMapping;

import java.util.List;
import java.util.Optional;

@ConfigMapping(prefix = "homelight")
interface HomeLightMapping {
    String targetRoot();

    List<RelocationMapping> relocations();
}

interface RelocationMapping {
    String sourcePath();

    Optional<String> targetPath();
}
