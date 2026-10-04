#!/usr/bin/env bash
# Runs the throwaway focus prototype in this terminal. Pass --tree for the TreeElement variant of the left list.
set -euo pipefail
WT="$(cd "$(dirname "$0")/../capture-wt" && pwd)"
CP="$(cd "$WT" && ./gradlew -q printProtoClasspath | tail -1)"
exec java --enable-native-access=ALL-UNNAMED -cp "$CP" io.github.bigswlittlesw.homelight.tui.prototype.FocusPrototypeKt "$@"
