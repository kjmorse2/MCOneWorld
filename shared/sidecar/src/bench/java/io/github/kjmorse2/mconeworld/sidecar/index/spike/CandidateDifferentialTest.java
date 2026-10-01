// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index.spike;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProtection;
import io.github.kjmorse2.mconeworld.fixtures.index.ChunkDatasets;
import io.github.kjmorse2.mconeworld.fixtures.index.ChunkIndexDifferential;
import io.github.kjmorse2.mconeworld.fixtures.index.ChunkRecordText;
import io.github.kjmorse2.mconeworld.fixtures.index.ReferenceChunkIndex;
import io.github.kjmorse2.mconeworld.fixtures.index.SyntheticWorld;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexBatch;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexException;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexReader;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkMutation;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkQuery;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every candidate must answer exactly like the reference index (after a bulk load, after random
 * updates, and after reopening from disk) before any of its benchmark numbers mean anything.
 */
class CandidateDifferentialTest {
  @TempDir Path temp;

  static Stream<Arguments> cases() {
    List<Arguments> out = new ArrayList<>();
    for (Candidate candidate : Candidate.values()) {
      out.add(Arguments.of(candidate, "friend-server", 0.5));
      out.add(Arguments.of(candidate, "negative-coords", 0.25));
      out.add(Arguments.of(candidate, "revisited", 0.15));
      out.add(Arguments.of(candidate, "multi-season", 0.1));
    }
    return out.stream();
  }

  @ParameterizedTest(name = "{0} on {1} x{2}")
  @MethodSource("cases")
  void answersLikeTheReference(Candidate candidate, String dataset, double scale) throws Exception {
    SyntheticWorld world = ChunkDatasets.named(dataset, scale);
    Path directory = temp.resolve(candidate.id());
    ReferenceChunkIndex reference = new ReferenceChunkIndex();
    SpikeIndex index = candidate.open(directory);
    Workload.bulkLoad(world, 3000, reference, index);
    compare(world, reference, index, 11);

    SeasonId season = SeasonId.of(UUID.fromString("00000000-0000-4000-8000-0000000000ff"));
    SplittableRandom random = new SplittableRandom(99);
    Set<ChunkPosition> removed = new HashSet<>();
    for (int i = 0; i < 4; i++) {
      List<ChunkMutation> batch =
          Workload.updateBatch(
              world, random, 400, season, Instant.parse("2030-01-01T00:00:00Z"), removed);
      Workload.applyAll(batch, reference, index);
    }
    compare(world, reference, index, 12);
    index.verify();
    if (index instanceof RegionLogIndex log) {
      log.checkpoint(); // the overlay folds into blocks without changing any answer
      compare(world, reference, index, 14);
      Workload.applyAll(
          Workload.updateBatch(
              world, random, 300, season, Instant.parse("2030-02-01T00:00:00Z"), removed),
          reference,
          index);
      index.verify();
    }
    index.close();

    try (SpikeIndex reopened = candidate.open(directory)) {
      compare(world, reference, reopened, 13);
      reopened.verify();
    }
  }

  private static void compare(
      SyntheticWorld world, ReferenceChunkIndex reference, SpikeIndex index, long seed)
      throws ChunkIndexException {
    SplittableRandom random = new SplittableRandom(seed);
    List<ChunkPosition> probes = new ArrayList<>();
    for (int i = 0; i < 300; i++) {
      probes.add(i % 2 == 0 ? world.sampleExisting(random) : world.sampleNearby(random));
    }
    try (ChunkIndexReader expected = reference.reader();
        ChunkIndexReader actual = index.reader()) {
      for (DimensionKey dimension : world.dimensions()) {
        List<ChunkQuery> queries =
            ChunkIndexDifferential.standardQueries(
                dimension, world.seasons(), probes.subList(0, 24), seed);
        ChunkIndexDifferential.assertSameAnswers(expected, actual, queries, probes, 97);
      }
      assertThat(ChunkRecordText.digest(actual)).isEqualTo(ChunkRecordText.digest(expected));
    }
  }

  @ParameterizedTest
  @EnumSource(Candidate.class)
  void readersAreSnapshotsAndBatchesAreAtomic(Candidate candidate) throws Exception {
    SyntheticWorld world = ChunkDatasets.named("friend-server", 0.2);
    try (SpikeIndex index = candidate.open(temp.resolve("snap"))) {
      Workload.bulkLoad(world, 500, index);
      ChunkPosition victim = world.sampleExisting(new SplittableRandom(1));
      String before;
      try (ChunkIndexReader snapshot = index.reader()) {
        before = ChunkRecordText.digest(snapshot);
        try (ChunkIndexBatch batch = index.begin()) {
          batch.apply(new ChunkMutation.SetProtection(victim, ChunkProtection.PROTECTED));
          batch.apply(new ChunkMutation.Remove(victim));
          batch.commit();
        }
        assertThat(ChunkRecordText.digest(snapshot)).isEqualTo(before);
        assertThat(snapshot.contains(victim)).isTrue();
      }
      try (ChunkIndexReader after = index.reader()) {
        assertThat(after.contains(victim)).isFalse();
      }
      // A poisoned batch commits nothing.
      ChunkPosition nowhere = new ChunkPosition(DimensionKey.THE_END, 0, 0);
      try (ChunkIndexBatch batch = index.begin()) {
        batch.apply(new ChunkMutation.Remove(world.sampleExisting(new SplittableRandom(2))));
        assertThatThrownBy(
                () -> batch.apply(new ChunkMutation.SetProtection(nowhere, ChunkProtection.NONE)))
            .isInstanceOf(ChunkIndexException.class);
        assertThatThrownBy(batch::commit).isInstanceOf(IllegalStateException.class);
      }
      try (ChunkIndexReader reader = index.reader()) {
        assertThat(reader.contains(world.sampleExisting(new SplittableRandom(2)))).isTrue();
      }
      ChunkIndexBatch first = index.begin();
      assertThatThrownBy(index::begin).isInstanceOf(IllegalStateException.class);
      first.close();
      ChunkIndexReader reader = index.reader();
      reader.close();
      assertThatThrownBy(() -> reader.find(victim)).isInstanceOf(IllegalStateException.class);
    }
  }

  @org.junit.jupiter.api.Test
  void deltaLogIgnoresATornTailAndNeverAppendsAfterIt() throws Exception {
    SyntheticWorld world = ChunkDatasets.named("friend-server", 0.2);
    Path directory = temp.resolve("torn");
    ReferenceChunkIndex reference = new ReferenceChunkIndex();
    SeasonId season = SeasonId.of(UUID.fromString("00000000-0000-4000-8000-0000000000ab"));
    SplittableRandom random = new SplittableRandom(5);
    Set<ChunkPosition> removed = new HashSet<>();
    Instant at = Instant.parse("2030-03-01T00:00:00Z");
    try (RegionLogIndex index = RegionLogIndex.open(directory)) {
      Workload.bulkLoad(world, 400, reference, index);
      Workload.applyAll(
          Workload.updateBatch(world, random, 50, season, at, removed), reference, index);
    }
    Path log;
    try (var files = java.nio.file.Files.list(directory)) {
      log =
          files
              .filter(p -> p.getFileName().toString().startsWith("deltas-"))
              .findFirst()
              .orElseThrow();
    }
    // A crash mid-append leaves a partial frame: length says 1 000 bytes, only 3 follow.
    java.nio.file.Files.write(
        log, new byte[] {0, 0, 3, (byte) 0xE8, 1, 2, 3}, java.nio.file.StandardOpenOption.APPEND);
    try (RegionLogIndex index = RegionLogIndex.open(directory)) {
      compare(world, reference, index, 21); // the torn frame was never acknowledged
      Workload.applyAll(
          Workload.updateBatch(world, random, 50, season, at, removed), reference, index);
      compare(world, reference, index, 22);
      index.verify();
    }
    try (RegionLogIndex index = RegionLogIndex.open(directory)) {
      compare(world, reference, index, 23);
    }
  }
}
