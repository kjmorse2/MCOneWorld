// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.fixtures.index;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProvenance;
import io.github.kjmorse2.mconeworld.domain.provenance.FirstObservation;
import io.github.kjmorse2.mconeworld.domain.provenance.ProvenanceEvidence;
import io.github.kjmorse2.mconeworld.domain.provenance.SeasonActivity;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkCursor;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexException;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexReader;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkQuery;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A canonical one-line text form of a record, for test diagnostics and for digests that compare
 * whole indexes. Every field is written, so two records have the same text exactly when they are
 * equal.
 *
 * <p>This is test support, not the diagnostic export format, which the chunk-index format defines
 * (Prompt 09).
 */
public final class ChunkRecordText {
  private ChunkRecordText() {}

  /** The canonical line for {@code record}, without a line terminator. */
  public static String line(ChunkProvenance record) {
    StringBuilder out = new StringBuilder(160);
    out.append(record.chunk().dimension())
        .append(' ')
        .append(record.chunk().x())
        .append(' ')
        .append(record.chunk().z())
        .append(" first=");
    FirstObservation first = record.firstObservation();
    switch (first) {
      case FirstObservation.InSeason s -> out.append(s.season());
      case FirstObservation.Legacy l -> out.append("legacy");
    }
    out.append('@').append(first.observedAt());
    evidence(out, first.evidence());
    out.append(" activity=[");
    for (int i = 0; i < record.activity().size(); i++) {
      SeasonActivity activity = record.activity().get(i);
      out.append(i == 0 ? "" : ",").append(activity.season()).append('@');
      out.append(activity.lastObservedAt());
      evidence(out, activity.evidence());
    }
    return out.append("] protection=").append(record.protection()).toString();
  }

  /**
   * The SHA-256 (lowercase hex) of every record's line, LF-terminated, in dimension and then {@link
   * io.github.kjmorse2.mconeworld.sidecar.index.ChunkOrder} order. Two readers with the same digest
   * hold the same records.
   */
  public static String digest(ChunkIndexReader reader) throws ChunkIndexException {
    MessageDigest sha256;
    try {
      sha256 = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Every Java platform provides SHA-256", e);
    }
    for (DimensionKey dimension : reader.dimensions()) {
      try (ChunkCursor cursor = reader.scan(ChunkQuery.all(dimension))) {
        for (ChunkProvenance record = cursor.next(); record != null; record = cursor.next()) {
          sha256.update((line(record) + "\n").getBytes(StandardCharsets.UTF_8));
        }
      }
    }
    return HexFormat.of().formatHex(sha256.digest());
  }

  private static void evidence(StringBuilder out, ProvenanceEvidence evidence) {
    out.append('/').append(evidence.source()).append('/').append(evidence.confidence());
  }
}
