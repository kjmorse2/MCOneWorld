// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationIssue;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationResult;
import io.github.kjmorse2.mconeworld.fixtures.index.ChunkDatasets;
import io.github.kjmorse2.mconeworld.fixtures.index.ChunkRecordText;
import io.github.kjmorse2.mconeworld.fixtures.index.IndexWorkloads;
import io.github.kjmorse2.mconeworld.fixtures.index.ReferenceChunkIndex;
import io.github.kjmorse2.mconeworld.fixtures.index.SyntheticWorld;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Corrupt, truncated, and oversized input at every structural layer (test plan X1–X10 and R1–R7). A
 * read either returns exactly what was committed or fails with a coded {@link ChunkIndexException}
 * that names the damaged structure; it never returns different records silently, never throws an
 * uncoded exception, and never repairs anything.
 */
class ChunkIndexCorruptionTest {
  private static final SeasonId SEASON =
      SeasonId.of(UUID.fromString("00000000-0000-4000-8000-0000000000cc"));
  private static final Set<IssueCode> CORRUPTION =
      Set.of(
          ChunkIndexIssueCodes.HEADER_CORRUPT,
          ChunkIndexIssueCodes.CATALOG_CORRUPT,
          ChunkIndexIssueCodes.BLOCK_CORRUPT,
          ChunkIndexIssueCodes.LOG_CORRUPT,
          ChunkIndexIssueCodes.SEGMENT_MISSING,
          ChunkIndexIssueCodes.INDEX_ID_MISMATCH,
          ChunkIndexIssueCodes.UNSUPPORTED_VERSION,
          ChunkIndexIssueCodes.MUTATION_INAPPLICABLE,
          ChunkIndexIssueCodes.TOO_LARGE);

  @TempDir Path temp;
  private Path root;
  private Path area;
  private String committed;

  /** A small index with a checkpoint (blocks in a segment) and two logged commits. */
  @BeforeEach
  void build() throws Exception {
    root = temp.resolve("sidecar");
    area = root.resolve("chunk-index");
    SyntheticWorld world = ChunkDatasets.named("friend-server", 0.07);
    ReferenceChunkIndex reference = new ReferenceChunkIndex();
    try (IndexHarness harness = new IndexHarness(root);
        SidecarChunkIndex index = harness.create(ChunkIndexOptions.DEFAULT.withRowsPerUnit(8))) {
      IndexWorkloads.bulkLoad(world, 200, reference, index);
      index.checkpoint();
      Set<ChunkPosition> removed = new HashSet<>();
      for (int i = 0; i < 2; i++) {
        IndexWorkloads.applyAll(
            IndexWorkloads.updateBatch(
                world,
                new SplittableRandom(i),
                25,
                SEASON,
                Instant.parse("2031-01-01T00:00:00Z"),
                removed),
            reference,
            index);
      }
    }
    try (var r = reference.reader()) {
      committed = ChunkRecordText.digest(r);
    }
  }

  private ChunkIndexStore store() throws Exception {
    return IndexStores.readOnly(root);
  }

  private List<Path> indexFiles() throws Exception {
    try (Stream<Path> walk = Files.walk(area)) {
      return walk.filter(Files::isRegularFile).sorted().toList();
    }
  }

  // ---- X1: exhaustive single-byte flips -------------------------------------------------------

  @Test
  void everySingleByteFlipIsDetectedOnReadAndByVerify() throws Exception {
    int trials = 0;
    for (Path file : indexFiles()) {
      byte[] original = Files.readAllBytes(file);
      for (int offset = 0; offset < original.length; offset++) {
        byte[] damaged = original.clone();
        damaged[offset] ^= (byte) (1 << (offset % 8));
        Files.write(file, damaged);
        String where = area.relativize(file) + "@" + offset;
        assertThat(readPath()).as("read path, flip at %s", where).isEqualTo(Outcome.DETECTED);
        assertThat(verifyPath()).as("verify path, flip at %s", where).isEqualTo(Outcome.DETECTED);
        trials++;
      }
      Files.write(file, original);
    }
    assertThat(trials).isGreaterThan(5_000);
    assertThat(readPath()).isEqualTo(Outcome.HARMLESS);
  }

  enum Outcome {
    DETECTED,
    HARMLESS
  }

  /** Opens and exports everything, as an ordinary reader would. */
  private Outcome readPath() throws Exception {
    try (ChunkIndexSnapshot reader = store().openReader()) {
      String digest = ChunkRecordText.digest(reader);
      if (!digest.equals(committed)) {
        throw new AssertionError("SILENT: different records without an error");
      }
      return Outcome.HARMLESS;
    } catch (ChunkIndexException e) {
      assertThat(CORRUPTION).as("code of %s", e).contains(e.code());
      assertThat(e.scope()).as("scope of %s", e).isNotNull();
      return Outcome.DETECTED;
    }
  }

  /** The deepest check: verification must report an error. */
  private Outcome verifyPath() throws Exception {
    ValidationResult result;
    try {
      result = store().verify();
    } catch (ChunkIndexException e) {
      assertThat(CORRUPTION).as("code of %s", e).contains(e.code());
      return Outcome.DETECTED;
    }
    return result.isValid() ? Outcome.HARMLESS : Outcome.DETECTED;
  }

  // ---- X2: truncation ---------------------------------------------------------------------------

  @Test
  void truncatingCatalogOrSegmentIsDetectedAndTruncatingTheLogLeavesAnEarlierCommit()
      throws Exception {
    Set<String> validStates = commitPrefixDigests();
    for (Path file : indexFiles()) {
      byte[] original = Files.readAllBytes(file);
      boolean log = file.getFileName().toString().startsWith("deltas-");
      for (int length = 0; length < original.length; length += 7) {
        Files.write(file, java.util.Arrays.copyOf(original, length));
        try (ChunkIndexSnapshot reader = store().openReader()) {
          String digest = ChunkRecordText.digest(reader);
          assertThat(log)
              .as("only the log may be cut without detection (%s at %d)", file, length)
              .isTrue();
          // A cut log is indistinguishable from a torn write: an earlier commit, never a mixture.
          assertThat(validStates).as("state after cutting the log at %d", length).contains(digest);
        } catch (ChunkIndexException e) {
          assertThat(CORRUPTION).contains(e.code());
        }
      }
      Files.write(file, original);
    }
  }

  /** The digests of the state after the checkpoint and after each logged commit. */
  private Set<String> commitPrefixDigests() throws Exception {
    SyntheticWorld world = ChunkDatasets.named("friend-server", 0.07);
    ReferenceChunkIndex reference = new ReferenceChunkIndex();
    IndexWorkloads.bulkLoad(world, 200, reference);
    Set<String> digests = new HashSet<>();
    try (var r = reference.reader()) {
      digests.add(ChunkRecordText.digest(r));
    }
    Set<ChunkPosition> removed = new HashSet<>();
    for (int i = 0; i < 2; i++) {
      IndexWorkloads.applyAll(
          IndexWorkloads.updateBatch(
              world,
              new SplittableRandom(i),
              25,
              SEASON,
              Instant.parse("2031-01-01T00:00:00Z"),
              removed),
          reference);
      try (var r = reference.reader()) {
        digests.add(ChunkRecordText.digest(r));
      }
    }
    return digests;
  }

  // ---- one fault per structural layer, with its code and scope ----------------------------------

  private Catalog catalog() throws Exception {
    return Catalog.decode(Files.readAllBytes(area.resolve("catalog")));
  }

  private Map.Entry<DimensionKey, Catalog.Entry> firstRegion() throws Exception {
    var dimension = catalog().regions.entrySet().iterator().next();
    return Map.entry(dimension.getKey(), dimension.getValue().firstEntry().getValue());
  }

  private Path segmentOf(Catalog.Entry e) {
    return area.resolve(IndexFormat.segmentPath(e.segment).toString());
  }

  private static void flip(Path file, long offset) throws Exception {
    byte[] bytes = Files.readAllBytes(file);
    bytes[(int) offset] ^= 0x10;
    Files.write(file, bytes);
  }

  private ChunkIndexException readFailure() throws Exception {
    try (ChunkIndexSnapshot reader = store().openReader()) {
      ChunkRecordText.digest(reader);
    } catch (ChunkIndexException e) {
      return e;
    }
    throw new AssertionError("expected a coded failure");
  }

  @Test
  void aDamagedBlockHeaderNamesItsRegion() throws Exception {
    var region = firstRegion();
    Catalog.Entry e = region.getValue();
    flip(segmentOf(e), e.offset + 10); // inside the header
    ChunkIndexException failure = readFailure();
    assertThat(failure.code()).isEqualTo(ChunkIndexIssueCodes.BLOCK_CORRUPT);
    assertThat(failure.scope())
        .contains("seg-")
        .contains(region.getKey().namespacedId())
        .contains("r." + e.regionX + "." + e.regionZ);
  }

  @Test
  void aDamagedUnitNamesItsRegionAndUnit() throws Exception {
    var region = firstRegion();
    Catalog.Entry e = region.getValue();
    flip(segmentOf(e), e.offset + e.length - 1); // the last stored byte: inside the last unit
    ChunkIndexException failure = readFailure();
    assertThat(failure.code()).isEqualTo(ChunkIndexIssueCodes.BLOCK_CORRUPT);
    assertThat(failure.scope()).contains("r." + e.regionX + "." + e.regionZ).contains("unit");
  }

  @Test
  void verifyReportsEveryDamagedRegionAtItsLocationAndKeepsGoing() throws Exception {
    var regions = new ArrayList<>(catalog().regions.values().iterator().next().values());
    Catalog.Entry a = regions.get(0);
    Catalog.Entry b = regions.get(regions.size() - 1);
    flip(segmentOf(a), a.offset + a.length - 1);
    flip(segmentOf(b), b.offset + b.length - 1);
    ValidationResult result = store().verify();
    List<String> locations =
        result.issues().stream().map(ValidationIssue::location).map(Object::toString).toList();
    assertThat(locations)
        .anyMatch(l -> l.contains("[r." + a.regionX + "." + a.regionZ + "]"))
        .anyMatch(l -> l.contains("[r." + b.regionX + "." + b.regionZ + "]"));
  }

  @Test
  void aDamagedCatalogMakesTheIndexUnusableWithACatalogCode() throws Exception {
    Path catalog = area.resolve("catalog");
    flip(catalog, Files.size(catalog) / 2);
    assertThat(readFailure().code()).isEqualTo(ChunkIndexIssueCodes.CATALOG_CORRUPT);
    ChunkIndexStatus status = store().inspect();
    assertThat(status.state()).isNull();
    assertThat(status.findings().has(ChunkIndexIssueCodes.CATALOG_CORRUPT)).isTrue();
  }

  @Test
  void damageToACompleteLogFrameIsCorruptionNotATornTail() throws Exception {
    Path log = logFile();
    flip(log, IndexFormat.HEADER_BYTES + 8 + 3); // payload of the first of two frames
    ChunkIndexException failure = readFailure();
    assertThat(failure.code()).isEqualTo(ChunkIndexIssueCodes.LOG_CORRUPT);
    assertThat(failure.scope()).contains("deltas-").contains("frame at");
  }

  @Test
  void aMissingSegmentIsNamed() throws Exception {
    Catalog.Entry e = firstRegion().getValue();
    Files.delete(segmentOf(e));
    ChunkIndexException failure = readFailure();
    assertThat(failure.code()).isEqualTo(ChunkIndexIssueCodes.SEGMENT_MISSING);
    assertThat(failure.scope()).contains("seg-");
  }

  @Test
  void aSegmentFromAnotherIndexIsRejected() throws Exception {
    Catalog.Entry e = firstRegion().getValue();
    Path segment = segmentOf(e);
    byte[] bytes = Files.readAllBytes(segment);
    ByteSink header = new ByteSink(IndexFormat.HEADER_BYTES);
    IndexFormat.writeHeader(header, IndexFormat.SEGMENT_MAGIC, UUID.randomUUID(), e.segment);
    System.arraycopy(header.toByteArray(), 0, bytes, 0, IndexFormat.HEADER_BYTES);
    Files.write(segment, bytes);
    assertThat(readFailure().code()).isEqualTo(ChunkIndexIssueCodes.INDEX_ID_MISMATCH);
  }

  @Test
  void anAbsurdCountIsRefusedBeforeAllocating() throws Exception {
    Path catalog = area.resolve("catalog");
    byte[] bytes = Files.readAllBytes(catalog);
    // After the header: varint next segment (one byte here), then the dimension count.
    int at = IndexFormat.HEADER_BYTES + 1;
    byte[] huge = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x07};
    byte[] damaged = new byte[bytes.length + huge.length - 1];
    System.arraycopy(bytes, 0, damaged, 0, at);
    System.arraycopy(huge, 0, damaged, at, huge.length);
    System.arraycopy(bytes, at + 1, damaged, at + huge.length, bytes.length - at - 1);
    rewriteCrc(damaged);
    Files.write(catalog, damaged);
    ChunkIndexException failure = readFailure();
    assertThat(failure.code()).isEqualTo(ChunkIndexIssueCodes.CATALOG_CORRUPT);
    assertThat(failure.getMessage()).contains("outside");
  }

  private static void rewriteCrc(byte[] bytes) {
    int crc = IndexFormat.crc(bytes, 0, bytes.length - 4);
    bytes[bytes.length - 4] = (byte) (crc >>> 24);
    bytes[bytes.length - 3] = (byte) (crc >>> 16);
    bytes[bytes.length - 2] = (byte) (crc >>> 8);
    bytes[bytes.length - 1] = (byte) crc;
  }

  private Path logFile() throws Exception {
    try (Stream<Path> files = Files.list(area)) {
      return files
          .filter(p -> p.getFileName().toString().startsWith("deltas-"))
          .findFirst()
          .orElseThrow();
    }
  }

  // ---- X8 versions, X9 symlinks, X10 read-only -------------------------------------------------

  @Test
  void anUnknownMajorVersionFailsClosedAndANewerMinorIsReadOnly() throws Exception {
    Path catalog = area.resolve("catalog");
    byte[] original = Files.readAllBytes(catalog);

    Files.write(catalog, withVersion(original, 2, 0));
    assertThat(readFailure().code()).isEqualTo(ChunkIndexIssueCodes.UNSUPPORTED_VERSION);

    Files.write(catalog, withVersion(original, 1, 1));
    try (ChunkIndexSnapshot reader = store().openReader()) {
      assertThat(reader.state().readOnly()).isTrue();
      assertThat(ChunkRecordText.digest(reader)).isEqualTo(committed);
    }
    assertThat(store().inspect().findings().has(ChunkIndexIssueCodes.NEWER_MINOR_VERSION)).isTrue();
    try (IndexHarness harness = new IndexHarness(root)) {
      assertThatThrownBy(() -> harness.open(ChunkIndexOptions.DEFAULT))
          .isInstanceOf(ChunkIndexException.class)
          .extracting(t -> ((ChunkIndexException) t).code())
          .isEqualTo(ChunkIndexIssueCodes.READ_ONLY);
      // The failed open released its claim on the writer.
      harness.writer.openIndexWriter().close();
    }
  }

  /** The catalog with another version in its header, all checksums recomputed. */
  private static byte[] withVersion(byte[] catalog, int major, int minor) {
    byte[] bytes = catalog.clone();
    bytes[4] = (byte) (major >>> 8);
    bytes[5] = (byte) major;
    bytes[6] = (byte) (minor >>> 8);
    bytes[7] = (byte) minor;
    int headerCrc = IndexFormat.crc(bytes, 0, IndexFormat.HEADER_BYTES - 4);
    for (int i = 0; i < 4; i++) {
      bytes[IndexFormat.HEADER_BYTES - 4 + i] = (byte) (headerCrc >>> (24 - 8 * i));
    }
    rewriteCrc(bytes);
    return bytes;
  }

  @Test
  void aSymlinkInTheAreaIsRefused() throws Exception {
    Catalog.Entry e = firstRegion().getValue();
    Path segment = segmentOf(e);
    Path moved = temp.resolve("elsewhere.dat");
    Files.move(segment, moved);
    Files.createSymbolicLink(segment, moved);
    ChunkIndexException failure = readFailure();
    assertThat(failure.code()).isNotNull();
    assertThat(failure.getMessage()).containsIgnoringCase("symbolic");
  }

  @Test
  void inspectionVerificationAndReadingNeverWrite() throws Exception {
    flip(segmentOf(firstRegion().getValue()), firstRegion().getValue().offset + 10);
    IndexFaultFileSystem fs = new IndexFaultFileSystem();
    ChunkIndexStore store = IndexStores.readOnly(root, fs);
    store.inspect();
    assertThatThrownBy(
            () -> {
              try (ChunkIndexSnapshot reader = store.openReader()) {
                ChunkRecordText.digest(reader);
              }
            })
        .isInstanceOf(ChunkIndexException.class);
    assertThat(store.verify().isValid()).isFalse();
    assertThat(fs.steps).as("mutating steps: %s", fs.stepLog).isZero();
    assertThat(fs.openHandles.get()).isZero();
  }

  @Test
  void unexplainedFilesAreReportedAndBlockTheWriterButAreNeverRemoved() throws Exception {
    Path stray = area.resolve("notes.txt");
    Files.writeString(stray, "hello");
    ChunkIndexStatus status = store().inspect();
    assertThat(status.findings().has(ChunkIndexIssueCodes.UNEXPLAINED_FILE)).isTrue();
    assertThat(status.usable()).isFalse();
    try (IndexHarness harness = new IndexHarness(root)) {
      assertThatThrownBy(() -> harness.open(ChunkIndexOptions.DEFAULT))
          .isInstanceOf(ChunkIndexException.class)
          .extracting(t -> ((ChunkIndexException) t).code())
          .isEqualTo(ChunkIndexIssueCodes.UNEXPLAINED_FILE);
    }
    assertThat(stray).exists();
  }

  @Test
  void aCatalogEntryPointingAtAnotherRegionsBlockIsDetected() throws Exception {
    Catalog catalog = catalog();
    var dimension = catalog.regions.entrySet().iterator().next();
    var regions = new ArrayList<>(dimension.getValue().entrySet());
    var a = regions.get(0);
    Catalog.Entry b = regions.get(1).getValue();
    java.util.TreeMap<Long, Catalog.Entry> swapped = new java.util.TreeMap<>(dimension.getValue());
    swapped.put(
        a.getKey(),
        new Catalog.Entry(
            a.getValue().regionX,
            a.getValue().regionZ,
            b.segment,
            b.offset,
            b.length,
            b.headerLength,
            b.headerCrc,
            presenceOf(a.getValue()),
            a.getValue().count,
            a.getValue().summary));
    java.util.TreeMap<DimensionKey, java.util.NavigableMap<Long, Catalog.Entry>> all =
        new java.util.TreeMap<>(catalog.regions);
    all.put(dimension.getKey(), swapped);
    Files.write(
        area.resolve("catalog"),
        new Catalog(catalog.indexId, catalog.generation, catalog.nextSegment, false, all).encode());
    ChunkIndexException failure = readFailure();
    assertThat(failure.code()).isEqualTo(ChunkIndexIssueCodes.BLOCK_CORRUPT);
    assertThat(failure.getMessage()).contains("another region");
  }

  private static long[] presenceOf(Catalog.Entry e) {
    long[] words = new long[16];
    for (int i = 0; i < 1024; i++) {
      if (e.present(i)) {
        words[i >>> 6] |= 1L << (i & 63);
      }
    }
    return words;
  }

  @Test
  void aLoggedMutationThatNoLongerAppliesIsReportedNotIgnored() throws Exception {
    // A well-formed frame recording activity on a chunk that has no record anywhere.
    ChunkPosition nowhere = new ChunkPosition(DimensionKey.THE_END, 12_345, -6_789);
    Map<ChunkPosition, List<ChunkMutation>> frame = new java.util.LinkedHashMap<>();
    frame.put(
        nowhere,
        List.of(
            new ChunkMutation.RecordActivity(
                nowhere,
                new io.github.kjmorse2.mconeworld.domain.provenance.SeasonActivity(
                    SEASON,
                    Instant.EPOCH,
                    new io.github.kjmorse2.mconeworld.domain.provenance.ProvenanceEvidence(
                        io.github.kjmorse2.mconeworld.domain.provenance.EvidenceSource
                            .RUNTIME_OBSERVATION,
                        io.github.kjmorse2.mconeworld.domain.provenance.Confidence.HIGH)))));
    Files.write(logFile(), DeltaLog.frame(frame), java.nio.file.StandardOpenOption.APPEND);
    ChunkIndexException failure = readFailure();
    assertThat(failure.code()).isEqualTo(ChunkIndexIssueCodes.MUTATION_INAPPLICABLE);
    assertThat(store().verify().has(ChunkIndexIssueCodes.MUTATION_INAPPLICABLE)).isTrue();
  }

  @Test
  void aLogNewerThanTheCatalogIsUnexplained() throws Exception {
    long generation = catalog().generation;
    Files.write(
        area.resolve(IndexFormat.logPath(generation + 1).toString()),
        DeltaLog.header(catalog().indexId, generation + 1));
    ChunkIndexStatus status = store().inspect();
    assertThat(status.findings().has(ChunkIndexIssueCodes.UNEXPLAINED_FILE)).isTrue();
    assertThat(status.usable()).isFalse();
  }
}
