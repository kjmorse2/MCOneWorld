// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.world.region;

import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import java.util.Objects;

/**
 * One chunk present in a region file's header.
 *
 * @param chunk the chunk's absolute position
 * @param sectorOffset where its data starts, in 4 KiB sectors (2 or more)
 * @param sectorCount how many sectors it occupies (1 or more)
 * @param lastSavedEpochSecond the header's timestamp: when Minecraft last saved it, in epoch
 *     seconds (unsigned 32-bit; 0 if never set)
 */
public record ChunkEntry(
    ChunkPosition chunk, int sectorOffset, int sectorCount, long lastSavedEpochSecond) {
  public ChunkEntry {
    Objects.requireNonNull(chunk, "chunk");
  }
}
