package io.github.bigswlittlesw.homelight.config;

import java.util.List;

/// Built-in, opt-in candidates for machine-local tool state.
public final class CandidateCatalog {
    private CandidateCatalog() {
    }

    public static List<CandidateSource> defaults() {
        return List.of(
                new CandidateSource("~/.m2", "Maven local repository"),
                new CandidateSource("~/.gradle/caches", "Gradle caches"),
                new CandidateSource("~/.gradle/wrapper", "Gradle wrapper distributions"),
                new CandidateSource("~/.cargo", "Rust toolchain and package state"),
                new CandidateSource("~/.rustup", "Rust toolchains"),
                new CandidateSource("~/.npm", "npm cache"),
                new CandidateSource("~/.cache/yarn", "Yarn cache"),
                new CandidateSource("~/.cache/pnpm", "pnpm cache"),
                new CandidateSource("~/.cache/pip", "pip cache"),
                new CandidateSource("~/.cache/uv", "uv cache"),
                new CandidateSource("~/.cache/go-build", "Go build cache"),
                new CandidateSource("~/.cache/JetBrains", "JetBrains caches"),
                new CandidateSource("~/.vscode-server", "VS Code server"));
    }
}
