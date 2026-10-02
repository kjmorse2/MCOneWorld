// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.fixtures.index.ChunkDatasets;
import io.github.kjmorse2.mconeworld.fixtures.index.ChunkIndexDifferential;
import io.github.kjmorse2.mconeworld.fixtures.index.ChunkRecordText;
import io.github.kjmorse2.mconeworld.fixtures.index.IndexWorkloads;
import io.github.kjmorse2.mconeworld.fixtures.index.ReferenceChunkIndex;
import io.github.kjmorse2.mconeworld.fixtures.index.SyntheticWorld;
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
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The production index answers exactly like the reference: after a bulk load, while mutations are
 * only logged, after checkpoints and compaction, and after reopening from disk. Covers every
 * dataset shape, including negative and extreme coordinates, legacy chunks, and chunks revisited by
 * many seasons, under several block layouts and with segments small enough to roll.
 */
class SidecarChunkIndexDifferentialTest {
  @TempDir Path temp;

  static Stream<Arguments> cases() {
    List<Arguments> out = new ArrayList<>();
    out.add(Arguments.of("friend-server", 0.5, 4));
    out.add(Arguments.of("negative-coords", 0.25, 1));
    out.add(Arguments.of("revisited", 0.15, 32));
    out.add(Arguments.of("multi-season", 0.1, 4));
    out.add(Arguments.of("dense-square", 0.1, 8));
    out.add(Arguments.of("sparse-exploration", 0.05, 2));
    out.add(Arguments.of("millions", 0.03, 16));
    return out.stream();
  }

  @ParameterizedTest(name = "{0} x{1}, {2} rows per unit")
  @MethodSource("cases")
  void answersLikeTheReference(String dataset, double scale, int rowsPerUnit) throws Exception {
    SyntheticWorld world = ChunkDatasets.named(dataset, scale);
    ChunkIndexOptions options =
        ChunkIndexOptions.DEFAULT
            .withRowsPerUnit(rowsPerUnit)
            .withCheckpointMutations(2_500) // forces checkpoints during the load
            .withSegmentRollBytes(1 << 20) // forces segments to roll
            .withCacheBytes(1 << 20); // forces cache eviction
    ReferenceChunkIndex reference = new ReferenceChunkIndex();
    try (IndexHarness harness = new IndexHarness(temp.resolve("sidecar"))) {
      SidecarChunkIndex index = harness.create(options);
      IndexWorkloads.bulkLoad(world, 1_000, reference, index);
      compare(world, reference, index, 11);

      SeasonId season = SeasonId.of(UUID.fromString("00000000-0000-4000-8000-0000000000ff"));
      SplittableRandom random = new SplittableRandom(99);
      Set<ChunkPosition> removed = new HashSet<>();
      Instant at = Instant.parse("2030-01-01T00:00:00Z");
      for (int i = 0; i < 4; i++) {
        IndexWorkloads.applyAll(
            IndexWorkloads.updateBatch(world, random, 400, season, at, removed), reference, index);
      }
      compare(world, reference, index, 12); // part logged, part checkpointed
      index.checkpoint();
      compare(world, reference, index, 13);
      IndexWorkloads.applyAll(
          IndexWorkloads.updateBatch(world, random, 300, season, at, removed), reference, index);
      index.compact();
      compare(world, reference, index, 14);
      List<String> deleted = index.removeObsoleteFiles();
      compare(world, reference, index, 15);
      assertThat(index.verify().isValid()).as("verify after maintenance %s", deleted).isTrue();
      IndexWorkloads.applyAll(
          IndexWorkloads.updateBatch(world, random, 200, season, at, removed), reference, index);
      index.close();

      try (SidecarChunkIndex reopened = harness.open(options)) {
        compare(world, reference, reopened, 16);
        assertThat(reopened.verify().isValid()).isTrue();
      }
      try (ChunkIndexSnapshot readOnly = harness.indexes(options).openReader();
          var expected = reference.reader()) {
        assertThat(ChunkRecordText.digest(readOnly)).isEqualTo(ChunkRecordText.digest(expected));
      }
    }
  }

  static void compare(
      SyntheticWorld world, ReferenceChunkIndex reference, SidecarChunkIndex index, long seed)
      throws Exception {
    SplittableRandom random = new SplittableRandom(seed);
    List<ChunkPosition> probes = new ArrayList<>();
    for (int i = 0; i < 300; i++) {
      probes.add(i % 2 == 0 ? world.sampleExisting(random) : world.sampleNearby(random));
    }
    try (var expected = reference.reader();
        var actual = index.reader()) {
      for (DimensionKey dimension : world.dimensions()) {
        ChunkIndexDifferential.assertSameAnswers(
            expected,
            actual,
            ChunkIndexDifferential.standardQueries(
                dimension, world.seasons(), probes.subList(0, 24), seed),
            probes,
            97);
      }
      assertThat(ChunkRecordText.digest(actual)).isEqualTo(ChunkRecordText.digest(expected));
    }
  }
}
