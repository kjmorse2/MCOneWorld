// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.fixtures.index.ChunkDatasets;
import io.github.kjmorse2.mconeworld.fixtures.index.ChunkRecordText;
import io.github.kjmorse2.mconeworld.fixtures.index.IndexWorkloads;
import io.github.kjmorse2.mconeworld.fixtures.index.ReferenceChunkIndex;
import io.github.kjmorse2.mconeworld.fixtures.index.SyntheticWorld;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Thread interruption (Prompt 11 audit). A server interrupts worker threads, for example at
 * shutdown, and an interrupted thread's NIO channel operations fail with {@code
 * ClosedByInterruptException}, which also closes the channel. Interrupting the writer at every step
 * of a commit or checkpoint must leave the old or the new state, like any other failure; and an
 * interrupted reader must not break other readers.
 */
class ChunkIndexInterruptTest {
  private static final SeasonId SEASON =
      SeasonId.of(UUID.fromString("00000000-0000-4000-8000-0000000000fb"));
  private static final ChunkIndexOptions OPTIONS =
      ChunkIndexOptions.DEFAULT.withCheckpointMutations(10_000).withSegmentRollBytes(1 << 20);

  @TempDir Path temp;

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"commit", "checkpoint"})
  void anInterruptAtEveryStepLeavesTheOldOrTheNewState(String operation) throws Exception {
    SyntheticWorld world = ChunkDatasets.named("friend-server", 0.1);
    for (int k = 1; ; k++) {
      ReferenceChunkIndex reference = new ReferenceChunkIndex();
      Set<ChunkPosition> removed = new HashSet<>();
      try (IndexHarness harness = new IndexHarness(temp.resolve(operation + "-" + k))) {
        SidecarChunkIndex index = harness.create(OPTIONS);
        IndexWorkloads.bulkLoad(world, 400, reference, index);
        index.checkpoint();
        String before = digest(reference);
        List<ChunkMutation> batch =
            IndexWorkloads.updateBatch(
                world,
                new SplittableRandom(k),
                60,
                SEASON,
                Instant.parse("2031-01-01T00:00:00Z"),
                removed);
        if (operation.equals("commit")) {
          IndexWorkloads.applyAll(batch, reference);
        } else {
          IndexWorkloads.applyAll(batch, index);
          IndexWorkloads.applyAll(batch, reference);
          before = digest(reference);
        }
        String after = digest(reference);

        harness.fs.steps = 0;
        harness.fs.interruptAtStep = k;
        boolean failed = false;
        try {
          if (operation.equals("commit")) {
            IndexWorkloads.applyAll(batch, index);
          } else {
            index.checkpoint();
          }
        } catch (IOException e) {
          failed = true;
        } finally {
          boolean wasInterrupted = Thread.interrupted();
          assertThat(wasInterrupted || harness.fs.steps < k || failed)
              .as("the interrupt at step %d was delivered or reported: %s", k, harness.fs.stepLog)
              .isTrue();
        }
        boolean reachedK = harness.fs.steps >= k;
        harness.fs.interruptAtStep = 0;
        index.close();

        String state = harness.digestFromDisk();
        assertThat(state)
            .as("interrupt at step %d of %s: %s", k, operation, harness.fs.stepLog)
            .isIn(before, after);
        try (SidecarChunkIndex reopened = harness.open(OPTIONS)) {
          assertThat(reopened.verify().isValid()).isTrue();
        }
        if (!reachedK) {
          assertThat(k).isGreaterThan(1);
          return;
        }
      }
    }
  }

  @Test
  void anInterruptedReaderDoesNotBreakOtherReadersOrTheWriter() throws Exception {
    SyntheticWorld world = ChunkDatasets.named("friend-server", 0.1);
    ReferenceChunkIndex reference = new ReferenceChunkIndex();
    try (IndexHarness harness = new IndexHarness(temp.resolve("reader"));
        SidecarChunkIndex index = harness.create(OPTIONS)) {
      IndexWorkloads.bulkLoad(world, 400, reference, index);
      index.checkpoint();
      String expected = digest(reference);
      try (ChunkIndexSnapshot interrupted = harness.indexes(OPTIONS).openReader();
          var fromHandle = index.reader()) {
        Thread.currentThread().interrupt();
        try {
          ChunkRecordText.digest(interrupted);
        } catch (IOException e) {
          // Either outcome is fine for the interrupted reader itself.
        } finally {
          Thread.interrupted();
        }
        try {
          ChunkRecordText.digest(fromHandle);
        } catch (IOException e) {
          // The handle's own reader shares its files; it may fail once, but must not stay broken.
        }
      }
      try (var reader = index.reader()) {
        assertThat(ChunkRecordText.digest(reader)).isEqualTo(expected);
      }
      List<ChunkMutation> more =
          IndexWorkloads.updateBatch(
              world,
              new SplittableRandom(7),
              30,
              SEASON,
              Instant.parse("2032-01-01T00:00:00Z"),
              new HashSet<>());
      IndexWorkloads.applyAll(more, reference, index);
      try (var reader = index.reader()) {
        assertThat(ChunkRecordText.digest(reader)).isEqualTo(digest(reference));
      }
      assertThat(harness.digestFromDisk()).isEqualTo(digest(reference));
    }
  }

  private static String digest(ReferenceChunkIndex reference) throws Exception {
    try (var r = reference.reader()) {
      return ChunkRecordText.digest(r);
    }
  }
}
