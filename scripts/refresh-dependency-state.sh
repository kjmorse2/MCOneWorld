#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
#
# Regenerates dependency lockfiles (gradle.lockfile, settings-gradle.lockfile) and
# gradle/verification-metadata.xml after a dependency or version-catalog change.
#
# Why a clean copy: Gradle writes verification metadata only for artifacts it actually resolves
# during the run. In a working tree with up-to-date build outputs, tasks such as spotless and
# Error Prone skip resolution, and the metadata comes out silently incomplete, which breaks the
# next clean checkout. So this script copies the tree (minus .git, offline-tool/, and all build
# outputs) to a temporary directory, resolves everything there with --continue (so a compile or
# test failure cannot truncate resolution), and copies the results back.
#
# Review the resulting diff of gradle/verification-metadata.xml before committing: every new
# checksum is a new trusted artifact.
set -euo pipefail

repo="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

(cd "$repo" && tar --exclude=./.git --exclude=./offline-tool --exclude=./.gradle --exclude=./build \
    --exclude=./build-logic/.gradle --exclude=./build-logic/.kotlin --exclude=./build-logic/build \
    --exclude='./shared/*/build' --exclude='./tool/*/build' -cf - .) | tar -xf - -C "$work"

rm -f "$work/gradle/verification-metadata.xml"
(cd "$work" && ./gradlew --no-configuration-cache --continue --write-verification-metadata sha256 \
    resolveAndLockAll --write-locks sharedCheck toolCheck) > "$work/refresh.log" 2>&1 || true

if ! grep -q '<component ' "$work/gradle/verification-metadata.xml" 2>/dev/null; then
  echo "Verification metadata was not produced; see log:" >&2
  tail -40 "$work/refresh.log" >&2
  exit 1
fi
if grep -q 'not part of the dependency lock state' "$work/refresh.log"; then
  echo "Lock state is still incomplete; see log:" >&2
  grep -m5 'not part of the dependency lock state' "$work/refresh.log" >&2
  exit 1
fi

cp "$work/gradle/verification-metadata.xml" "$repo/gradle/verification-metadata.xml"
(cd "$work" && find . -name '*.lockfile' -not -path './offline-tool/*') | while read -r f; do
  cp "$work/$f" "$repo/$f"
done
echo "Refreshed $(grep -c '<component ' "$repo/gradle/verification-metadata.xml") verified components and lockfiles."
echo "Build result in the clean copy (failures other than dependency state are for you to fix):"
grep -E 'BUILD (SUCCESSFUL|FAILED)|FAILED$' "$work/refresh.log" | head -10 || true
