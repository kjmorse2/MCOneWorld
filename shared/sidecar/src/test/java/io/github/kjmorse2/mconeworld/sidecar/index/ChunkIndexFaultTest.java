// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.fixtures.index.ChunkDatasets;
import io.github.kjmorse2.mconeworld.fixtures.index.ChunkRecordText;
import io.github.kjmorse2.mconeworld.fixtures.index.IndexWorkloads;
import io.github.kjmorse2.mconeworld.fixtures.index.ReferenceChunkIndex;
import io.github.kjmorse2.mconeworld.fixtures.index.SyntheticWorld;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Interrupted writes at every injected failure point. For each operation, step {@code k} of its
 * filesystem steps fails (for writes, optionally after a prefix of the bytes landed), for every
 * {@code k} until the operation completes. After each failure the handle must refuse further
 * writes, and a reopened index must hold exactly the state before or after the operation, verify
 * cleanly, and accept new commits.
 */
class ChunkIndexFaultTest {
  private static final SeasonId SEASON =
      SeasonId.of(UUID.fromString("00000000-0000-4000-8000-0000000000fa"));
  private static final ChunkIndexOptions OPTIONS =
      ChunkIndexOptions.DEFAULT.withCheckpointMutations(10_000).withSegmentRollBytes(1 << 20);

  @TempDir Path temp;

  /** What the scenario does to the prepared index. */
  enum Operation {
    FIRST_COMMIT_OF_GENERATION,
    APPENDING_COMMIT,
    CHECKPOINT,
    COMPACT,
    REMOVE_OBSOLETE_FILES
  }

  static Stream<Arguments> operations() {
    return Stream.of(Operation.values())
        .flatMap(op -> Stream.of(Arguments.of(op, -1), Arguments.of(op, 7)));
  }

  @ParameterizedTest(name = "{0}, tear {1}")
  @MethodSource("operations")
  void everyFailurePointLeavesTheOldOrTheNewState(Operation operation, int tear) throws Exception {
    SyntheticWorld world = ChunkDatasets.named("friend-server", 0.15);
    for (int k = 1; ; k++) {
      Path dir = temp.resolve(operation + "-" + tear + "-" + k);
      ReferenceChunkIndex reference = new ReferenceChunkIndex();
      Set<ChunkPosition> removed = new HashSet<>();
      try (IndexHarness harness = new IndexHarness(dir)) {
        SidecarChunkIndex index = prepare(operation, world, reference, removed, harness);
        String before = digest(reference);
        List<ChunkMutation> batch =
            IndexWorkloads.updateBatch(
                world,
                new SplittableRandom(k),
                60,
                SEASON,
                Instant.parse("2031-01-01T00:00:00Z"),
                removed);
        boolean mutates =
            operation == Operation.FIRST_COMMIT_OF_GENERATION
                || operation == Operation.APPENDING_COMMIT;
        if (mutates) {
          IndexWorkloads.applyAll(batch, reference);
        }
        String after = digest(reference);

        harness.fs.steps = 0;
        harness.fs.failAtStep = k;
        harness.fs.tearBytes = tear;
        boolean failed = false;
        try {
          run(operation, index, batch);
        } catch (ChunkIndexException e) {
          failed = true;
          harness.fs.failAtStep = 0;
          if (operation == Operation.REMOVE_OBSOLETE_FILES) {
            // Maintenance never touches committed state, so a failure leaves the handle usable.
            run(operation, index, batch);
          } else {
            assertThatThrownBy(() -> run(operation, index, batch))
                .isInstanceOf(ChunkIndexException.class)
                .extracting(t -> ((ChunkIndexException) t).code())
                .isEqualTo(ChunkIndexIssueCodes.HANDLE_BROKEN);
          }
        }
        boolean reachedK = harness.fs.steps >= k;
        harness.fs.failAtStep = 0;
        index.close(); // a crash: nothing is flushed or repaired on the way out

        String state = harness.digestFromDisk();
        assertThat(state)
            .as("step %d of %s: %s", k, operation, harness.fs.stepLog)
            .isIn(before, after);
        try (SidecarChunkIndex reopened = harness.open(OPTIONS)) {
          assertThat(reopened.verify().isValid()).as("verify after step %d", k).isTrue();
          // The index stays usable: one more commit and a checkpoint succeed.
          ReferenceChunkIndex resumed = reference;
          if (!state.equals(after)) {
            resumed = new ReferenceChunkIndex();
            prepareReference(operation, world, resumed);
          }
          List<ChunkMutation> more =
              IndexWorkloads.updateBatch(
                  world,
                  new SplittableRandom(1000 + k),
                  30,
                  SEASON,
                  Instant.parse("2032-01-01T00:00:00Z"),
                  removed);
          IndexWorkloads.applyAll(more, resumed, reopened);
          reopened.checkpoint();
          try (var actual = reopened.reader()) {
            assertThat(ChunkRecordText.digest(actual)).isEqualTo(digest(resumed));
          }
        }
        if (!failed && !reachedK) {
          assertThat(k).as("the operation must have at least one step").isGreaterThan(1);
          return; // every step of the operation has been failed once
        }
      }
    }
  }

  private static String digest(ReferenceChunkIndex reference) throws Exception {
    try (var r = reference.reader()) {
      return ChunkRecordText.digest(r);
    }
  }

  /** The update that {@link #prepare} applies after the bulk load (deterministic). */
  private static List<ChunkMutation> prepareBatch(
      SyntheticWorld world, Set<ChunkPosition> removed) {
    return IndexWorkloads.updateBatch(
        world,
        new SplittableRandom(-1),
        80,
        SEASON,
        Instant.parse("2030-06-01T00:00:00Z"),
        removed);
  }

  /**
   * Brings the index to the starting point of {@code operation}; returns the handle to use, which
   * may be a reopened one.
   */
  private static SidecarChunkIndex prepare(
      Operation operation,
      SyntheticWorld world,
      ReferenceChunkIndex reference,
      Set<ChunkPosition> removed,
      IndexHarness harness)
      throws Exception {
    SidecarChunkIndex index = harness.create(OPTIONS);
    IndexWorkloads.bulkLoad(world, 400, reference, index);
    index.checkpoint();
    if (operation != Operation.FIRST_COMMIT_OF_GENERATION) {
      IndexWorkloads.applyAll(prepareBatch(world, removed), reference, index);
    }
    if (operation == Operation.REMOVE_OBSOLETE_FILES) {
      // A new session writes a new segment; rewriting every record makes the old one obsolete.
      index.checkpoint();
      index.close();
      index = harness.open(OPTIONS);
      List<ChunkMutation> rewriteAll = new java.util.ArrayList<>();
      try (var r = reference.reader()) {
        for (var dimension : r.dimensions()) {
          try (var cursor =
              r.scan(io.github.kjmorse2.mconeworld.sidecar.index.ChunkQuery.all(dimension))) {
            for (var record = cursor.next(); record != null; record = cursor.next()) {
              rewriteAll.add(new ChunkMutation.Replace(record));
            }
          }
        }
      }
      IndexWorkloads.applyAll(rewriteAll, index);
      index.checkpoint();
    }
    return index;
  }

  /** The reference state of {@link #prepare}. */
  private static void prepareReference(
      Operation operation, SyntheticWorld world, ReferenceChunkIndex reference) throws Exception {
    IndexWorkloads.bulkLoad(world, 400, reference);
    if (operation != Operation.FIRST_COMMIT_OF_GENERATION) {
      IndexWorkloads.applyAll(prepareBatch(world, new HashSet<>()), reference);
    }
  }

  private static void run(Operation operation, SidecarChunkIndex index, List<ChunkMutation> batch)
      throws Exception {
    switch (operation) {
      case FIRST_COMMIT_OF_GENERATION, APPENDING_COMMIT -> IndexWorkloads.applyAll(batch, index);
      case CHECKPOINT -> index.checkpoint();
      case COMPACT -> index.compact();
      case REMOVE_OBSOLETE_FILES -> index.removeObsoleteFiles();
    }
  }

  @Test
  void interruptedCreationLeavesNoIndexOrAnEmptyOne() throws Exception {
    for (int k = 1; ; k++) {
      try (IndexHarness harness = new IndexHarness(temp.resolve("create-" + k))) {
        harness.fs.failAtStep = k;
        boolean failed = false;
        try (SidecarChunkIndex created = harness.create(OPTIONS)) {
          assertThat(created.state().generation()).isZero();
        } catch (ChunkIndexException e) {
          failed = true;
        }
        int steps = harness.fs.steps;
        harness.fs.failAtStep = 0;
        if (failed) {
          ChunkIndexStatus status = harness.indexes(OPTIONS).inspect();
          if (status.state() == null) {
            // Never created: creating again succeeds.
            try (SidecarChunkIndex again = harness.create(OPTIONS)) {
              assertThat(again.state().loggedMutations()).isZero();
            }
          } else {
            try (SidecarChunkIndex opened = harness.open(OPTIONS)) {
              assertThat(opened.state().generation()).isZero();
            }
          }
        } else if (steps < k) {
          return;
        }
      }
    }
  }

  @Test
  void aTornLogIsNeverAppendedTo() throws Exception {
    SyntheticWorld world = ChunkDatasets.named("friend-server", 0.1);
    ReferenceChunkIndex reference = new ReferenceChunkIndex();
    try (IndexHarness harness = new IndexHarness(temp.resolve("torn"))) {
      SidecarChunkIndex index = harness.create(OPTIONS);
      IndexWorkloads.bulkLoad(world, 200, reference, index);
      List<ChunkMutation> batch =
          IndexWorkloads.updateBatch(
              world,
              new SplittableRandom(3),
              40,
              SEASON,
              Instant.parse("2031-01-01T00:00:00Z"),
              new HashSet<>());
      harness.fs.steps = 0;
      harness.fs.failAtStep = 1; // the frame write
      harness.fs.tearBytes = 20;
      assertThatThrownBy(() -> IndexWorkloads.applyAll(batch, index))
          .isInstanceOf(ChunkIndexException.class);
      harness.fs.failAtStep = 0;
      index.close();

      ChunkIndexStatus status = harness.indexes(OPTIONS).inspect();
      assertThat(status.findings().has(ChunkIndexIssueCodes.TORN_TAIL)).isTrue();
      long generation = status.state().generation();
      try (SidecarChunkIndex reopened = harness.open(OPTIONS)) {
        IndexWorkloads.applyAll(batch, reference, reopened);
        // The commit checkpointed first, so it went to a fresh log of the next generation.
        assertThat(reopened.state().generation()).isEqualTo(generation + 1);
        try (var expected = reference.reader();
            var actual = reopened.reader()) {
          assertThat(ChunkRecordText.digest(actual)).isEqualTo(ChunkRecordText.digest(expected));
        }
      }
      assertThat(harness.indexes(OPTIONS).inspect().findings().has(ChunkIndexIssueCodes.TORN_TAIL))
          .isFalse();
    }
  }

  @Test
  void readersKeepTheirSnapshotAcrossCommitsAndCheckpoints() throws Exception {
    SyntheticWorld world = ChunkDatasets.named("friend-server", 0.1);
    try (IndexHarness harness = new IndexHarness(temp.resolve("snapshots"))) {
      SidecarChunkIndex index = harness.create(OPTIONS);
      IndexWorkloads.bulkLoad(world, 500, index);
      try (ChunkIndexSnapshot before = index.reader()) {
        String digest = ChunkRecordText.digest(before);
        ChunkPosition chunk = world.sampleExisting(new SplittableRandom(5));
        IndexWorkloads.applyAll(List.of(new ChunkMutation.Remove(chunk)), index);
        index.checkpoint();
        index.compact();
        index.removeObsoleteFiles(); // must keep segments the open reader needs
        assertThat(ChunkRecordText.digest(before)).isEqualTo(digest);
        assertThat(before.contains(chunk)).isTrue();
        try (ChunkIndexSnapshot after = index.reader()) {
          assertThat(after.contains(chunk)).isFalse();
          assertThat(after.state().pin()).isNotEqualTo(before.state().pin());
        }
      }
      index.close();
    }
  }
}
