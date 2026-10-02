// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.world.layout;

/** How a world arranges its dimension directories. */
public enum LayoutKind {
  /**
   * Java 26.1+: every dimension, vanilla ones included, in {@code dimensions/<namespace>/<path>/}.
   * The only layout verified for writing (ADR 0010, {@code world-format-26.2.md}).
   */
  MODERN,

  /**
   * Before 26.1: the Overworld at the world root, the Nether in {@code DIM-1}, the End in {@code
   * DIM1}.
   */
  LEGACY,

  /** Both layouts are present, so which one Minecraft uses is unclear. Inspect-only. */
  MIXED,

  /** No dimension directory was found. */
  NONE
}
