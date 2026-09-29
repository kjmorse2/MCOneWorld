// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.manifest;

import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonReadLimits;

/**
 * Resource bounds for decoding a manifest. They are configurable, but only within hard maxima,
 * which are enforced at construction.
 *
 * @param maxDocumentBytes maximum manifest size in bytes (hard max 256 MiB)
 * @param maxNestingDepth maximum JSON nesting depth (hard max 64)
 * @param maxStringChars maximum length of any string or member name (hard max 1 Mi chars)
 * @param maxArrayElements maximum elements in one array (hard max 10 M)
 * @param maxObjectMembers maximum members in one object (hard max 1 M)
 */
public record ManifestLimits(
    long maxDocumentBytes,
    int maxNestingDepth,
    int maxStringChars,
    int maxArrayElements,
    int maxObjectMembers) {

  /**
   * Defaults: 16 MiB, depth 32, 8192 chars, 100 000 elements, 10 000 members. That is far beyond a
   * real manifest, which holds tens of seasons and a bounded number of records; per-chunk data
   * lives in chunk indexes.
   */
  public static final ManifestLimits DEFAULT =
      new ManifestLimits(16L * 1024 * 1024, 32, 8192, 100_000, 10_000);

  /**
   * @throws IllegalArgumentException if any value is non-positive or above its hard maximum
   */
  public ManifestLimits {
    new JsonReadLimits(
        maxDocumentBytes, maxNestingDepth, maxStringChars, maxArrayElements, maxObjectMembers);
  }

  JsonReadLimits toJsonLimits() {
    return new JsonReadLimits(
        maxDocumentBytes, maxNestingDepth, maxStringChars, maxArrayElements, maxObjectMembers);
  }
}
