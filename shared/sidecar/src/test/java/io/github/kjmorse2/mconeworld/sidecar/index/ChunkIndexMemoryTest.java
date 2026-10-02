// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.fixtures.index.ChunkDatasets;
import io.github.kjmorse2.mconeworld.fixtures.index.IndexWorkloads;
import io.github.kjmorse2.mconeworld.fixtures.index.SyntheticWorld;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Bounded memory, without timing: a million-record index is loaded, checkpointed, scanned in full,
 * looked up, updated, verified, and reopened inside a JVM capped at 128 MiB (the {@code
 * indexMemoryTest} task). If any step held the whole index, it would run out of memory. Not run by
 * the ordinary {@code test} task.
 */
@Tag("memory")
class ChunkIndexMemoryTest {
  @TempDir Path temp;

  @Test
  void aMillionRecordsFitInASmallHeap() throws Exception {
    assertThat(Runtime.getRuntime().maxMemory()).isLessThanOrEqualTo(160L << 20);
    SyntheticWorld world = ChunkDatasets.named("sparse-exploration", 1.0);
    ChunkIndexOptions options = ChunkIndexOptions.DEFAULT.withCacheBytes(16L << 20);
    long records;
    try (IndexHarness harness = new IndexHarness(temp.resolve("sidecar"))) {
      try (SidecarChunkIndex index = harness.create(options)) {
        records = IndexWorkloads.bulkLoad(world, 5_000, index);
        index.checkpoint();
        long scanned = 0;
        try (ChunkIndexSnapshot reader = index.reader()) {
          for (DimensionKey d : reader.dimensions()) {
            try (ChunkCursor cursor = reader.scan(ChunkQuery.all(d))) {
              while (cursor.next() != null) {
                scanned++;
              }
            }
          }
          SplittableRandom random = new SplittableRandom(1);
          for (int i = 0; i < 20_000; i++) {
            reader.find(world.sampleNearby(random));
          }
        }
        assertThat(scanned).isEqualTo(records);
        SeasonId season = SeasonId.of(UUID.fromString("00000000-0000-4000-8000-0000000000ee"));
        HashSet<ChunkPosition> removed = new HashSet<>();
        SplittableRandom random = new SplittableRandom(2);
        for (int i = 0; i < 20; i++) {
          IndexWorkloads.applyAll(
              IndexWorkloads.updateBatch(world, random, 1_000, season, Instant.EPOCH, removed),
              index);
        }
        index.checkpoint();
        assertThat(index.verify().isValid()).isTrue();
      }
      try (SidecarChunkIndex reopened = harness.open(options);
          ChunkIndexSnapshot reader = reopened.reader()) {
        assertThat(reader.count(ChunkQuery.all(DimensionKey.OVERWORLD))).isPositive();
      }
    }
    assertThat(records).isGreaterThan(1_000_000);
  }
}
