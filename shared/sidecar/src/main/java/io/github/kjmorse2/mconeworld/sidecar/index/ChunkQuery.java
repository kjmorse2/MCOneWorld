// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.DomainErrorCode;
import io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkRectangle;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProvenance;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Which records of one dimension a scan returns. All conditions must hold. Results are always in
 * {@link ChunkOrder}.
 *
 * <p>Build queries from {@link #all(DimensionKey)} and the {@code with…} methods. To page, pass the
 * last position returned to {@link #after(ChunkPosition)}: the next scan resumes strictly after it.
 *
 * <p>{@link #matches(ChunkProvenance)} defines the result set for every implementation.
 *
 * @param dimension the dimension scanned; a scan never crosses dimensions
 * @param area if non-null, only chunks inside this rectangle (geometric containment)
 * @param seasons the season-evidence condition
 * @param protection the explicit-protection condition
 * @param startAfter if non-null, only chunks strictly after this one in {@link ChunkOrder}
 */
public record ChunkQuery(
    DimensionKey dimension,
    @Nullable ChunkRectangle area,
    SeasonFilter seasons,
    ProtectionFilter protection,
    @Nullable ChunkPosition startAfter) {

  /**
   * @throws InvalidDomainValueException with {@code DIMENSION_MISMATCH} if {@code area} or {@code
   *     startAfter} is in another dimension
   */
  public ChunkQuery {
    Objects.requireNonNull(dimension, "dimension");
    Objects.requireNonNull(seasons, "seasons");
    Objects.requireNonNull(protection, "protection");
    if (area != null && !area.dimension().equals(dimension)) {
      throw mismatch("area", area.dimension(), dimension);
    }
    if (startAfter != null && !startAfter.dimension().equals(dimension)) {
      throw mismatch("startAfter", startAfter.dimension(), dimension);
    }
  }

  /** Every record in {@code dimension}. */
  public static ChunkQuery all(DimensionKey dimension) {
    return new ChunkQuery(dimension, null, SeasonFilter.any(), ProtectionFilter.ANY, null);
  }

  /** This query restricted to chunks inside {@code newArea}. */
  public ChunkQuery within(ChunkRectangle newArea) {
    return new ChunkQuery(dimension, newArea, seasons, protection, startAfter);
  }

  /** This query with the season condition {@code filter}. */
  public ChunkQuery withSeasons(SeasonFilter filter) {
    return new ChunkQuery(dimension, area, filter, protection, startAfter);
  }

  /** This query with the protection condition {@code filter}. */
  public ChunkQuery withProtection(ProtectionFilter filter) {
    return new ChunkQuery(dimension, area, seasons, filter, startAfter);
  }

  /** This query resuming strictly after {@code position}: the next page. */
  public ChunkQuery after(ChunkPosition position) {
    return new ChunkQuery(dimension, area, seasons, protection, position);
  }

  /** Returns whether {@code record} is in this query's result set. */
  public boolean matches(ChunkProvenance record) {
    ChunkPosition chunk = record.chunk();
    return chunk.dimension().equals(dimension)
        && (area == null || area.contains(chunk))
        && (startAfter == null || ChunkOrder.key(chunk) > ChunkOrder.key(startAfter))
        && seasons.matches(record)
        && protection.matches(record);
  }

  private static InvalidDomainValueException mismatch(
      String what, DimensionKey actual, DimensionKey expected) {
    return new InvalidDomainValueException(
        DomainErrorCode.DIMENSION_MISMATCH,
        "Query " + what + " is in " + actual + ", but the query scans " + expected);
  }
}
