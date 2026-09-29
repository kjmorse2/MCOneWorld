// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.internal.json;

/**
 * Bounds applied while reading JSON. Every value must lie within the hard maxima below, so no
 * configuration can disable the protection.
 *
 * @param maxDocumentBytes maximum input size in bytes
 * @param maxNestingDepth maximum array and object nesting depth
 * @param maxStringChars maximum length of any string or member name, in UTF-16 chars
 * @param maxArrayElements maximum elements in one array
 * @param maxObjectMembers maximum members in one object
 */
public record JsonReadLimits(
    long maxDocumentBytes,
    int maxNestingDepth,
    int maxStringChars,
    int maxArrayElements,
    int maxObjectMembers) {

  public static final long HARD_MAX_DOCUMENT_BYTES = 256L * 1024 * 1024;
  public static final int HARD_MAX_NESTING_DEPTH = 64;
  public static final int HARD_MAX_STRING_CHARS = 1 << 20;
  public static final int HARD_MAX_ARRAY_ELEMENTS = 10_000_000;
  public static final int HARD_MAX_OBJECT_MEMBERS = 1_000_000;

  /**
   * @throws IllegalArgumentException if any limit is non-positive or exceeds its hard maximum
   */
  public JsonReadLimits {
    check("maxDocumentBytes", maxDocumentBytes, HARD_MAX_DOCUMENT_BYTES);
    check("maxNestingDepth", maxNestingDepth, HARD_MAX_NESTING_DEPTH);
    check("maxStringChars", maxStringChars, HARD_MAX_STRING_CHARS);
    check("maxArrayElements", maxArrayElements, HARD_MAX_ARRAY_ELEMENTS);
    check("maxObjectMembers", maxObjectMembers, HARD_MAX_OBJECT_MEMBERS);
  }

  private static void check(String name, long value, long hardMax) {
    if (value < 1 || value > hardMax) {
      throw new IllegalArgumentException(name + " must be in 1.." + hardMax + ", got " + value);
    }
  }
}
