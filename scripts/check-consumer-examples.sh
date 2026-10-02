#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Compiles and runs the consumer examples in scripts/consumer-examples/ against the shared jars
# only (plus their declared runtime dependencies), with no platform dependencies: the offline tool
# at --release 21, the Paper and Fabric adapters at --release 25. Prompt 11 audit; see
# docs/architecture/shared-release-audit.md. Usage: scripts/check-consumer-examples.sh
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew -q assemble
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
dep() { find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1" -name "$1" | head -1; }
cp="$(ls shared/domain/build/libs/*.jar shared/sidecar/build/libs/*.jar shared/protocol/build/libs/*.jar | tr '\n' ':')"
cp="$cp$(dep jspecify-1.0.1.jar):$(dep jackson-core-3.2.3.jar)"
src=scripts/consumer-examples
javac -Xlint:all -Werror --release 21 -cp "$cp" -d "$work/offline" "$src/OfflineToolConsumer.java"
javac -Xlint:all -Werror --release 25 -cp "$cp" -d "$work/paper" "$src/PaperAdapterConsumer.java"
javac -Xlint:all -Werror --release 25 -cp "$cp" -d "$work/fabric" "$src/FabricAdapterConsumer.java"
mkdir "$work/sidecar"
java -cp "$cp:$work/paper" PaperAdapterConsumer "$work/sidecar"
java -cp "$cp:$work/offline" OfflineToolConsumer "$work/sidecar"
java -cp "$cp:$work/fabric" FabricAdapterConsumer
echo "consumer examples: OK"
