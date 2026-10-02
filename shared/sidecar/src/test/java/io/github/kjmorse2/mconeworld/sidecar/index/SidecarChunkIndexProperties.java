// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkRectangle;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProtection;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProvenance;
import io.github.kjmorse2.mconeworld.domain.provenance.Confidence;
import io.github.kjmorse2.mconeworld.domain.provenance.EvidenceSource;
import io.github.kjmorse2.mconeworld.domain.provenance.FirstObservation;
import io.github.kjmorse2.mconeworld.domain.provenance.ProvenanceEvidence;
import io.github.kjmorse2.mconeworld.domain.provenance.SeasonActivity;
import io.github.kjmorse2.mconeworld.fixtures.index.ChunkIndexDifferential;
import io.github.kjmorse2.mconeworld.fixtures.index.ChunkRecordText;
import io.github.kjmorse2.mconeworld.fixtures.index.ReferenceChunkIndex;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.statistics.Statistics;

/**
 * Random programs of batches, checkpoints, compactions, maintenance, and reopens, checked against
 * the reference after every step: set and query algebra across positive, negative, and extreme
 * coordinates, multi-season activity, legacy chunks, and protection. Rejected batches must leave
 * both indexes unchanged.
 */
class SidecarChunkIndexProperties {
  private static final List<DimensionKey> DIMENSIONS =
      List.of(DimensionKey.OVERWORLD, DimensionKey.THE_NETHER);
  private static final List<SeasonId> SEASONS =
      List.of(
          SeasonId.of(UUID.fromString("00000000-0000-4000-8000-00000000000a")),
          SeasonId.of(UUID.fromString("00000000-0000-4000-8000-00000000000b")),
          SeasonId.of(UUID.fromString("00000000-0000-4000-8000-00000000000c")));
  private static final ProvenanceEvidence EVIDENCE =
      new ProvenanceEvidence(EvidenceSource.RUNTIME_OBSERVATION, Confidence.HIGH);
  private static final ChunkIndexOptions OPTIONS =
      ChunkIndexOptions.DEFAULT.withCheckpointMutations(15).withRowsPerUnit(2);

  /** One step of a program. */
  sealed interface Step {}

  record Commit(List<ChunkMutation> mutations) implements Step {}

  record Maintain(String kind) implements Step {}

  @Property(tries = 100)
  void programsMatchTheReference(@ForAll("programs") List<Step> program) throws Exception {
    Path dir = Files.createTempDirectory("index-property");
    try {
      ReferenceChunkIndex reference = new ReferenceChunkIndex();
      try (IndexHarness harness = new IndexHarness(dir.resolve("sidecar"))) {
        SidecarChunkIndex index = harness.create(OPTIONS);
        for (Step step : program) {
          switch (step) {
            case Commit commit -> {
              boolean rejectedByReference = !applies(reference, commit.mutations());
              boolean rejectedByIndex = !apply(index, commit.mutations());
              assertThat(rejectedByIndex).isEqualTo(rejectedByReference);
              if (!rejectedByReference) {
                apply(reference, commit.mutations());
              }
              Statistics.label("batch").collect(rejectedByReference ? "rejected" : "committed");
            }
            case Maintain maintain -> {
              switch (maintain.kind()) {
                case "checkpoint" -> index.checkpoint();
                case "compact" -> index.compact();
                case "remove" -> index.removeObsoleteFiles();
                default -> {
                  index.close();
                  index = harness.open(OPTIONS);
                }
              }
            }
          }
          assertSame(reference, index);
        }
        assertThat(index.verify().isValid()).isTrue();
        index.close();
      }
    } finally {
      try (var walk = Files.walk(dir)) {
        for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
          Files.delete(p);
        }
      }
    }
  }

  private static boolean applies(ReferenceChunkIndex reference, List<ChunkMutation> mutations)
      throws Exception {
    // A batch closed without committing changes nothing, so this only asks whether it would apply.
    try (var batch = reference.begin()) {
      for (ChunkMutation m : mutations) {
        batch.apply(m);
      }
      return true;
    } catch (ChunkIndexException e) {
      return false;
    }
  }

  private static boolean apply(
      io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndex index, List<ChunkMutation> mutations)
      throws Exception {
    try (var batch = index.begin()) {
      for (ChunkMutation m : mutations) {
        batch.apply(m);
      }
      batch.commit();
      return true;
    } catch (ChunkIndexException e) {
      if (!e.code().equals(ChunkIndexIssueCodes.RECORD_ABSENT)) {
        throw e;
      }
      return false;
    }
  }

  private static void assertSame(ReferenceChunkIndex reference, SidecarChunkIndex index)
      throws Exception {
    List<ChunkPosition> probes = new ArrayList<>();
    for (DimensionKey d : DIMENSIONS) {
      for (int x : COORDINATES) {
        for (int z : new int[] {COORDINATES[0], 0, 31, 32}) {
          probes.add(new ChunkPosition(d, x, z));
        }
      }
    }
    try (var expected = reference.reader();
        var actual = index.reader()) {
      assertThat(ChunkRecordText.digest(actual)).isEqualTo(ChunkRecordText.digest(expected));
      for (DimensionKey d : DIMENSIONS) {
        List<io.github.kjmorse2.mconeworld.sidecar.index.ChunkQuery> queries =
            new ArrayList<>(ChunkIndexDifferential.standardQueries(d, SEASONS, probes, 1));
        queries.add(
            io.github.kjmorse2.mconeworld.sidecar.index.ChunkQuery.all(d)
                .within(new ChunkRectangle(d, -33, -33, 32, 32)));
        ChunkIndexDifferential.assertSameAnswers(expected, actual, queries, probes, 3);
      }
    }
  }

  /** Region edges and the extreme int range, so steps collide on the same chunks and regions. */
  private static final int[] COORDINATES = {
    Integer.MIN_VALUE, Integer.MIN_VALUE + 31, -33, -32, -1, 0, 31, 32, Integer.MAX_VALUE
  };

  @Provide
  Arbitrary<List<Step>> programs() {
    Arbitrary<Step> commit = mutation().list().ofMinSize(1).ofMaxSize(8).map(Commit::new);
    Arbitrary<Step> maintain =
        Arbitraries.of("checkpoint", "compact", "remove", "reopen").map(Maintain::new);
    return Arbitraries.frequencyOf(Tuple.of(6, commit), Tuple.of(2, maintain))
        .list()
        .ofMinSize(1)
        .ofMaxSize(14);
  }

  private static Arbitrary<ChunkMutation> mutation() {
    Arbitrary<ChunkPosition> chunk =
        Combinators.combine(
                Arbitraries.of(DIMENSIONS),
                Arbitraries.of(box(COORDINATES)),
                Arbitraries.of(box(COORDINATES)))
            .as(ChunkPosition::new);
    Arbitrary<SeasonId> season = Arbitraries.of(SEASONS);
    Arbitrary<Instant> instant =
        Arbitraries.longs()
            .between(0, 1_000_000)
            .map(
                s ->
                    Instant.parse("2026-01-01T00:00:00Z")
                        .plusSeconds(s)
                        .plusNanos(s * 7919 % 1000));
    Arbitrary<FirstObservation> first =
        Arbitraries.oneOf(
            Combinators.combine(season, instant)
                .as((s, t) -> new FirstObservation.InSeason(s, t, EVIDENCE)),
            instant.map(t -> new FirstObservation.Legacy(t, EVIDENCE)));
    Arbitrary<SeasonActivity> activity =
        Combinators.combine(season, instant).as((s, t) -> new SeasonActivity(s, t, EVIDENCE));
    return Arbitraries.frequencyOf(
        Tuple.of(5, Combinators.combine(chunk, first).as(ChunkMutation.ObserveFirst::new)),
        Tuple.of(4, Combinators.combine(chunk, activity).as(ChunkMutation.RecordActivity::new)),
        Tuple.of(
            1,
            Combinators.combine(chunk, Arbitraries.of(ChunkProtection.class))
                .as(ChunkMutation.SetProtection::new)),
        Tuple.of(
            1,
            Combinators.combine(chunk, first, activity)
                .as(
                    (c, f, a) ->
                        new ChunkMutation.Replace(
                            new ChunkProvenance(c, f, List.of(a), ChunkProtection.NONE)))),
        Tuple.of(2, chunk.map(ChunkMutation.Remove::new)));
  }

  private static Integer[] box(int[] values) {
    Integer[] out = new Integer[values.length];
    for (int i = 0; i < values.length; i++) {
      out[i] = values[i];
    }
    return out;
  }
}
