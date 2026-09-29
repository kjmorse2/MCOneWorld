// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.provenance;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.DomainErrorCode;
import io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkRectangle;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;

/**
 * A <b>derived</b>, conservative bounding summary of some chunks in one dimension (a "season
 * envelope"), used for visualization and planning.
 *
 * <p>An envelope is <b>not a membership set</b>. A chunk inside the bounds is not thereby part of a
 * season, and destructive operations must never select chunks by envelope containment (root
 * AGENTS.md §6, §10). For that reason this type deliberately has no {@code contains} method. Use
 * {@link #boundsMayInclude(ChunkPosition)}, whose name states the weaker meaning.
 *
 * <p>An envelope is either {@link Empty} (no chunks summarized) or {@link Bounded}.
 */
public sealed interface ChunkEnvelope {

  /** The dimension this envelope summarizes. */
  DimensionKey dimension();

  /** The bounding rectangle, or empty for {@link Empty}. */
  Optional<ChunkRectangle> bounds();

  /**
   * Returns whether {@code chunk} lies within the bounding rectangle. This does <b>not</b> mean the
   * chunk is one of the summarized chunks.
   */
  default boolean boundsMayInclude(ChunkPosition chunk) {
    return bounds().map(b -> b.contains(chunk)).orElse(false);
  }

  /** The envelope of no chunks. */
  static ChunkEnvelope empty(DimensionKey dimension) {
    return new Empty(dimension);
  }

  /**
   * Returns the envelope of all chunks produced by {@code chunks}. It uses constant memory, so it
   * is suitable for streaming very large chunk sets.
   *
   * @throws InvalidDomainValueException with {@code DIMENSION_MISMATCH} if any chunk is in another
   *     dimension
   */
  static ChunkEnvelope of(DimensionKey dimension, Iterator<ChunkPosition> chunks) {
    ChunkEnvelope envelope = empty(dimension);
    while (chunks.hasNext()) {
      envelope = envelope.including(chunks.next());
    }
    return envelope;
  }

  /** Returns the envelope widened to include {@code chunk}. */
  default ChunkEnvelope including(ChunkPosition chunk) {
    if (!dimension().equals(chunk.dimension())) {
      throw new InvalidDomainValueException(
          DomainErrorCode.DIMENSION_MISMATCH,
          "Envelope for " + dimension() + " cannot include chunk in " + chunk.dimension());
    }
    return new Bounded(
        bounds().map(b -> b.including(chunk)).orElseGet(() -> ChunkRectangle.of(chunk)));
  }

  /** Returns the envelope covering both envelopes, which must share a dimension. */
  default ChunkEnvelope union(ChunkEnvelope other) {
    if (!dimension().equals(other.dimension())) {
      throw new InvalidDomainValueException(
          DomainErrorCode.DIMENSION_MISMATCH,
          "Cannot union envelopes for " + dimension() + " and " + other.dimension());
    }
    Optional<ChunkRectangle> mine = bounds();
    Optional<ChunkRectangle> theirs = other.bounds();
    if (mine.isEmpty()) {
      return other;
    }
    if (theirs.isEmpty()) {
      return this;
    }
    return new Bounded(mine.get().union(theirs.get()));
  }

  /**
   * No chunks.
   *
   * @param dimension the dimension
   */
  record Empty(DimensionKey dimension) implements ChunkEnvelope {
    public Empty {
      Objects.requireNonNull(dimension, "dimension");
    }

    @Override
    public Optional<ChunkRectangle> bounds() {
      return Optional.empty();
    }
  }

  /**
   * A non-empty bounding rectangle.
   *
   * @param rectangle the bounds; its dimension is the envelope's dimension
   */
  record Bounded(ChunkRectangle rectangle) implements ChunkEnvelope {
    public Bounded {
      Objects.requireNonNull(rectangle, "rectangle");
    }

    @Override
    public DimensionKey dimension() {
      return rectangle.dimension();
    }

    @Override
    public Optional<ChunkRectangle> bounds() {
      return Optional.of(rectangle);
    }
  }
}
