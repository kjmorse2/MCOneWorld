// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.operation;

import io.github.kjmorse2.mconeworld.domain.PlayerId;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkRectangle;
import java.util.Objects;

/**
 * One exact, logical target of an operation. Targets are portable: they name dimensions, chunks,
 * players, and records by their stable identities, never by host paths.
 *
 * <p>A chunk-area target lists the chunks a step may affect. It is not proof that those chunks
 * should be affected: selection evidence and protection checks belong to the planner (root {@code
 * AGENTS.md} §10, "never delete chunks based only on rectangular containment").
 */
public sealed interface OperationTarget {

  /** The persistent kind name, as written in plans. */
  String kind();

  /**
   * An inclusive rectangle of chunks in one dimension.
   *
   * @param area the chunks
   */
  record ChunkArea(ChunkRectangle area) implements OperationTarget {
    public ChunkArea {
      Objects.requireNonNull(area, "area");
    }

    @Override
    public String kind() {
      return "chunkArea";
    }
  }

  /**
   * One player's state, identified by the permanent account UUID.
   *
   * @param player the player
   */
  record Player(PlayerId player) implements OperationTarget {
    public Player {
      Objects.requireNonNull(player, "player");
    }

    @Override
    public String kind() {
      return "player";
    }
  }

  /**
   * One season record.
   *
   * @param season the season
   */
  record Season(SeasonId season) implements OperationTarget {
    public Season {
      Objects.requireNonNull(season, "season");
    }

    @Override
    public String kind() {
      return "season";
    }
  }

  /** The sidecar manifest as a whole, for operations that commit a sidecar transaction. */
  record SidecarManifest() implements OperationTarget {
    @Override
    public String kind() {
      return "sidecarManifest";
    }
  }

  /**
   * A target kind defined by an integration, such as {@code example-plugin:warp} with key {@code
   * spawn-hub}.
   *
   * @param type the namespaced target kind
   * @param key an opaque token {@code [A-Za-z0-9._:+-]{1,128}}. It cannot contain a slash or
   *     backslash, so it cannot hold a host path.
   */
  record Extension(ActionKey type, String key) implements OperationTarget {
    /** Maximum key length in characters. */
    public static final int MAX_KEY_LENGTH = 128;

    public Extension {
      Objects.requireNonNull(type, "type");
      Keys.check("extension target key", key, Keys.TOKEN, MAX_KEY_LENGTH);
    }

    @Override
    public String kind() {
      return "extension";
    }
  }
}
