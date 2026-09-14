#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../../.."
if [[ -z "${JAVA_HOME:-}" && -d /Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home ]]; then
  export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
fi
prototype_build=$(mktemp -d "${TMPDIR:-/tmp}/homelight-prototype.XXXXXX")
mvn -o -q dependency:build-classpath -Dmdep.outputFile="$prototype_build/classpath"
prototype_cp=$(<"$prototype_build/classpath")
# Compile the real app sources with exactly two isolated replacements: app overlay and fake session.
rg --files src/main/java -g '*.java' | rg -v '/(HomeLightApp|HomeLightSession)\.java$' > "$prototype_build/sources"
rg --files docs/research/session-c-prototype/anchored -g '*.java' >> "$prototype_build/sources"
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" --release 25 -proc:none -cp "$prototype_cp" -d "$prototype_build" @"$prototype_build/sources"
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" --enable-native-access=ALL-UNNAMED -cp "$prototype_build:$prototype_cp" io.github.bigswlittlesw.homelight.tui.HomeLightApp "$@"
