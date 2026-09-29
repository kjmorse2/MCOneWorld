// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.provenance;

import io.github.kjmorse2.mconeworld.domain.DisplayName;
import io.github.kjmorse2.mconeworld.domain.ProtectedRegionId;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkRectangle;
import java.time.Instant;
import java.util.Objects;

/**
 * An explicitly protected chunk area. Destructive operations must not alter chunks inside it
 * without an explicit override. Unlike a {@link ChunkEnvelope}, the rectangle <em>is</em> the
 * authoritative protected area.
 *
 * <p>Protection is independent of provenance. Whether a chunk is protected is {@code
 * provenance.protection() == PROTECTED || anyRegion.protects(chunk)}.
 *
 * @param id stable identity
 * @param name non-unique display name
 * @param area the protected chunks, inclusive, in one dimension
 * @param createdAt when the protection was created, supplied by the caller
 */
public record ProtectedRegion(
    ProtectedRegionId id, DisplayName name, ChunkRectangle area, Instant createdAt) {
  public ProtectedRegion {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(area, "area");
    Objects.requireNonNull(createdAt, "createdAt");
  }

  /** Returns whether this region protects {@code chunk} (same dimension, inside the area). */
  public boolean protects(ChunkPosition chunk) {
    return area.contains(chunk);
  }
}
