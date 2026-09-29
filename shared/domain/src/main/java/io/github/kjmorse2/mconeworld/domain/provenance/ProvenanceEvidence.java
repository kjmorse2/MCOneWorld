// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.provenance;

import java.util.Objects;

/**
 * Source and confidence of one provenance claim.
 *
 * @param source where the claim came from
 * @param confidence how strongly the source supports it
 */
public record ProvenanceEvidence(EvidenceSource source, Confidence confidence) {
  public ProvenanceEvidence {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(confidence, "confidence");
  }
}
