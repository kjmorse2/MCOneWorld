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
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexExport;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexOptions;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexStore;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkMutation;
import io.github.kjmorse2.mconeworld.sidecar.index.SidecarChunkIndex;
import io.github.kjmorse2.mconeworld.sidecar.storage.LockOwner;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarRoot;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarStore;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarWriter;
import io.github.kjmorse2.mconeworld.sidecar.storage.StaleLockPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The golden chunk index, {@code chunk-index} format 1.0: a small index whose every byte is pinned.
 * It holds legacy and in-season first observations, multi-season activity, protection, two
 * dimensions, negative and extreme {@code int} coordinates, region boundaries, and nanosecond
 * times, all checkpointed into one segment, plus a log with two commits that use every mutation
 * type.
 *
 * <p>The resources under {@value #GOLDEN_DIRECTORY} were written by {@link #writeGoldenIndex} and
 * reviewed. Each file has a {@code .meta} with its format, version, and SHA-256. To change them
 * intentionally, run {@link #main} with a scratch directory, review the diff, copy the files, and
 * record the change in {@code docs/formats/chunk-index-v1.md}.
 */
public final class ChunkIndexFixtures {
  /** Classpath directory of the golden v1 index files. */
  public static final String GOLDEN_DIRECTORY =
      "/io/github/kjmorse2/mconeworld/fixtures/chunk-index/v1/";

  /** The golden files, relative to {@code chunk-index/}. */
  public static final List<String> GOLDEN_FILES =
      List.of("catalog", "segments/seg-0000000000000001.dat", "deltas-0000000000000001.log");

  /** The golden export of the golden index. */
  public static final String GOLDEN_EXPORT = GOLDEN_DIRECTORY + "export.jsonl";

  /** The golden index's id. */
  public static final UUID INDEX_ID = UUID.fromString("901de11d-0000-4000-8000-00000000001d");

  /** Options of the golden index: four rows per unit, no automatic checkpoint. */
  public static final ChunkIndexOptions OPTIONS =
      ChunkIndexOptions.DEFAULT.withRowsPerUnit(4).withCheckpointMutations(1_000_000);

  private static final SeasonId S1 =
      SeasonId.of(UUID.fromString("5ea50001-0000-4000-8000-000000000001"));
  private static final SeasonId S2 =
      SeasonId.of(UUID.fromString("5ea50002-0000-4000-8000-000000000002"));
  private static final ProvenanceEvidence RUNTIME =
      new ProvenanceEvidence(EvidenceSource.RUNTIME_OBSERVATION, Confidence.HIGH);
  private static final ProvenanceEvidence IMPORT =
      new ProvenanceEvidence(EvidenceSource.WORLD_IMPORT, Confidence.MEDIUM);
  private static final ProvenanceEvidence ADMIN =
      new ProvenanceEvidence(EvidenceSource.ADMINISTRATOR, Confidence.LOW);

  private ChunkIndexFixtures() {}

  private static Instant at(long second, int nanos) {
    return Instant.ofEpochSecond(1_780_000_000L + second, nanos);
  }

  /** The checkpointed records: hand-picked edge cases plus a small grid. */
  public static List<ChunkProvenance> checkpointRecords() {
    List<ChunkProvenance> out = new ArrayList<>();
    DimensionKey o = DimensionKey.OVERWORLD;
    out.add(
        new ChunkProvenance(
            new ChunkPosition(o, Integer.MIN_VALUE, Integer.MIN_VALUE),
            new FirstObservation.Legacy(at(0, 0), IMPORT),
            List.of(new SeasonActivity(S1, at(10, 123_456_789), RUNTIME)),
            ChunkProtection.PROTECTED));
    out.add(
        new ChunkProvenance(
            new ChunkPosition(o, Integer.MAX_VALUE, Integer.MAX_VALUE),
            new FirstObservation.InSeason(S2, at(20, 1), RUNTIME),
            List.of(),
            ChunkProtection.NONE));
    out.add(
        new ChunkProvenance(
            new ChunkPosition(o, -1, -1),
            new FirstObservation.InSeason(S1, at(30, 0), RUNTIME),
            List.of(
                new SeasonActivity(S1, at(31, 500), RUNTIME),
                new SeasonActivity(S2, at(-5, 999_999_999), ADMIN)),
            ChunkProtection.NONE));
    out.add(
        new ChunkProvenance(
            new ChunkPosition(DimensionKey.THE_NETHER, 0, -33),
            new FirstObservation.Legacy(at(40, 0), IMPORT),
            List.of(),
            ChunkProtection.PROTECTED));
    // A small grid straddling the region boundaries at x = 0 and z = 0.
    for (int i = 0; i < 24; i++) {
      int x = i % 6 - 3;
      int z = i / 6 - 2;
      SeasonId season = i % 2 == 0 ? S1 : S2;
      List<SeasonActivity> activity =
          i % 3 == 0
              ? List.of(
                  new SeasonActivity(S1, at(100 + i, i * 1000), RUNTIME),
                  new SeasonActivity(S2, at(200 + i, 0), RUNTIME))
              : i % 3 == 1
                  ? List.of(new SeasonActivity(season, at(300 + i, 0), RUNTIME))
                  : List.of();
      out.add(
          new ChunkProvenance(
              new ChunkPosition(o, x, z),
              i % 5 == 0
                  ? new FirstObservation.Legacy(at(i, 0), IMPORT)
                  : new FirstObservation.InSeason(season, at(i, i), RUNTIME),
              activity,
              i % 7 == 0 ? ChunkProtection.PROTECTED : ChunkProtection.NONE));
    }
    return out;
  }

  /** The two logged commits, using every mutation type. */
  public static List<List<ChunkMutation>> loggedCommits() {
    DimensionKey o = DimensionKey.OVERWORLD;
    ChunkPosition edge = new ChunkPosition(o, Integer.MIN_VALUE, Integer.MIN_VALUE);
    ChunkPosition minusOne = new ChunkPosition(o, -1, -1);
    ChunkPosition fresh = new ChunkPosition(o, 32, -32);
    return List.of(
        List.of(
            new ChunkMutation.ObserveFirst(
                fresh, new FirstObservation.InSeason(S2, at(50, 7), RUNTIME)),
            new ChunkMutation.RecordActivity(fresh, new SeasonActivity(S2, at(51, 8), RUNTIME)),
            new ChunkMutation.RecordActivity(edge, new SeasonActivity(S2, at(52, 0), ADMIN)),
            new ChunkMutation.SetProtection(minusOne, ChunkProtection.PROTECTED)),
        List.of(
            new ChunkMutation.Replace(
                new ChunkProvenance(
                    minusOne,
                    new FirstObservation.Legacy(at(1, 0), IMPORT),
                    List.of(new SeasonActivity(S1, at(60, 0), RUNTIME)),
                    ChunkProtection.NONE)),
            new ChunkMutation.Remove(new ChunkPosition(o, Integer.MAX_VALUE, Integer.MAX_VALUE)),
            new ChunkMutation.ObserveFirst(fresh, new FirstObservation.Legacy(at(0, 0), IMPORT))));
  }

  /** Applies the golden history (load, checkpoint, two commits) to any index. */
  public static void applyHistory(ChunkIndex index, boolean checkpoint) throws Exception {
    List<ChunkMutation> load = new ArrayList<>();
    for (ChunkProvenance r : checkpointRecords()) {
      load.add(new ChunkMutation.Replace(r));
    }
    IndexWorkloads.applyAll(load, index);
    if (checkpoint && index instanceof SidecarChunkIndex sidecar) {
      sidecar.checkpoint();
    }
    for (List<ChunkMutation> commit : loggedCommits()) {
      IndexWorkloads.applyAll(commit, index);
    }
  }

  /** Writes the golden index into a new sidecar at {@code root}. */
  public static void writeGoldenIndex(Path root) throws Exception {
    Files.createDirectories(root);
    SidecarStore store = SidecarStore.open(SidecarRoot.open(root));
    Clock clock = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC);
    try (SidecarWriter writer =
            store.openWriter(new LockOwner("golden", "1"), clock, StaleLockPolicy.REFUSE);
        SidecarChunkIndex index = ChunkIndexStore.of(store, OPTIONS).create(writer, INDEX_ID)) {
      applyHistory(index, true);
    }
  }

  /** Regenerates the golden index into {@code args[0]} (a scratch directory) for review. */
  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    writeGoldenIndex(root);
    try (var reader = ChunkIndexStore.of(SidecarStore.open(SidecarRoot.open(root))).openReader();
        var out = Files.newOutputStream(root.resolve("export.jsonl"))) {
      ChunkIndexExport.write(reader, out);
    }
  }
}
