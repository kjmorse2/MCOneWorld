// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProvenance;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * The mutations logged since the checkpoint, per chunk, in the order they were applied. Immutable:
 * a commit builds a new overlay, so a reader's snapshot never moves. A commit copies the maps of
 * the dimensions it touches, so its cost grows with the overlay, which the checkpoint threshold
 * bounds.
 *
 * <p>A chunk's current record is its checkpoint record with these mutations folded over it through
 * {@link ChunkMutation#applyTo}, the contract's single definition of semantics ({@link #resolve}).
 */
final class Overlay {
  static final Overlay EMPTY = new Overlay(Map.of(), 0);

  private final Map<DimensionKey, NavigableMap<Long, List<ChunkMutation>>> dimensions;
  private final int mutations;

  private Overlay(
      Map<DimensionKey, NavigableMap<Long, List<ChunkMutation>>> dimensions, int mutations) {
    this.dimensions = dimensions;
    this.mutations = mutations;
  }

  int mutations() {
    return mutations;
  }

  boolean isEmpty() {
    return mutations == 0;
  }

  Map<DimensionKey, NavigableMap<Long, List<ChunkMutation>>> dimensions() {
    return dimensions;
  }

  /** The logged chunks of {@code dimension}, by key (empty if none). */
  NavigableMap<Long, List<ChunkMutation>> of(DimensionKey dimension) {
    NavigableMap<Long, List<ChunkMutation>> map = dimensions.get(dimension);
    return map == null ? new TreeMap<>() : map;
  }

  boolean touches(DimensionKey dimension) {
    NavigableMap<Long, List<ChunkMutation>> map = dimensions.get(dimension);
    return map != null && !map.isEmpty();
  }

  @Nullable List<ChunkMutation> get(ChunkPosition chunk) {
    NavigableMap<Long, List<ChunkMutation>> map = dimensions.get(chunk.dimension());
    return map == null ? null : map.get(ChunkOrder.key(chunk));
  }

  /** This overlay plus {@code added}, appended after any mutations already logged per chunk. */
  Overlay plus(Map<ChunkPosition, List<ChunkMutation>> added) {
    Map<DimensionKey, NavigableMap<Long, List<ChunkMutation>>> next = new HashMap<>(dimensions);
    Map<DimensionKey, NavigableMap<Long, List<ChunkMutation>>> copied = new HashMap<>();
    int count = mutations;
    for (Map.Entry<ChunkPosition, List<ChunkMutation>> e : added.entrySet()) {
      DimensionKey d = e.getKey().dimension();
      NavigableMap<Long, List<ChunkMutation>> map = copied.get(d);
      if (map == null) {
        map = new TreeMap<>(dimensions.getOrDefault(d, new TreeMap<>()));
        copied.put(d, map);
        next.put(d, map);
      }
      long key = ChunkOrder.key(e.getKey());
      List<ChunkMutation> merged = new ArrayList<>(map.getOrDefault(key, List.of()));
      merged.addAll(e.getValue());
      map.put(key, List.copyOf(merged));
      count += e.getValue().size();
    }
    return new Overlay(next, count);
  }

  /**
   * Folds {@code mutations} over {@code checkpoint}. They were validated when committed, so a
   * rejection here means the stored data changed underneath them.
   *
   * @throws ChunkIndexException {@code MUTATION_INAPPLICABLE}
   */
  static @Nullable ChunkProvenance resolve(
      @Nullable ChunkProvenance checkpoint, List<ChunkMutation> mutations)
      throws ChunkIndexException {
    ChunkProvenance record = checkpoint;
    for (ChunkMutation mutation : mutations) {
      try {
        record = mutation.applyTo(record);
      } catch (ChunkIndexException e) {
        throw new ChunkIndexException(
            ChunkIndexIssueCodes.MUTATION_INAPPLICABLE,
            "a logged mutation no longer applies to its checkpoint record",
            "chunk " + mutation.chunk(),
            e);
      }
    }
    return record;
  }

  /** Whether a record exists after {@code mutation}, given whether one existed before. */
  static boolean existsAfter(ChunkMutation mutation, boolean before) {
    return switch (mutation) {
      case ChunkMutation.ObserveFirst o -> true;
      case ChunkMutation.Replace r -> true;
      case ChunkMutation.Remove r -> false;
      case ChunkMutation.RecordActivity a -> before;
      case ChunkMutation.SetProtection p -> before;
    };
  }
}
