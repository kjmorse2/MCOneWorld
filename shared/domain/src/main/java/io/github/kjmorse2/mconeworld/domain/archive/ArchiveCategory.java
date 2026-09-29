// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.archive;

/**
 * A category of per-player season progression that can be archived and then reset. Constant names
 * are persisted, so never rename them. How each category is serialized is a separate, later
 * decision (player item serialization ADR).
 */
public enum ArchiveCategory {
  /** Main inventory, armor, and offhand. */
  INVENTORY,
  /** Ender chest contents. */
  ENDER_CHEST,
  /** Experience level and points. */
  EXPERIENCE,
  /** Advancement progress. */
  ADVANCEMENTS,
  /** Statistics. */
  STATISTICS,
  /** Unlocked recipes. */
  RECIPES
}
