// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkRectangle;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkEnvelope;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProtection;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProvenance;
import io.github.kjmorse2.mconeworld.domain.provenance.Confidence;
import io.github.kjmorse2.mconeworld.domain.provenance.EvidenceSource;
import io.github.kjmorse2.mconeworld.domain.provenance.FirstObservation;
import io.github.kjmorse2.mconeworld.domain.provenance.ProvenanceEvidence;
import io.github.kjmorse2.mconeworld.domain.provenance.SeasonActivity;
import io.github.kjmorse2.mconeworld.fixtures.index.ReferenceChunkIndex;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * Random batches of random mutations against a plain map model. Checks the parts of the contract
 * the reference implements itself: batch atomicity, snapshot isolation, region-major order (by an
 * independent oracle), paging, count, and envelope.
 */
class ReferenceChunkIndexProperties {
  private static final List<DimensionKey> DIMENSIONS =
      List.of(DimensionKey.OVERWORLD, DimensionKey.THE_NETHER);
  private static final List<SeasonId> SEASONS =
      List.of(
          SeasonId.of(UUID.fromString("00000000-0000-4000-8000-00000000000a")),
          SeasonId.of(UUID.fromString("00000000-0000-4000-8000-00000000000b")),
          SeasonId.of(UUID.fromString("00000000-0000-4000-8000-00000000000c")));
  private static final ProvenanceEvidence EVIDENCE =
      new ProvenanceEvidence(EvidenceSource.RUNTIME_OBSERVATION, Confidence.HIGH);
  private static final Comparator<ChunkPosition> ORACLE_ORDER =
      Comparator.comparing(ChunkPosition::dimension)
          .thenComparingInt(c -> Math.floorDiv(c.x(), 32))
          .thenComparingInt(c -> Math.floorDiv(c.z(), 32))
          .thenComparingInt(c -> Math.floorMod(c.z(), 32))
          .thenComparingInt(c -> Math.floorMod(c.x(), 32));

  @Property(tries = 300)
  void batchesAreAtomicAndScansMatchTheModel(
      @ForAll("batches") List<List<ChunkMutation>> batches,
      @ForAll("rectangles") ChunkRectangle area,
      @ForAll @IntRange(min = 1, max = 7) int pageSize)
      throws Exception {
    Map<ChunkPosition, ChunkProvenance> model = new HashMap<>();
    try (ReferenceChunkIndex index = new ReferenceChunkIndex()) {
      for (List<ChunkMutation> batch : batches) {
        Map<ChunkPosition, ChunkProvenance> before = new HashMap<>(model);
        Map<ChunkPosition, ChunkProvenance> next = new HashMap<>(model);
        boolean rejected = false;
        try (ChunkIndexReader snapshot = index.reader();
            ChunkIndexBatch writer = index.begin()) {
          for (ChunkMutation mutation : batch) {
            ChunkProvenance updated;
            try {
              updated = mutation.applyTo(next.get(mutation.chunk()));
            } catch (ChunkIndexException e) {
              rejected = true;
              break;
            }
            writer.apply(mutation);
            if (updated == null) {
              next.remove(mutation.chunk());
            } else {
              next.put(mutation.chunk(), updated);
            }
          }
          net.jqwik.api.statistics.Statistics.label("batch")
              .collect(rejected ? "rejected" : "committed");
          if (!rejected) {
            writer.commit();
            model = next;
          }
          assertMatches(snapshot, before, area, pageSize); // the snapshot never moves
        } catch (ChunkIndexException unexpected) {
          throw new AssertionError("Model accepted what the index rejected", unexpected);
        }
        try (ChunkIndexReader reader = index.reader()) {
          assertMatches(reader, model, area, pageSize);
        }
      }
      net.jqwik.api.statistics.Statistics.label("final records")
          .collect(model.isEmpty() ? "0" : model.size() < 10 ? "1-9" : "10+");
    }
  }

  private static void assertMatches(
      ChunkIndexReader reader,
      Map<ChunkPosition, ChunkProvenance> model,
      ChunkRectangle area,
      int pageSize)
      throws ChunkIndexException {
    for (DimensionKey dimension : DIMENSIONS) {
      ChunkRectangle inDimension =
          new ChunkRectangle(dimension, area.minX(), area.minZ(), area.maxX(), area.maxZ());
      List<ChunkQuery> queries = new ArrayList<>();
      queries.add(ChunkQuery.all(dimension));
      queries.add(ChunkQuery.all(dimension).within(inDimension));
      queries.add(ChunkQuery.all(dimension).withSeasons(SeasonFilter.legacy()));
      queries.add(ChunkQuery.all(dimension).withProtection(ProtectionFilter.PROTECTED));
      for (SeasonId season : SEASONS) {
        queries.add(ChunkQuery.all(dimension).withSeasons(SeasonFilter.touchedBy(season)));
        queries.add(
            ChunkQuery.all(dimension)
                .within(inDimension)
                .withSeasons(SeasonFilter.activeIn(season)));
      }
      for (ChunkQuery query : queries) {
        List<ChunkProvenance> expected =
            model.values().stream()
                .filter(query::matches)
                .sorted(Comparator.comparing(ChunkProvenance::chunk, ORACLE_ORDER))
                .toList();
        assertThat(scanAll(reader, query)).containsExactlyElementsOf(expected);
        assertThat(scanPaged(reader, query, pageSize)).containsExactlyElementsOf(expected);
        assertThat(reader.count(query)).isEqualTo(expected.size());
        ChunkEnvelope envelope = ChunkEnvelope.empty(dimension);
        for (ChunkProvenance record : expected) {
          envelope = envelope.including(record.chunk());
        }
        assertThat(reader.envelope(query)).isEqualTo(envelope);
      }
    }
    for (ChunkPosition chunk : model.keySet()) {
      assertThat(reader.find(chunk)).contains(model.get(chunk));
    }
  }

  private static List<ChunkProvenance> scanAll(ChunkIndexReader reader, ChunkQuery query)
      throws ChunkIndexException {
    List<ChunkProvenance> out = new ArrayList<>();
    try (ChunkCursor cursor = reader.scan(query)) {
      for (ChunkProvenance r = cursor.next(); r != null; r = cursor.next()) {
        out.add(r);
      }
    }
    return out;
  }

  private static List<ChunkProvenance> scanPaged(
      ChunkIndexReader reader, ChunkQuery query, int size) throws ChunkIndexException {
    List<ChunkProvenance> out = new ArrayList<>();
    ChunkQuery page = query;
    while (true) {
      int read = 0;
      try (ChunkCursor cursor = reader.scan(page)) {
        for (ChunkProvenance r = cursor.next(); r != null && read < size; r = cursor.next()) {
          out.add(r);
          read++;
        }
      }
      if (read < size) {
        return out;
      }
      page = query.after(out.get(out.size() - 1).chunk());
    }
  }

  @Provide
  Arbitrary<List<List<ChunkMutation>>> batches() {
    return mutation().list().ofMaxSize(12).list().ofMinSize(1).ofMaxSize(10);
  }

  @Provide
  Arbitrary<ChunkRectangle> rectangles() {
    Arbitrary<Integer> coordinate = Arbitraries.integers().between(-40, 40);
    return Combinators.combine(coordinate, coordinate, coordinate, coordinate)
        .as(
            (a, b, c, d) ->
                new ChunkRectangle(
                    DimensionKey.OVERWORLD,
                    Math.min(a, b),
                    Math.min(c, d),
                    Math.max(a, b),
                    Math.max(c, d)));
  }

  /** Mostly region-boundary coordinates, so mutations often hit the same chunk. */
  private static Arbitrary<Integer> coordinate() {
    return Arbitraries.frequencyOf(
        net.jqwik.api.Tuple.of(4, Arbitraries.of(-33, -32, -1, 0, 31, 32)),
        net.jqwik.api.Tuple.of(1, Arbitraries.integers().between(-40, 40)));
  }

  private static Arbitrary<ChunkMutation> mutation() {
    Arbitrary<ChunkPosition> chunk =
        Combinators.combine(Arbitraries.of(DIMENSIONS), coordinate(), coordinate())
            .as(ChunkPosition::new);
    Arbitrary<SeasonId> season = Arbitraries.of(SEASONS);
    Arbitrary<Instant> instant =
        Arbitraries.longs()
            .between(0, 1000)
            .map(s -> Instant.parse("2026-01-01T00:00:00Z").plusSeconds(s));
    Arbitrary<FirstObservation> first =
        Arbitraries.oneOf(
            Combinators.combine(season, instant)
                .as((s, t) -> new FirstObservation.InSeason(s, t, EVIDENCE)),
            instant.map(t -> new FirstObservation.Legacy(t, EVIDENCE)));
    Arbitrary<SeasonActivity> activity =
        Combinators.combine(season, instant).as((s, t) -> new SeasonActivity(s, t, EVIDENCE));
    return Arbitraries.frequencyOf(
        net.jqwik.api.Tuple.of(
            5, Combinators.combine(chunk, first).as(ChunkMutation.ObserveFirst::new)),
        net.jqwik.api.Tuple.of(
            4, Combinators.combine(chunk, activity).as(ChunkMutation.RecordActivity::new)),
        net.jqwik.api.Tuple.of(
            1,
            Combinators.combine(chunk, Arbitraries.of(ChunkProtection.class))
                .as(ChunkMutation.SetProtection::new)),
        net.jqwik.api.Tuple.of(
            1,
            Combinators.combine(chunk, first)
                .as((c, f) -> new ChunkMutation.Replace(ChunkProvenance.firstObserved(c, f)))),
        net.jqwik.api.Tuple.of(2, chunk.map(ChunkMutation.Remove::new)));
  }
}
