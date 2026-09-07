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
                new CandidateSource("~/.local/share/uv", "uv-managed Python installations"),
                new CandidateSource("~/.local/share/uv/tools", "uv tools and uvx environments"),
                new CandidateSource("~/.cache/pypoetry", "Poetry cache"),
                new CandidateSource("~/.cache/pdm", "PDM cache"),
                new CandidateSource("~/.cache/virtualenv", "virtualenv cache"),
                new CandidateSource("~/.local/pipx/venvs", "pipx virtual environments"),
                new CandidateSource("~/.cache/go-build", "Go build cache"),
                new CandidateSource("~/.cache/node-gyp", "node-gyp cache"),
                new CandidateSource("~/.yarn/berry/cache", "Yarn Berry cache"),
                new CandidateSource("~/.local/share/pnpm/store", "pnpm package store"),
                new CandidateSource("~/.pnpm-store", "legacy pnpm package store"),
                new CandidateSource("~/.nvm", "Node.js versions managed by nvm"),
                new CandidateSource("~/.bun/install/cache", "Bun package cache"),
                new CandidateSource("~/.cache/JetBrains", "JetBrains caches"),
                new CandidateSource("~/.vscode-server", "VS Code server"));
    }
}
