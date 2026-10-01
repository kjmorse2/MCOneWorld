// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.operation;

import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.domain.operation.OperationPlan;
import io.github.kjmorse2.mconeworld.sidecar.manifest.SchemaVersion;
import java.util.List;
import java.util.Objects;

/**
 * A decoded plan file.
 *
 * @param plan the plan
 * @param digest the plan digest, computed over the whole parsed document (including members this
 *     reader does not understand), so it is the digest the writer recorded
 * @param schemaVersion the version the file declares
 * @param unknownMembers locations of members this reader does not understand; only possible for a
 *     newer minor version
 */
public record DecodedPlan(
    OperationPlan plan, Checksum digest, SchemaVersion schemaVersion, List<String> unknownMembers) {
  public DecodedPlan {
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(digest, "digest");
    Objects.requireNonNull(schemaVersion, "schemaVersion");
    unknownMembers = List.copyOf(unknownMembers);
  }

  /**
   * Returns whether this reader may act on the plan. A plan from a newer minor version may carry
   * semantics this reader would silently ignore, so it is inspect-only.
   */
  public boolean executable() {
    return schemaVersion.compareTo(OperationPlanCodec.CURRENT) <= 0;
  }
}
