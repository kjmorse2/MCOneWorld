// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import io.github.kjmorse2.mconeworld.domain.provenance.Confidence;
import io.github.kjmorse2.mconeworld.domain.provenance.EvidenceSource;
import io.github.kjmorse2.mconeworld.domain.provenance.ProvenanceEvidence;

/**
 * The persisted evidence byte, {@code source << 2 | confidence}, with explicit codes (format §3).
 * Never enum ordinals, so reordering or adding constants cannot change stored meaning. A new code
 * is a major format change, because older readers cannot represent it.
 */
final class EvidenceCodes {
  private EvidenceCodes() {}

  static int encode(ProvenanceEvidence evidence) {
    int source =
        switch (evidence.source()) {
          case RUNTIME_OBSERVATION -> 0;
          case OFFLINE_SCAN -> 1;
          case REGION_FILE_TIMESTAMP -> 2;
          case INHABITED_TIME -> 3;
          case WORLD_IMPORT -> 4;
          case ADMINISTRATOR -> 5;
        };
    int confidence =
        switch (evidence.confidence()) {
          case HIGH -> 0;
          case MEDIUM -> 1;
          case LOW -> 2;
        };
    return source << 2 | confidence;
  }

  static ProvenanceEvidence decode(ByteSource in) throws ChunkIndexException {
    int code = in.u8();
    EvidenceSource source =
        switch (code >>> 2) {
          case 0 -> EvidenceSource.RUNTIME_OBSERVATION;
          case 1 -> EvidenceSource.OFFLINE_SCAN;
          case 2 -> EvidenceSource.REGION_FILE_TIMESTAMP;
          case 3 -> EvidenceSource.INHABITED_TIME;
          case 4 -> EvidenceSource.WORLD_IMPORT;
          case 5 -> EvidenceSource.ADMINISTRATOR;
          default -> throw in.corrupt("unknown evidence source code " + (code >>> 2));
        };
    Confidence confidence =
        switch (code & 3) {
          case 0 -> Confidence.HIGH;
          case 1 -> Confidence.MEDIUM;
          case 2 -> Confidence.LOW;
          default -> throw in.corrupt("unknown confidence code 3");
        };
    return new ProvenanceEvidence(source, confidence);
  }
}
