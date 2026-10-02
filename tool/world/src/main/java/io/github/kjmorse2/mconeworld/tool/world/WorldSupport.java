// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.world;

import io.github.kjmorse2.mconeworld.domain.validation.IssueLocation;
import io.github.kjmorse2.mconeworld.domain.validation.Severity;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationIssue;
import io.github.kjmorse2.mconeworld.tool.world.layout.LayoutKind;
import io.github.kjmorse2.mconeworld.tool.world.layout.WorldLayout;
import io.github.kjmorse2.mconeworld.tool.world.level.LevelData;
import java.util.ArrayList;
import java.util.List;

/**
 * Whether the Tool may write to a world at all (tool/AGENTS.md §3, ADR 0010). Only the pinned data
 * version on the verified 26.1+ vanilla layout is writable; everything else is inspect-only.
 * Whether a server is running is a separate, momentary check ({@code SessionLockProbe}).
 *
 * @param writable whether world writes are allowed for this world
 * @param reasons why not, as warnings; empty when writable
 */
public record WorldSupport(boolean writable, List<ValidationIssue> reasons) {
  /** The only data version the Tool writes: Minecraft Java 26.2. */
  public static final int PINNED_DATA_VERSION = 4903;

  /** The pinned version's name, for messages. */
  public static final String PINNED_VERSION_NAME = "26.2";

  public WorldSupport {
    reasons = List.copyOf(reasons);
  }

  /** Classifies a world from its level data and layout. */
  public static WorldSupport classify(LevelData level, WorldLayout layout) {
    List<ValidationIssue> reasons = new ArrayList<>();
    if (level.dataVersion().isEmpty() || level.dataVersion().getAsInt() != PINNED_DATA_VERSION) {
      reasons.add(
          ValidationIssue.warning(
                  WorldIssueCodes.VERSION_NOT_PINNED,
                  IssueLocation.root().key("level.dat").field("DataVersion"),
                  "The world's data version is "
                      + (level.dataVersion().isPresent()
                          ? String.valueOf(level.dataVersion().getAsInt())
                          : "unknown")
                      + "; the Tool only modifies worlds of data version "
                      + PINNED_DATA_VERSION
                      + " (Minecraft "
                      + PINNED_VERSION_NAME
                      + ")")
              .withRemediation(
                  "Open and save the world with Minecraft " + PINNED_VERSION_NAME + " first."));
    }
    if (layout.kind() != LayoutKind.MODERN) {
      reasons.add(
          ValidationIssue.warning(
              WorldIssueCodes.LAYOUT_UNVERIFIED,
              IssueLocation.root(),
              "The world uses the "
                  + layout.kind()
                  + " layout; only the 26.1+ layout is writable"));
    }
    for (ValidationIssue finding : layout.findings().issues()) {
      if (finding.severity() == Severity.ERROR
          || finding.code().equals(WorldIssueCodes.LAYOUT_UNVERIFIED)
          || finding.code().equals(WorldIssueCodes.LAYOUT_MIXED)) {
        reasons.add(finding);
      }
    }
    return new WorldSupport(reasons.isEmpty(), reasons);
  }
}
