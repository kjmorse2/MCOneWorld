// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.archive;

import io.github.kjmorse2.mconeworld.domain.DomainErrorCode;
import io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException;
import io.github.kjmorse2.mconeworld.domain.PlayerArchiveId;
import io.github.kjmorse2.mconeworld.domain.PlayerId;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.coordinate.BlockPosition;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * <b>Metadata only</b> about one player's archived season state. Item and progression serialization
 * is a separate, later decision and is intentionally absent.
 *
 * <p>The player keeps their permanent account UUID ({@link PlayerId}). An archive never replaces
 * it. Physical containers are the player-facing archive, and this sidecar record exists before they
 * are placed (root AGENTS.md §11).
 *
 * @param id stable identity of this archive record
 * @param player the player's existing account identity
 * @param season the season whose progression was archived
 * @param archivedAt when archival happened, supplied by the caller
 * @param categories which progression categories were archived; non-empty, stored in declaration
 *     order, unmodifiable
 * @param physicalContainers absolute positions of placed archive containers, in placement order;
 *     empty if none are placed yet or the central archive fallback is used
 */
public record PlayerArchiveRecord(
    PlayerArchiveId id,
    PlayerId player,
    SeasonId season,
    Instant archivedAt,
    Set<ArchiveCategory> categories,
    List<BlockPosition> physicalContainers) {
  /**
   * @throws InvalidDomainValueException with {@link DomainErrorCode#VALUE_INVALID} if {@code
   *     categories} is empty
   */
  public PlayerArchiveRecord {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(player, "player");
    Objects.requireNonNull(season, "season");
    Objects.requireNonNull(archivedAt, "archivedAt");
    if (categories.isEmpty()) {
      throw new InvalidDomainValueException(
          DomainErrorCode.VALUE_INVALID, "A player archive must cover at least one category");
    }
    // EnumSet iterates in declaration order, so the order is deterministic; wrap to prevent
    // aliasing.
    categories = Collections.unmodifiableSet(EnumSet.copyOf(categories));
    List<BlockPosition> containers = new ArrayList<>(physicalContainers);
    containers.forEach(c -> Objects.requireNonNull(c, "container"));
    physicalContainers = List.copyOf(containers);
  }
}
