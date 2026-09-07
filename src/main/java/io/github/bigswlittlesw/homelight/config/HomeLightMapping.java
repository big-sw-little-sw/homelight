package io.github.bigswlittlesw.homelight.config;

import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "homelight")
interface HomeLightMapping {
    String sourcePath();

    String targetPath();
}
