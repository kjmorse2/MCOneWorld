// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.fixtures.index;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkRectangle;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProvenance;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkCursor;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexException;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexReader;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkQuery;
import io.github.kjmorse2.mconeworld.sidecar.index.ProtectionFilter;
import io.github.kjmorse2.mconeworld.sidecar.index.SeasonFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;
import org.jspecify.annotations.Nullable;

/**
 * Checks that an implementation answers exactly like the {@link ReferenceChunkIndex}. It streams
 * both sides, so it works at any size, and throws {@link AssertionError} at the first difference
 * with both records in canonical text.
 *
 * <p>For every query it compares the full scan, {@code count}, {@code envelope}, and a paged scan
 * (resuming with {@link ChunkQuery#after}) against the expected scan. For every probe it compares
 * {@code find} and {@code contains}.
 */
public final class ChunkIndexDifferential {
  private ChunkIndexDifferential() {}

  /**
   * Compares {@code actual} with {@code expected} on every query and probe.
   *
   * @param pageSize records per page for the paged comparison
   * @throws AssertionError at the first difference
   */
  public static void assertSameAnswers(
      ChunkIndexReader expected,
      ChunkIndexReader actual,
      List<ChunkQuery> queries,
      List<ChunkPosition> probes,
      int pageSize)
      throws ChunkIndexException {
    check(
        expected.dimensions().equals(actual.dimensions()),
        "dimensions: expected " + expected.dimensions() + ", actual " + actual.dimensions());
    for (ChunkQuery query : queries) {
      long n = assertSameScan(expected, actual, query);
      check(actual.count(query) == n, "count of " + query + ": expected " + n);
      check(
          expected.envelope(query).equals(actual.envelope(query)),
          "envelope of " + query + ": expected " + expected.envelope(query));
      assertPagedScanMatches(expected, actual, query, pageSize);
    }
    for (ChunkPosition probe : probes) {
      check(
          expected.find(probe).equals(actual.find(probe)),
          "find("
              + probe
              + "): expected "
              + expected.find(probe)
              + ", actual "
              + actual.find(probe));
      check(
          expected.contains(probe) == actual.contains(probe),
          "contains(" + probe + "): expected " + expected.contains(probe));
    }
  }

  /**
   * A broad query set for {@code dimension}: everything; each season filter for each season;
   * legacy; both protection filters; and a few rectangles around {@code sampleChunks}, alone and
   * combined with season filters.
   */
  public static List<ChunkQuery> standardQueries(
      DimensionKey dimension, List<SeasonId> seasons, List<ChunkPosition> sampleChunks, long seed) {
    ChunkQuery all = ChunkQuery.all(dimension);
    List<ChunkQuery> queries = new ArrayList<>();
    queries.add(all);
    queries.add(all.withSeasons(SeasonFilter.legacy()));
    queries.add(all.withProtection(ProtectionFilter.PROTECTED));
    queries.add(all.withProtection(ProtectionFilter.UNPROTECTED));
    for (SeasonId season : seasons) {
      queries.add(all.withSeasons(SeasonFilter.firstObservedIn(season)));
      queries.add(all.withSeasons(SeasonFilter.activeIn(season)));
      queries.add(all.withSeasons(SeasonFilter.touchedBy(season)));
    }
    SplittableRandom random = new SplittableRandom(seed);
    for (ChunkPosition center : sampleChunks) {
      if (!center.dimension().equals(dimension)) {
        continue;
      }
      int half = 1 + random.nextInt(48);
      ChunkRectangle area =
          new ChunkRectangle(
              dimension,
              saturatingAdd(center.x(), -half),
              saturatingAdd(center.z(), -half),
              saturatingAdd(center.x(), half),
              saturatingAdd(center.z(), half));
      ChunkQuery rect = all.within(area);
      queries.add(rect);
      if (!seasons.isEmpty()) {
        SeasonId season = seasons.get(random.nextInt(seasons.size()));
        queries.add(rect.withSeasons(SeasonFilter.touchedBy(season)));
      }
      queries.add(rect.after(center));
    }
    return queries;
  }

  private static long assertSameScan(
      ChunkIndexReader expected, ChunkIndexReader actual, ChunkQuery query)
      throws ChunkIndexException {
    long n = 0;
    try (ChunkCursor e = expected.scan(query);
        ChunkCursor a = actual.scan(query)) {
      while (true) {
        @Nullable ChunkProvenance x = e.next();
        @Nullable ChunkProvenance y = a.next();
        if (!Objects.equals(x, y)) {
          throw new AssertionError(
              "scan of "
                  + query
                  + " differs at result "
                  + n
                  + ":\n  expected "
                  + text(x)
                  + "\n  actual   "
                  + text(y));
        }
        if (x == null) {
          return n;
        }
        n++;
      }
    }
  }

  private static void assertPagedScanMatches(
      ChunkIndexReader expected, ChunkIndexReader actual, ChunkQuery query, int pageSize)
      throws ChunkIndexException {
    ChunkQuery page = query;
    long index = 0;
    try (ChunkCursor e = expected.scan(query)) {
      while (true) {
        @Nullable ChunkPosition last = null;
        try (ChunkCursor a = actual.scan(page)) {
          for (int i = 0; i < pageSize; i++) {
            @Nullable ChunkProvenance y = a.next();
            @Nullable ChunkProvenance x = e.next();
            if (!Objects.equals(x, y)) {
              throw new AssertionError(
                  "paged scan of "
                      + query
                      + " differs at result "
                      + index
                      + ":\n  expected "
                      + text(x)
                      + "\n  actual   "
                      + text(y));
            }
            if (y == null) {
              return;
            }
            last = y.chunk();
            index++;
          }
        }
        page = query.after(Objects.requireNonNull(last));
      }
    }
  }

  private static String text(@Nullable ChunkProvenance record) {
    return record == null ? "<end>" : ChunkRecordText.line(record);
  }

  private static int saturatingAdd(int value, int delta) {
    long sum = (long) value + delta;
    return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, sum));
  }

  private static void check(boolean condition, String message) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
