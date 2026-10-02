// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProvenance;
import io.github.kjmorse2.mconeworld.fixtures.index.ChunkDatasets;
import io.github.kjmorse2.mconeworld.fixtures.index.ChunkRecordText;
import io.github.kjmorse2.mconeworld.fixtures.index.IndexWorkloads;
import io.github.kjmorse2.mconeworld.fixtures.index.ReferenceChunkIndex;
import io.github.kjmorse2.mconeworld.fixtures.index.SyntheticWorld;
import io.github.kjmorse2.mconeworld.sidecar.storage.LockException;
import io.github.kjmorse2.mconeworld.sidecar.storage.LockOwner;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarRoot;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarStore;
import io.github.kjmorse2.mconeworld.sidecar.storage.StaleLockPolicy;
import io.github.kjmorse2.mconeworld.sidecar.storage.StorageIssueCodes;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Locking, claims, readers, export, and the deterministic resource bounds of the index. */
class ChunkIndexLifecycleTest {
  private static final SeasonId SEASON =
      SeasonId.of(UUID.fromString("00000000-0000-4000-8000-0000000000dd"));

  @TempDir Path temp;

  // ---- locking and claims ---------------------------------------------------------------------

  @Test
  void aSecondWriterCannotTakeTheLockButReadersStillWork() throws Exception {
    SyntheticWorld world = ChunkDatasets.named("friend-server", 0.05);
    try (IndexHarness harness = new IndexHarness(temp.resolve("sidecar"));
        SidecarChunkIndex index = harness.create(ChunkIndexOptions.DEFAULT)) {
      IndexWorkloads.bulkLoad(world, 100, index);
      SidecarStore other = SidecarStore.open(SidecarRoot.open(harness.root));
      assertThatThrownBy(
              () ->
                  other.openWriter(
                      new LockOwner("other", "1"), IndexHarness.CLOCK, StaleLockPolicy.REFUSE))
          .isInstanceOf(LockException.class);
      try (ChunkIndexSnapshot reader = ChunkIndexStore.of(other).openReader()) {
        assertThat(reader.state().loggedMutations()).isEqualTo(index.loggedMutations());
      }
    }
  }

  @Test
  void oneIndexHandlePerWriterAndClosingReleasesTheClaim() throws Exception {
    try (IndexHarness harness = new IndexHarness(temp.resolve("sidecar"))) {
      SidecarChunkIndex first = harness.create(ChunkIndexOptions.DEFAULT);
      assertThatThrownBy(() -> harness.open(ChunkIndexOptions.DEFAULT))
          .isInstanceOf(IllegalStateException.class);
      first.close();
      try (SidecarChunkIndex second = harness.open(ChunkIndexOptions.DEFAULT)) {
        assertThat(second.state().generation()).isZero();
      }
      assertThatThrownBy(() -> harness.create(ChunkIndexOptions.DEFAULT))
          .isInstanceOf(ChunkIndexException.class)
          .extracting(t -> ((ChunkIndexException) t).code())
          .isEqualTo(ChunkIndexIssueCodes.INDEX_EXISTS);
    }
  }

  @Test
  void aLostLockStopsEveryWrite() throws Exception {
    try (IndexHarness harness = new IndexHarness(temp.resolve("sidecar"));
        SidecarChunkIndex index = harness.create(ChunkIndexOptions.DEFAULT)) {
      Files.delete(harness.root.resolve(SidecarRoot.LOCK_OWNER)); // someone removed our claim
      ChunkPosition chunk = new ChunkPosition(DimensionKey.OVERWORLD, 1, 1);
      assertThatThrownBy(
              () ->
                  IndexWorkloads.applyAll(
                      List.of(
                          new ChunkMutation.ObserveFirst(
                              chunk,
                              new io.github.kjmorse2.mconeworld.domain.provenance.FirstObservation
                                  .Legacy(
                                  Instant.EPOCH,
                                  new io.github.kjmorse2.mconeworld.domain.provenance
                                      .ProvenanceEvidence(
                                      io.github.kjmorse2.mconeworld.domain.provenance.EvidenceSource
                                          .OFFLINE_SCAN,
                                      io.github.kjmorse2.mconeworld.domain.provenance.Confidence
                                          .LOW)))),
                      index))
          .isInstanceOf(ChunkIndexException.class)
          .extracting(t -> ((ChunkIndexException) t).code())
          .isEqualTo(StorageIssueCodes.LOCK_LOST);
    }
  }

  @Test
  void openingWithoutAnIndexIsRefusedAndNothingIsCreatedImplicitly() throws Exception {
    try (IndexHarness harness = new IndexHarness(temp.resolve("sidecar"))) {
      assertThatThrownBy(() -> harness.open(ChunkIndexOptions.DEFAULT))
          .isInstanceOf(ChunkIndexException.class)
          .extracting(t -> ((ChunkIndexException) t).code())
          .isEqualTo(ChunkIndexIssueCodes.INDEX_MISSING);
      assertThat(harness.root.resolve("chunk-index")).doesNotExist();
      assertThat(harness.indexes(ChunkIndexOptions.DEFAULT).inspect().state()).isNull();
    }
  }

  // ---- readers ---------------------------------------------------------------------------------

  @Test
  void concurrentReadersSeeConsistentCommittedStatesWhileTheWriterCommits() throws Exception {
    SyntheticWorld world = ChunkDatasets.named("friend-server", 0.1);
    ReferenceChunkIndex reference = new ReferenceChunkIndex();
    List<String> committedStates = java.util.Collections.synchronizedList(new ArrayList<>());
    try (IndexHarness harness = new IndexHarness(temp.resolve("sidecar"));
        SidecarChunkIndex index =
            harness.create(ChunkIndexOptions.DEFAULT.withCheckpointMutations(150))) {
      IndexWorkloads.bulkLoad(world, 200, reference, index);
      committedStates.add(digest(reference));
      ChunkIndexStore crossProcess = IndexStores.readOnly(harness.root);
      ExecutorService pool = Executors.newFixedThreadPool(3);
      try {
        List<Future<List<String>>> seen = new ArrayList<>();
        for (int t = 0; t < 3; t++) {
          boolean sameProcess = t == 0;
          seen.add(
              pool.submit(
                  () -> {
                    List<String> digests = new ArrayList<>();
                    for (int i = 0; i < 25; i++) {
                      try (ChunkIndexSnapshot r =
                          sameProcess ? index.reader() : crossProcess.openReader()) {
                        digests.add(ChunkRecordText.digest(r));
                      }
                    }
                    return digests;
                  }));
        }
        SplittableRandom random = new SplittableRandom(4);
        HashSet<ChunkPosition> removed = new HashSet<>();
        for (int i = 0; i < 12; i++) {
          IndexWorkloads.applyAll(
              IndexWorkloads.updateBatch(
                  world, random, 40, SEASON, Instant.parse("2031-01-01T00:00:00Z"), removed),
              reference,
              index);
          committedStates.add(digest(reference));
        }
        for (Future<List<String>> f : seen) {
          assertThat(committedStates).containsAll(f.get());
        }
      } finally {
        pool.shutdownNow();
      }
    }
  }

  private static String digest(ReferenceChunkIndex reference) throws Exception {
    try (var r = reference.reader()) {
      return ChunkRecordText.digest(r);
    }
  }

  @Test
  void closingTheIndexInvalidatesReadersCursorsAndBatches() throws Exception {
    try (IndexHarness harness = new IndexHarness(temp.resolve("sidecar"))) {
      SidecarChunkIndex index = harness.create(ChunkIndexOptions.DEFAULT);
      ChunkIndexSnapshot reader = index.reader();
      ChunkCursor cursor =
          reader.scan(
              io.github.kjmorse2.mconeworld.sidecar.index.ChunkQuery.all(DimensionKey.OVERWORLD));
      ChunkIndexBatch batch = index.begin();
      index.close();
      assertThatThrownBy(cursor::next).isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(reader::dimensions).isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(
              () ->
                  batch.apply(
                      new ChunkMutation.Remove(new ChunkPosition(DimensionKey.OVERWORLD, 0, 0))))
          .isInstanceOf(IllegalStateException.class);
      assertThat(harness.fs.openHandles.get()).as("every file handle is closed").isZero();
    }
  }

  // ---- export -----------------------------------------------------------------------------------

  @Test
  void exportRoundTripsAtTheSemanticLevelAndIsDeterministic() throws Exception {
    SyntheticWorld world = ChunkDatasets.named("revisited", 0.05);
    ReferenceChunkIndex reference = new ReferenceChunkIndex();
    byte[] first;
    try (IndexHarness harness = new IndexHarness(temp.resolve("a"));
        SidecarChunkIndex index = harness.create(ChunkIndexOptions.DEFAULT)) {
      IndexWorkloads.bulkLoad(world, 300, reference, index);
      IndexWorkloads.applyAll(
          IndexWorkloads.updateBatch(
              world,
              new SplittableRandom(1),
              50,
              SEASON,
              Instant.parse("2031-01-01T00:00:00Z"),
              new HashSet<>()),
          reference,
          index);
      first = export(index);
    }
    // Import into a fresh index with different batching and checkpoints, then export again.
    List<ChunkMutation> imported = new ArrayList<>();
    ChunkIndexExport.Reader in = ChunkIndexExport.read(new ByteArrayInputStream(first));
    assertThat(in.indexId()).isEqualTo(IndexHarness.INDEX_ID);
    for (ChunkProvenance r = in.next(); r != null; r = in.next()) {
      imported.add(new ChunkMutation.Replace(r));
    }
    try (var expected = reference.reader()) {
      assertThat(imported)
          .hasSize(
              (int)
                  expected.count(
                      io.github.kjmorse2.mconeworld.sidecar.index.ChunkQuery.all(
                          DimensionKey.OVERWORLD)));
    }
    try (IndexHarness harness = new IndexHarness(temp.resolve("b"));
        SidecarChunkIndex copy =
            harness.create(
                ChunkIndexOptions.DEFAULT.withRowsPerUnit(32).withCheckpointMutations(97))) {
      for (int i = 0; i < imported.size(); i += 113) {
        IndexWorkloads.applyAll(imported.subList(i, Math.min(imported.size(), i + 113)), copy);
      }
      byte[] second = export(copy);
      assertThat(recordLines(second)).isEqualTo(recordLines(first));
      try (var actual = copy.reader();
          var expected = reference.reader()) {
        assertThat(ChunkRecordText.digest(actual)).isEqualTo(ChunkRecordText.digest(expected));
      }
    }
  }

  private static byte[] export(SidecarChunkIndex index) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ChunkIndexSnapshot reader = index.reader()) {
      ChunkIndexExport.write(reader, out);
    }
    return out.toByteArray();
  }

  private static List<String> recordLines(byte[] export) {
    List<String> lines = List.of(new String(export, StandardCharsets.UTF_8).split("\n", -1));
    return lines.subList(1, lines.size());
  }

  @Test
  void theExportReaderIsStrict() {
    String header =
        "{\"format\":\"mconeworld-chunk-index-export\",\"generation\":0,\"indexId\":\""
            + UUID.randomUUID()
            + "\",\"logBytes\":36,\"version\":\"1.0\"}\n";
    assertThatThrownBy(() -> read(header + "{\"x\":1}\n"))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("line 2");
    assertThatThrownBy(() -> read(header.replace("1.0", "2.0"))).isInstanceOf(IOException.class);
    assertThatThrownBy(() -> read(header.trim()))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("no LF");
  }

  /**
   * Regression (Prompt 11 audit): the reader used to accept these, which the writer never emits.
   */
  @Test
  void theExportReaderAcceptsOnlyWhatTheWriterWrites() throws IOException {
    String golden = new String(goldenExport(), StandardCharsets.UTF_8);
    read(golden);
    List<String> lines = new java.util.ArrayList<>(List.of(golden.split("\n")));
    String header = lines.get(0);
    String uuid = header.replaceAll(".*\"indexId\":\"([^\"]+)\".*", "$1");
    assertRejected(golden.replace(uuid, uuid.toUpperCase(java.util.Locale.ROOT)), "canonical");
    String time = lines.get(1).replaceAll(".*\"observedAt\":\"([^\"]+)Z\".*", "$1");
    assertRejected(golden.replace(time + "Z", time + ".000Z"), "canonical");
    assertRejected(golden.replace(time + "Z", time + "+00:00"), "canonical");
    List<String> duplicated = new java.util.ArrayList<>(lines);
    duplicated.add(2, lines.get(2));
    assertRejected(String.join("\n", duplicated) + "\n", "ascending");
    List<String> swapped = new java.util.ArrayList<>(lines);
    java.util.Collections.swap(swapped, 2, 3);
    assertRejected(String.join("\n", swapped) + "\n", "ascending");
  }

  private static void assertRejected(String export, String message) {
    assertThatThrownBy(() -> read(export))
        .isInstanceOf(IOException.class)
        .hasMessageContaining(message);
  }

  private static byte[] goldenExport() throws IOException {
    try (var in =
        ChunkIndexLifecycleTest.class.getResourceAsStream(
            io.github.kjmorse2.mconeworld.fixtures.index.ChunkIndexFixtures.GOLDEN_EXPORT)) {
      return java.util.Objects.requireNonNull(in).readAllBytes();
    }
  }

  private static void read(String text) throws IOException {
    ChunkIndexExport.Reader reader =
        ChunkIndexExport.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    while (reader.next() != null) {
      // drain
    }
  }

  // ---- deterministic resource bounds (time and memory proxies) ----------------------------------

  @Test
  void aCommitIsOneDataSyncAndReadsNoBlock() throws Exception {
    SyntheticWorld world = ChunkDatasets.named("sparse-exploration", 0.05);
    try (IndexHarness harness = new IndexHarness(temp.resolve("sidecar"));
        SidecarChunkIndex index = harness.create(ChunkIndexOptions.DEFAULT)) {
      IndexWorkloads.bulkLoad(world, 2000, index);
      index.checkpoint();
      SplittableRandom random = new SplittableRandom(8);
      HashSet<ChunkPosition> removed = new HashSet<>();
      // The first commit of a generation also creates and syncs the log's header.
      IndexWorkloads.applyAll(
          IndexWorkloads.updateBatch(world, random, 500, SEASON, Instant.EPOCH, removed), index);
      for (int i = 0; i < 5; i++) {
        List<ChunkMutation> batch =
            IndexWorkloads.updateBatch(world, random, 1000, SEASON, Instant.EPOCH, removed);
        int syncs = harness.fs.dataSyncs.get() + harness.fs.fullSyncs.get();
        int reads = harness.fs.positionedReads.get();
        int steps = harness.fs.steps;
        IndexWorkloads.applyAll(batch, index);
        assertThat(harness.fs.dataSyncs.get() + harness.fs.fullSyncs.get() - syncs).isEqualTo(1);
        assertThat(harness.fs.positionedReads.get() - reads).as("block reads").isZero();
        assertThat(harness.fs.steps - steps)
            .as("filesystem steps: one write, one sync")
            .isEqualTo(2);
      }
    }
  }

  @Test
  void aLookupReadsAtMostAHeaderAndAUnitAndTheCacheStaysWithinItsBudget() throws Exception {
    SyntheticWorld world = ChunkDatasets.named("dense-square", 0.2);
    ChunkIndexOptions options = ChunkIndexOptions.DEFAULT.withCacheBytes(1 << 20);
    try (IndexHarness harness = new IndexHarness(temp.resolve("sidecar"));
        SidecarChunkIndex index = harness.create(options)) {
      IndexWorkloads.bulkLoad(world, 5000, index);
      index.checkpoint();
      SplittableRandom random = new SplittableRandom(2);
      try (ChunkIndexSnapshot reader = index.reader()) {
        // Opening a segment verifies its header once; after that a lookup reads at most a block
        // header and one unit.
        reader.find(world.sampleExisting(new SplittableRandom(99)));
        for (int i = 0; i < 2000; i++) {
          ChunkPosition chunk = world.sampleExisting(random);
          int reads = harness.fs.positionedReads.get();
          assertThat(reader.find(chunk)).isPresent();
          assertThat(harness.fs.positionedReads.get() - reads).isLessThanOrEqualTo(2);
          int again = harness.fs.positionedReads.get();
          reader.find(chunk);
          assertThat(harness.fs.positionedReads.get())
              .as("a repeated lookup is cached")
              .isEqualTo(again);
        }
      }
      assertThat(cachedBytes(index)).isLessThanOrEqualTo(options.cacheBytes());
    }
  }

  private static long cachedBytes(SidecarChunkIndex index) throws Exception {
    var field = SidecarChunkIndex.class.getDeclaredField("data");
    field.setAccessible(true);
    return ((IndexData) field.get(index)).cachedBytes();
  }

  @Test
  void theLogIsBoundedByTheCheckpointThreshold() throws Exception {
    SyntheticWorld world = ChunkDatasets.named("friend-server", 0.2);
    try (IndexHarness harness = new IndexHarness(temp.resolve("sidecar"));
        SidecarChunkIndex index =
            harness.create(ChunkIndexOptions.DEFAULT.withCheckpointMutations(300))) {
      IndexWorkloads.bulkLoad(world, 100, index);
      assertThat(index.loggedMutations()).isLessThan(300);
      assertThat(index.state().generation()).isPositive();
    }
  }
}
