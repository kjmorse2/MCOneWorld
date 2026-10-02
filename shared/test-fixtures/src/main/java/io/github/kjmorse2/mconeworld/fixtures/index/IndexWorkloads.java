// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.fixtures.index;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProtection;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProvenance;
import io.github.kjmorse2.mconeworld.domain.provenance.Confidence;
import io.github.kjmorse2.mconeworld.domain.provenance.EvidenceSource;
import io.github.kjmorse2.mconeworld.domain.provenance.FirstObservation;
import io.github.kjmorse2.mconeworld.domain.provenance.ProvenanceEvidence;
import io.github.kjmorse2.mconeworld.domain.provenance.SeasonActivity;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndex;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexBatch;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexException;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkMutation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.SplittableRandom;

/** Shared, deterministic ways to load and update indexes, used by tests and the harness alike. */
public final class IndexWorkloads {
  private static final ProvenanceEvidence RUNTIME =
      new ProvenanceEvidence(EvidenceSource.RUNTIME_OBSERVATION, Confidence.HIGH);

  private IndexWorkloads() {}

  /**
   * Imports every record of {@code world} in region order as {@link ChunkMutation.Replace}
   * mutations, committing every {@code batchSize} records into each of {@code indexes}. Returns the
   * number of records.
   */
  public static long bulkLoad(SyntheticWorld world, int batchSize, ChunkIndex... indexes)
      throws ChunkIndexException {
    long total = 0;
    List<ChunkMutation> batch = new ArrayList<>(batchSize);
    for (DimensionKey dimension : world.dimensions()) {
      for (Iterator<ChunkProvenance> it = world.records(dimension); it.hasNext(); ) {
        batch.add(new ChunkMutation.Replace(it.next()));
        total++;
        if (batch.size() == batchSize) {
          applyAll(batch, indexes);
          batch.clear();
        }
      }
    }
    if (!batch.isEmpty()) {
      applyAll(batch, indexes);
    }
    return total;
  }

  /** Applies {@code mutations} as one committed batch to each index. */
  public static void applyAll(List<ChunkMutation> mutations, ChunkIndex... indexes)
      throws ChunkIndexException {
    for (ChunkIndex index : indexes) {
      try (ChunkIndexBatch batch = index.begin()) {
        for (ChunkMutation mutation : mutations) {
          batch.apply(mutation);
        }
        batch.commit();
      }
    }
  }

  /**
   * A batch of runtime-like updates that every mutation can apply without rejection: 70 % activity
   * on existing chunks in {@code season}, 20 % first observations near explored terrain, 5 %
   * protection changes on existing chunks, and 5 % removals of existing chunks. Activity and
   * protection target chunks that exist in the generated world and were not removed; the caller
   * keeps {@code removed} across batches.
   */
  public static List<ChunkMutation> updateBatch(
      SyntheticWorld world,
      SplittableRandom random,
      int size,
      SeasonId season,
      Instant at,
      java.util.Set<ChunkPosition> removed) {
    List<ChunkMutation> out = new ArrayList<>(size);
    java.util.Set<ChunkPosition> touched = new java.util.HashSet<>();
    while (out.size() < size) {
      int roll = random.nextInt(100);
      if (roll < 20) {
        ChunkPosition chunk = world.sampleNearby(random);
        if (removed.contains(chunk)) {
          continue;
        }
        out.add(
            new ChunkMutation.ObserveFirst(
                chunk, new FirstObservation.InSeason(season, at, RUNTIME)));
        touched.add(chunk);
        continue;
      }
      ChunkPosition chunk = world.sampleExisting(random);
      if (removed.contains(chunk) || !touched.add(chunk)) {
        continue;
      }
      if (roll < 90) {
        out.add(
            new ChunkMutation.RecordActivity(
                chunk, new SeasonActivity(season, at.plusMillis(random.nextInt(60_000)), RUNTIME)));
      } else if (roll < 95) {
        out.add(
            new ChunkMutation.SetProtection(
                chunk, random.nextBoolean() ? ChunkProtection.PROTECTED : ChunkProtection.NONE));
      } else {
        out.add(new ChunkMutation.Remove(chunk));
        removed.add(chunk);
      }
    }
    return out;
  }
}
