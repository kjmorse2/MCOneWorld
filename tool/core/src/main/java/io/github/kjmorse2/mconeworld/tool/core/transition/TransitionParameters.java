// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.transition;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.DisplayName;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.coordinate.BlockPosition;
import io.github.kjmorse2.mconeworld.domain.coordinate.DimensionOrigin;
import io.github.kjmorse2.mconeworld.domain.operation.ActionKey;
import io.github.kjmorse2.mconeworld.domain.operation.OperationPlan;
import io.github.kjmorse2.mconeworld.domain.operation.OperationTarget;
import io.github.kjmorse2.mconeworld.domain.season.SeasonRecord;
import io.github.kjmorse2.mconeworld.tool.core.season.SeasonProposal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * The new season a transition creates, carried in its plan's targets so that a resumed operation
 * needs nothing but its journal. The plan is immutable and digested, so these values are exactly
 * what the user approved.
 *
 * <ul>
 *   <li>{@code Season(id)}: the new season
 *   <li>{@code Extension(mconeworld:season_name, "<n>.<hex>")}: the name's UTF-8 bytes in lowercase
 *       hex, split into numbered parts (target keys are short tokens)
 *   <li>{@code Extension(mconeworld:season_origin, "<dimension>:<x>:<y>:<z>")}: one per origin
 *   <li>{@code Extension(mconeworld:season_spawn, "<dimension>:<x>:<y>:<z>")}: the planned spawn
 *   <li>{@code Extension(mconeworld:archived_dimension, "minecraft:the_end")}: present when the End
 *       is archived
 * </ul>
 *
 * The season's creation time is the plan's.
 *
 * @param season the new season's id
 * @param name its display name
 * @param placement its origins and spawn
 * @param archivesEnd whether the End is archived
 */
record TransitionParameters(
    SeasonId season, DisplayName name, SeasonProposal placement, boolean archivesEnd) {
  static final ActionKey NAME = ActionKey.of("mconeworld:season_name");
  static final ActionKey ORIGIN = ActionKey.of("mconeworld:season_origin");
  static final ActionKey SPAWN = ActionKey.of("mconeworld:season_spawn");
  static final ActionKey ARCHIVED = ActionKey.of("mconeworld:archived_dimension");

  /** Hex digits per name part, leaving room for the part number. */
  private static final int PART = 120;

  TransitionParameters {
    Objects.requireNonNull(season, "season");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(placement, "placement");
  }

  /** The targets, in order: the season, the name parts, the origins, the spawn, the End. */
  List<OperationTarget> targets() {
    List<OperationTarget> targets = new ArrayList<>();
    targets.add(new OperationTarget.Season(season));
    String encoded = HexFormat.of().formatHex(name.value().getBytes(StandardCharsets.UTF_8));
    for (int i = 0, part = 0; i < encoded.length(); i += PART, part++) {
      String chunk = encoded.substring(i, Math.min(encoded.length(), i + PART));
      targets.add(new OperationTarget.Extension(NAME, part + "." + chunk));
    }
    targets.add(new OperationTarget.Extension(ORIGIN, position(placement.overworld())));
    targets.add(new OperationTarget.Extension(ORIGIN, position(placement.nether())));
    BlockPosition spawn = placement.spawn();
    targets.add(
        new OperationTarget.Extension(
            SPAWN, position(spawn.dimension(), spawn.x(), spawn.y(), spawn.z())));
    if (archivesEnd) {
      targets.add(new OperationTarget.Extension(ARCHIVED, DimensionKey.THE_END.toString()));
    }
    return targets;
  }

  /** The index of the archived-dimension target, or -1. */
  static int archivedTarget(List<OperationTarget> targets) {
    for (int i = 0; i < targets.size(); i++) {
      if (targets.get(i) instanceof OperationTarget.Extension e && e.type().equals(ARCHIVED)) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Reads the parameters back from {@code plan}.
   *
   * @throws IllegalArgumentException if the plan does not carry them in this form
   */
  static TransitionParameters of(OperationPlan plan) {
    SeasonId season = null;
    StringBuilder name = new StringBuilder();
    DimensionOrigin overworld = null;
    DimensionOrigin nether = null;
    BlockPosition spawn = null;
    boolean end = false;
    int part = 0;
    for (OperationTarget target : plan.targets()) {
      if (target instanceof OperationTarget.Season s && season == null) {
        season = s.season();
      } else if (target instanceof OperationTarget.Extension e) {
        if (e.type().equals(NAME)) {
          String prefix = part++ + ".";
          if (!e.key().startsWith(prefix)) {
            throw new IllegalArgumentException("Season name parts out of order");
          }
          name.append(e.key().substring(prefix.length()));
        } else if (e.type().equals(ORIGIN)) {
          long[] p = coordinates(e.key());
          DimensionOrigin origin =
              new DimensionOrigin(dimension(e.key()), p[0], Math.toIntExact(p[1]), p[2]);
          if (origin.dimension().equals(DimensionKey.OVERWORLD)) {
            overworld = origin;
          } else if (origin.dimension().equals(DimensionKey.THE_NETHER)) {
            nether = origin;
          } else {
            throw new IllegalArgumentException("Unexpected origin dimension " + e.key());
          }
        } else if (e.type().equals(SPAWN)) {
          long[] p = coordinates(e.key());
          spawn = new BlockPosition(dimension(e.key()), p[0], Math.toIntExact(p[1]), p[2]);
        } else if (e.type().equals(ARCHIVED)) {
          end = true;
        }
      }
    }
    if (season == null || name.isEmpty() || overworld == null || nether == null || spawn == null) {
      throw new IllegalArgumentException("The plan does not describe a new season");
    }
    DisplayName decoded =
        DisplayName.of(
            new String(HexFormat.of().parseHex(name.toString()), StandardCharsets.UTF_8));
    return new TransitionParameters(
        season, decoded, new SeasonProposal(overworld, nether, spawn), end);
  }

  /** The new season as the commit appends it: planned, with both origins. */
  SeasonRecord record(Instant createdAt) {
    return SeasonRecord.planned(season, name, createdAt, placement.spawn())
        .withOrigin(placement.overworld())
        .withOrigin(placement.nether());
  }

  private static String position(DimensionOrigin origin) {
    return position(origin.dimension(), origin.x(), origin.y(), origin.z());
  }

  private static String position(DimensionKey dimension, long x, int y, long z) {
    return dimension + ":" + x + ":" + y + ":" + z;
  }

  /** The dimension of a {@code <dimension>:<x>:<y>:<z>} key: all before the last three parts. */
  private static DimensionKey dimension(String key) {
    int end = key.length();
    for (int i = 0; i < 3; i++) {
      end = key.lastIndexOf(':', end - 1);
      if (end <= 0) {
        throw new IllegalArgumentException("Malformed position " + key);
      }
    }
    return DimensionKey.parse(key.substring(0, end));
  }

  private static long[] coordinates(String key) {
    long[] values = new long[3];
    int end = key.length();
    for (int i = 2; i >= 0; i--) {
      int start = key.lastIndexOf(':', end - 1);
      if (start <= 0) {
        throw new IllegalArgumentException("Malformed position " + key);
      }
      values[i] = Long.parseLong(key.substring(start + 1, end));
      end = start;
    }
    return values;
  }

  @Override
  public String toString() {
    return "season " + season + " \"" + name.value() + "\"";
  }
}
