// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProtection;
import io.github.kjmorse2.mconeworld.domain.provenance.FirstObservation;
import io.github.kjmorse2.mconeworld.domain.provenance.ProvenanceEvidence;
import io.github.kjmorse2.mconeworld.domain.provenance.SeasonActivity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The delta log (format §6): the commits since the checkpoint, as {@link ChunkMutation}s.
 *
 * <pre>
 *   common header ("MCOL", version, index id, generation = the catalog's)
 *   frames: u32 payload length L | u32 CRC-32C(the 4 length bytes) | payload | u32 CRC-32C(payload)
 *   payload: season dictionary; varint chunk count; per chunk: utf8 dimension, i64 ChunkOrder key,
 *            varint mutation count, then per mutation a type byte and its fields:
 *              1 ObserveFirst   u8 legacy; [varint season]; zigzag seconds; varint nanos; u8 evidence
 *              2 RecordActivity varint season; zigzag seconds; varint nanos; u8 evidence
 *              3 SetProtection  u8 (0 NONE, 1 PROTECTED)
 *              4 Replace        record (RecordCodec, base second 0)
 *              5 Remove
 * </pre>
 *
 * <p><b>Torn tail versus corruption.</b> Only an <em>incomplete</em> final frame is a torn tail:
 * fewer bytes than a frame header remain, or a valid header's payload runs past the end of the
 * file. It is a commit that was never acknowledged, and is ignored. A header-checksum mismatch, or
 * a complete frame whose payload checksum fails, is corruption wherever it is, because treating it
 * as a tail would silently drop acknowledged commits after it (the Prompt 08 probe found exactly
 * that).
 */
final class DeltaLog {
  private DeltaLog() {}

  /** Result of reading a log: its commits in order, and where the complete frames end. */
  record Replay(
      UUID indexId,
      long generation,
      boolean newerMinor,
      Overlay overlay,
      long validEnd,
      long size,
      int tailCrc) {
    boolean tornTail() {
      return validEnd != size;
    }
  }

  static byte[] header(UUID indexId, long generation) {
    ByteSink out = new ByteSink(IndexFormat.HEADER_BYTES);
    IndexFormat.writeHeader(out, IndexFormat.LOG_MAGIC, indexId, generation);
    return out.toByteArray();
  }

  /**
   * Reads every complete frame of {@code bytes}.
   *
   * @throws ChunkIndexException {@code LOG_CORRUPT}, {@code HEADER_CORRUPT}, {@code
   *     UNSUPPORTED_VERSION}, or {@code TOO_LARGE} if the replay would exceed {@code maxMutations}
   */
  static Replay replay(byte[] bytes, String scope, int maxMutations) throws ChunkIndexException {
    IndexFormat.FileHeader header =
        IndexFormat.readHeader(bytes, 0, bytes.length, IndexFormat.LOG_MAGIC, scope);
    Overlay overlay = Overlay.EMPTY;
    int tailCrc = 0;
    int position = IndexFormat.HEADER_BYTES;
    while (position < bytes.length) {
      int remaining = bytes.length - position;
      if (remaining < 8) {
        break; // torn inside a frame header
      }
      String frameScope = scope + " frame at " + position;
      ByteSource head =
          new ByteSource(bytes, position, 8, ChunkIndexIssueCodes.LOG_CORRUPT, frameScope);
      int length = head.i32();
      if (head.i32() != IndexFormat.crc(bytes, position, 4)) {
        throw head.corrupt("frame header checksum mismatch");
      }
      if (length < 0 || length > IndexFormat.MAX_FRAME_BYTES) {
        throw head.corrupt("frame length " + length);
      }
      if (remaining < IndexFormat.FRAME_OVERHEAD + length) {
        break; // torn inside a payload: never acknowledged
      }
      ByteSource crc =
          new ByteSource(
              bytes, position + 8 + length, 4, ChunkIndexIssueCodes.LOG_CORRUPT, frameScope);
      int payloadCrc = crc.i32();
      if (payloadCrc != IndexFormat.crc(bytes, position + 8, length)) {
        throw crc.corrupt("frame checksum mismatch");
      }
      tailCrc = payloadCrc;
      Map<ChunkPosition, List<ChunkMutation>> mutations =
          decodePayload(
              new ByteSource(
                  bytes, position + 8, length, ChunkIndexIssueCodes.LOG_CORRUPT, frameScope));
      overlay = overlay.plus(mutations);
      if (overlay.mutations() > maxMutations) {
        throw new ChunkIndexException(
            ChunkIndexIssueCodes.TOO_LARGE,
            "the log holds more than " + maxMutations + " mutations",
            scope,
            null);
      }
      position += IndexFormat.FRAME_OVERHEAD + length;
    }
    return new Replay(
        header.indexId(),
        header.number(),
        header.newerMinor(),
        overlay,
        position,
        bytes.length,
        tailCrc);
  }

  /** One frame holding {@code mutations} (per chunk, in the order they were applied). */
  static byte[] frame(Map<ChunkPosition, List<ChunkMutation>> mutations) {
    byte[] payload = encodePayload(mutations);
    ByteSink out = new ByteSink(payload.length + IndexFormat.FRAME_OVERHEAD);
    out.i32(payload.length);
    out.i32(IndexFormat.crc(out.array(), 0, 4));
    out.bytes(payload);
    out.i32(IndexFormat.crc(payload, 0, payload.length));
    return out.toByteArray();
  }

  // ---- payload codec -------------------------------------------------------------------------

  private static byte[] encodePayload(Map<ChunkPosition, List<ChunkMutation>> mutations) {
    TreeMap<SeasonId, Integer> dictionary = new TreeMap<>();
    for (List<ChunkMutation> list : mutations.values()) {
      for (ChunkMutation m : list) {
        switch (m) {
          case ChunkMutation.ObserveFirst o -> {
            SeasonId s = o.first().knownSeason();
            if (s != null) {
              dictionary.put(s, 0);
            }
          }
          case ChunkMutation.RecordActivity a -> dictionary.put(a.activity().season(), 0);
          case ChunkMutation.Replace r -> {
            SeasonId s = r.record().firstObservation().knownSeason();
            if (s != null) {
              dictionary.put(s, 0);
            }
            r.record().activity().forEach(a -> dictionary.put(a.season(), 0));
          }
          case ChunkMutation.SetProtection p -> {}
          case ChunkMutation.Remove r -> {}
        }
      }
    }
    int next = 0;
    for (Map.Entry<SeasonId, Integer> e : dictionary.entrySet()) {
      e.setValue(next++);
    }
    ByteSink out = new ByteSink(64 + mutations.size() * 48);
    IndexFormat.writeSeasons(out, dictionary.keySet());
    out.varInt(mutations.size());
    for (Map.Entry<ChunkPosition, List<ChunkMutation>> e : mutations.entrySet()) {
      out.utf(e.getKey().dimension().namespacedId()).i64(ChunkOrder.key(e.getKey()));
      out.varInt(e.getValue().size());
      for (ChunkMutation m : e.getValue()) {
        switch (m) {
          case ChunkMutation.ObserveFirst o -> {
            SeasonId s = o.first().knownSeason();
            out.u8(1).u8(s == null ? 1 : 0);
            if (s != null) {
              out.varInt(index(dictionary, s));
            }
            RecordCodec.writeInstant(out, o.first().observedAt(), 0);
            out.u8(EvidenceCodes.encode(o.first().evidence()));
          }
          case ChunkMutation.RecordActivity a -> {
            out.u8(2).varInt(index(dictionary, a.activity().season()));
            RecordCodec.writeInstant(out, a.activity().lastObservedAt(), 0);
            out.u8(EvidenceCodes.encode(a.activity().evidence()));
          }
          case ChunkMutation.SetProtection p ->
              out.u8(3).u8(p.protection() == ChunkProtection.PROTECTED ? 1 : 0);
          case ChunkMutation.Replace r -> {
            out.u8(4);
            RecordCodec.write(out, r.record(), s -> index(dictionary, s), 0);
          }
          case ChunkMutation.Remove r -> out.u8(5);
        }
      }
    }
    return out.toByteArray();
  }

  private static int index(Map<SeasonId, Integer> dictionary, SeasonId season) {
    Integer index = dictionary.get(season);
    if (index == null) {
      throw new IllegalStateException("season missing from the frame dictionary: " + season);
    }
    return index;
  }

  private static Map<ChunkPosition, List<ChunkMutation>> decodePayload(ByteSource in)
      throws ChunkIndexException {
    SeasonId[] seasons = IndexFormat.readSeasons(in);
    int chunks = in.count(Integer.MAX_VALUE, 12);
    Map<ChunkPosition, List<ChunkMutation>> out = new LinkedHashMap<>();
    for (int i = 0; i < chunks; i++) {
      DimensionKey dimension;
      try {
        dimension = DimensionKey.parse(in.utf(DimensionKey.MAX_LENGTH * 4));
      } catch (RuntimeException e) {
        throw in.corrupt("invalid dimension key", e);
      }
      ChunkPosition chunk = ChunkOrder.position(dimension, in.i64());
      int count = in.count(Integer.MAX_VALUE, 1);
      List<ChunkMutation> mutations = new ArrayList<>(count);
      for (int m = 0; m < count; m++) {
        mutations.add(readMutation(in, chunk, seasons));
      }
      if (out.put(chunk, mutations) != null) {
        throw in.corrupt("chunk " + chunk + " appears twice in one frame");
      }
    }
    in.requireEnd();
    return out;
  }

  private static ChunkMutation readMutation(ByteSource in, ChunkPosition chunk, SeasonId[] seasons)
      throws ChunkIndexException {
    int type = in.u8();
    try {
      return switch (type) {
        case 1 -> {
          int legacy = in.u8();
          if (legacy > 1) {
            throw in.corrupt("legacy flag " + legacy);
          }
          SeasonId season = legacy == 1 ? null : seasons[seasonIndex(in, seasons)];
          Instant at = RecordCodec.readInstant(in, 0);
          ProvenanceEvidence evidence = EvidenceCodes.decode(in);
          yield new ChunkMutation.ObserveFirst(
              chunk,
              season == null
                  ? new FirstObservation.Legacy(at, evidence)
                  : new FirstObservation.InSeason(season, at, evidence));
        }
        case 2 -> {
          SeasonId season = seasons[seasonIndex(in, seasons)];
          Instant at = RecordCodec.readInstant(in, 0);
          yield new ChunkMutation.RecordActivity(
              chunk, new SeasonActivity(season, at, EvidenceCodes.decode(in)));
        }
        case 3 -> {
          int value = in.u8();
          if (value > 1) {
            throw in.corrupt("protection code " + value);
          }
          yield new ChunkMutation.SetProtection(
              chunk, value == 1 ? ChunkProtection.PROTECTED : ChunkProtection.NONE);
        }
        case 4 -> new ChunkMutation.Replace(RecordCodec.read(in, chunk, seasons, 0));
        case 5 -> new ChunkMutation.Remove(chunk);
        default -> throw in.corrupt("unknown mutation type " + type);
      };
    } catch (RuntimeException e) {
      throw in.corrupt("invalid mutation for " + chunk + ": " + e.getMessage(), e);
    }
  }

  private static int seasonIndex(ByteSource in, SeasonId[] seasons) throws ChunkIndexException {
    if (seasons.length == 0) {
      throw in.corrupt("season index without a dictionary");
    }
    return in.varInt(seasons.length - 1);
  }
}
