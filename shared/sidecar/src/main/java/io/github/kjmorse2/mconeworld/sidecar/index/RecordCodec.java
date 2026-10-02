// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProtection;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProvenance;
import io.github.kjmorse2.mconeworld.domain.provenance.FirstObservation;
import io.github.kjmorse2.mconeworld.domain.provenance.ProvenanceEvidence;
import io.github.kjmorse2.mconeworld.domain.provenance.SeasonActivity;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * One record's bytes (format §4). Seasons are indexes into a dictionary that the enclosing block or
 * log frame carries; times are seconds relative to the dictionary's base second, plus nanos.
 *
 * <pre>
 *   u8      flags: bit 0 legacy, bit 1 protected (other bits must be 0)
 *   varint  first season index            (absent when legacy)
 *   zigzag  observedAt seconds - base;  varint nanos;  u8 evidence
 *   varint  activity count A (at most the dictionary size), then A times:
 *           varint season index;  zigzag seconds - base;  varint nanos;  u8 evidence
 * </pre>
 */
final class RecordCodec {
  private static final int MAX_NANOS = 999_999_999;

  private RecordCodec() {}

  static void write(
      ByteSink out, ChunkProvenance record, ToIntFunction<SeasonId> seasonIndex, long baseSecond) {
    FirstObservation first = record.firstObservation();
    SeasonId firstSeason = first.knownSeason();
    out.u8(
        (firstSeason == null ? 1 : 0) | (record.protection() == ChunkProtection.PROTECTED ? 2 : 0));
    if (firstSeason != null) {
      out.varInt(seasonIndex.applyAsInt(firstSeason));
    }
    writeInstant(out, first.observedAt(), baseSecond);
    out.u8(EvidenceCodes.encode(first.evidence()));
    out.varInt(record.activity().size());
    for (SeasonActivity activity : record.activity()) {
      out.varInt(seasonIndex.applyAsInt(activity.season()));
      writeInstant(out, activity.lastObservedAt(), baseSecond);
      out.u8(EvidenceCodes.encode(activity.evidence()));
    }
  }

  /** Decodes one record. Every value is range-checked; any failure is corruption. */
  static ChunkProvenance read(ByteSource in, ChunkPosition chunk, SeasonId[] seasons, long base)
      throws ChunkIndexException {
    int flags = in.u8();
    if ((flags & ~3) != 0) {
      throw in.corrupt("record flags " + flags + " for " + chunk);
    }
    SeasonId firstSeason = (flags & 1) == 0 ? season(in, seasons) : null;
    Instant firstAt = readInstant(in, base);
    ProvenanceEvidence firstEvidence = EvidenceCodes.decode(in);
    int count = in.count(seasons.length, 4);
    List<SeasonActivity> activity = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      SeasonId season = season(in, seasons);
      Instant at = readInstant(in, base);
      activity.add(new SeasonActivity(season, at, EvidenceCodes.decode(in)));
    }
    FirstObservation first =
        firstSeason == null
            ? new FirstObservation.Legacy(firstAt, firstEvidence)
            : new FirstObservation.InSeason(firstSeason, firstAt, firstEvidence);
    try {
      return new ChunkProvenance(
          chunk,
          first,
          activity,
          (flags & 2) != 0 ? ChunkProtection.PROTECTED : ChunkProtection.NONE);
    } catch (RuntimeException e) {
      throw in.corrupt("record for " + chunk + " violates a domain rule: " + e.getMessage(), e);
    }
  }

  /**
   * Steps over one record, checking every bound {@link #read} checks, without allocating. Only the
   * cross-field rule (one activity entry per season) is left for {@link #read}.
   */
  static void skip(ByteSource in, int seasonCount) throws ChunkIndexException {
    int flags = in.u8();
    if ((flags & ~3) != 0) {
      throw in.corrupt("record flags " + flags);
    }
    if ((flags & 1) == 0) {
      seasonIndex(in, seasonCount);
    }
    skipInstant(in);
    EvidenceCodes.decode(in);
    int count = in.count(seasonCount, 4);
    for (int i = 0; i < count; i++) {
      seasonIndex(in, seasonCount);
      skipInstant(in);
      EvidenceCodes.decode(in);
    }
  }

  static void writeInstant(ByteSink out, Instant at, long baseSecond) {
    out.zigZag(at.getEpochSecond() - baseSecond).varInt(at.getNano());
  }

  static Instant readInstant(ByteSource in, long baseSecond) throws ChunkIndexException {
    long delta = in.zigZag();
    int nanos = in.varInt(MAX_NANOS);
    try {
      return Instant.ofEpochSecond(Math.addExact(baseSecond, delta), nanos);
    } catch (ArithmeticException | DateTimeException e) {
      throw in.corrupt("instant out of range", e);
    }
  }

  private static void skipInstant(ByteSource in) throws ChunkIndexException {
    in.zigZag();
    in.varInt(MAX_NANOS);
  }

  private static SeasonId season(ByteSource in, SeasonId[] seasons) throws ChunkIndexException {
    return seasons[seasonIndex(in, seasons.length)];
  }

  private static int seasonIndex(ByteSource in, int seasonCount) throws ChunkIndexException {
    if (seasonCount == 0) {
      throw in.corrupt("season index without a dictionary");
    }
    return in.varInt(seasonCount - 1);
  }
}
