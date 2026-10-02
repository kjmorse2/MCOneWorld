// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.world.nbt;

/**
 * Bounds applied while reading NBT.
 *
 * @param maxBytes the most decompressed bytes read before failing; every length is also checked
 *     against what remains of this budget before anything is allocated
 * @param maxDepth the deepest nesting of lists and compounds (Minecraft's own limit is 512)
 */
public record NbtLimits(long maxBytes, int maxDepth) {
  /** For world-level saved data such as {@code level.dat}: 16 MiB, depth 512. */
  public static final NbtLimits WORLD_DATA = new NbtLimits(16L << 20, 512);

  public NbtLimits {
    if (maxBytes < 1 || maxDepth < 1) {
      throw new IllegalArgumentException("Limits must be positive");
    }
  }
}
