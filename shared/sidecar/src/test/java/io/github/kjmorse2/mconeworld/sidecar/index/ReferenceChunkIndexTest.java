// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import static io.github.kjmorse2.mconeworld.sidecar.index.ChunkMutationTest.CHUNK;
import static io.github.kjmorse2.mconeworld.sidecar.index.ChunkMutationTest.FIRST;
import static io.github.kjmorse2.mconeworld.sidecar.index.ChunkMutationTest.OTHER;
import static io.github.kjmorse2.mconeworld.sidecar.index.ChunkMutationTest.RECORD;
import static io.github.kjmorse2.mconeworld.sidecar.index.ChunkMutationTest.RUNTIME;
import static io.github.kjmorse2.mconeworld.sidecar.index.ChunkMutationTest.S1;
import static io.github.kjmorse2.mconeworld.sidecar.index.ChunkMutationTest.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkRectangle;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkEnvelope;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProtection;
import io.github.kjmorse2.mconeworld.domain.provenance.SeasonActivity;
import io.github.kjmorse2.mconeworld.fixtures.index.ReferenceChunkIndex;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The lifecycle and ownership rules of the {@code sidecar.index} contract, on the reference. */
class ReferenceChunkIndexTest {

  private static ReferenceChunkIndex indexWith(ChunkMutation... mutations) throws Exception {
    ReferenceChunkIndex index = new ReferenceChunkIndex();
    try (ChunkIndexBatch batch = index.begin()) {
      for (ChunkMutation mutation : mutations) {
        batch.apply(mutation);
      }
      batch.commit();
    }
    return index;
  }

  private static List<ChunkPosition> scan(ChunkIndexReader reader, ChunkQuery query)
      throws Exception {
    List<ChunkPosition> out = new ArrayList<>();
    try (ChunkCursor cursor = reader.scan(query)) {
      for (var record = cursor.next(); record != null; record = cursor.next()) {
        out.add(record.chunk());
      }
    }
    return out;
  }

  @Test
  void readersAreSnapshotsOfTheCommittedState() throws Exception {
    try (ReferenceChunkIndex index = indexWith(new ChunkMutation.ObserveFirst(CHUNK, FIRST));
        ChunkIndexReader before = index.reader()) {
      try (ChunkIndexBatch batch = index.begin()) {
        batch.apply(new ChunkMutation.ObserveFirst(OTHER, FIRST));
        batch.apply(new ChunkMutation.Remove(CHUNK));
        try (ChunkIndexReader during = index.reader()) {
          assertThat(during.contains(CHUNK)).isTrue();
          assertThat(during.contains(OTHER)).isFalse();
        }
        batch.commit();
      }
      assertThat(before.find(CHUNK)).contains(RECORD);
      assertThat(before.contains(OTHER)).isFalse();
      try (ChunkIndexReader after = index.reader()) {
        assertThat(after.contains(CHUNK)).isFalse();
        assertThat(after.contains(OTHER)).isTrue();
      }
    }
  }

  @Test
  void anUncommittedBatchChangesNothing() throws Exception {
    try (ReferenceChunkIndex index = indexWith(new ChunkMutation.ObserveFirst(CHUNK, FIRST))) {
      try (ChunkIndexBatch batch = index.begin()) {
        batch.apply(new ChunkMutation.Remove(CHUNK));
      }
      try (ChunkIndexReader reader = index.reader()) {
        assertThat(reader.find(CHUNK)).contains(RECORD);
      }
    }
  }

  @Test
  void mutationsInABatchSeeEarlierOnes() throws Exception {
    SeasonActivity activity = new SeasonActivity(S1, T0, RUNTIME);
    try (ReferenceChunkIndex index =
            indexWith(
                new ChunkMutation.ObserveFirst(CHUNK, FIRST),
                new ChunkMutation.RecordActivity(CHUNK, activity),
                new ChunkMutation.SetProtection(CHUNK, ChunkProtection.PROTECTED));
        ChunkIndexReader reader = index.reader()) {
      assertThat(reader.find(CHUNK))
          .contains(RECORD.withActivity(activity).withProtection(ChunkProtection.PROTECTED));
    }
  }

  @Test
  void aRejectedMutationPoisonsTheWholeBatch() throws Exception {
    try (ReferenceChunkIndex index = new ReferenceChunkIndex()) {
      try (ChunkIndexBatch batch = index.begin()) {
        batch.apply(new ChunkMutation.ObserveFirst(CHUNK, FIRST));
        assertThatThrownBy(
                () ->
                    batch.apply(new ChunkMutation.SetProtection(OTHER, ChunkProtection.PROTECTED)))
            .isInstanceOf(ChunkIndexException.class);
        assertThatThrownBy(() -> batch.apply(new ChunkMutation.Remove(CHUNK)))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(batch::commit).isInstanceOf(IllegalStateException.class);
      }
      try (ChunkIndexReader reader = index.reader()) {
        assertThat(reader.contains(CHUNK)).isFalse();
      }
    }
  }

  @Test
  void oneWriterAtATime() throws Exception {
    try (ReferenceChunkIndex index = new ReferenceChunkIndex()) {
      ChunkIndexBatch first = index.begin();
      assertThatThrownBy(index::begin).isInstanceOf(IllegalStateException.class);
      first.close();
      index.begin().close();
    }
  }

  @Test
  void aCommittedBatchCannotBeReused() throws Exception {
    try (ReferenceChunkIndex index = new ReferenceChunkIndex();
        ChunkIndexBatch batch = index.begin()) {
      batch.commit();
      assertThatThrownBy(batch::commit).isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> batch.apply(new ChunkMutation.Remove(CHUNK)))
          .isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void closingAReaderInvalidatesItsCursors() throws Exception {
    try (ReferenceChunkIndex index = indexWith(new ChunkMutation.ObserveFirst(CHUNK, FIRST))) {
      ChunkIndexReader reader = index.reader();
      ChunkCursor cursor = reader.scan(ChunkQuery.all(DimensionKey.OVERWORLD));
      reader.close();
      assertThatThrownBy(cursor::next).isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> reader.find(CHUNK)).isInstanceOf(IllegalStateException.class);
      cursor.close();
      reader.close();
    }
  }

  @Test
  void closingTheIndexInvalidatesEverything() throws Exception {
    ReferenceChunkIndex index = indexWith(new ChunkMutation.ObserveFirst(CHUNK, FIRST));
    ChunkIndexReader reader = index.reader();
    ChunkCursor cursor = reader.scan(ChunkQuery.all(DimensionKey.OVERWORLD));
    ChunkIndexBatch batch = index.begin();
    index.close();
    assertThatThrownBy(cursor::next).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> reader.find(CHUNK)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> batch.apply(new ChunkMutation.Remove(CHUNK)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(index::reader).isInstanceOf(IllegalStateException.class);
    index.close();
  }

  @Test
  void aClosedCursorCannotBeRead() throws Exception {
    try (ReferenceChunkIndex index = indexWith(new ChunkMutation.ObserveFirst(CHUNK, FIRST));
        ChunkIndexReader reader = index.reader()) {
      ChunkCursor cursor = reader.scan(ChunkQuery.all(DimensionKey.OVERWORLD));
      cursor.close();
      assertThatThrownBy(cursor::next).isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void scansAreRegionMajorAndPageable() throws Exception {
    DimensionKey d = DimensionKey.OVERWORLD;
    List<ChunkPosition> chunks =
        List.of(
            new ChunkPosition(d, 32, -1),
            new ChunkPosition(d, -1, 5),
            new ChunkPosition(d, 0, 0),
            new ChunkPosition(d, -33, 40),
            new ChunkPosition(d, 1, 0));
    List<ChunkMutation> mutations = new ArrayList<>();
    for (ChunkPosition chunk : chunks) {
      mutations.add(new ChunkMutation.ObserveFirst(chunk, FIRST));
    }
    mutations.add(
        new ChunkMutation.ObserveFirst(new ChunkPosition(DimensionKey.THE_NETHER, 0, 0), FIRST));
    try (ReferenceChunkIndex index = indexWith(mutations.toArray(ChunkMutation[]::new));
        ChunkIndexReader reader = index.reader()) {
      // regions: (-2,1) < (-1,0) < (0,0) idx 0 < (0,0) idx 1 < (1,-1)
      List<ChunkPosition> expected =
          List.of(chunks.get(3), chunks.get(1), chunks.get(2), chunks.get(4), chunks.get(0));
      assertThat(scan(reader, ChunkQuery.all(d))).containsExactlyElementsOf(expected);
      assertThat(scan(reader, ChunkQuery.all(d).after(chunks.get(2))))
          .containsExactlyElementsOf(expected.subList(3, 5));
      assertThat(scan(reader, ChunkQuery.all(d).within(new ChunkRectangle(d, -1, -1, 1, 5))))
          .containsExactly(chunks.get(1), chunks.get(2), chunks.get(4));
      assertThat(reader.dimensions())
          .containsExactly(DimensionKey.OVERWORLD, DimensionKey.THE_NETHER);
      assertThat(reader.count(ChunkQuery.all(d))).isEqualTo(5);
      assertThat(reader.envelope(ChunkQuery.all(d)))
          .isEqualTo(new ChunkEnvelope.Bounded(new ChunkRectangle(d, -33, -1, 32, 40)));
      assertThat(reader.envelope(ChunkQuery.all(DimensionKey.THE_END)))
          .isEqualTo(ChunkEnvelope.empty(DimensionKey.THE_END));
    }
  }
}
