// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.season;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.archive.ArchiveState;
import io.github.kjmorse2.mconeworld.domain.coordinate.BlockPosition;
import io.github.kjmorse2.mconeworld.domain.coordinate.DimensionOrigin;
import io.github.kjmorse2.mconeworld.domain.coordinate.OriginAlignment;
import io.github.kjmorse2.mconeworld.domain.coordinate.OverworldNetherScaling;
import io.github.kjmorse2.mconeworld.domain.coordinate.WorldBounds;
import io.github.kjmorse2.mconeworld.domain.season.SeasonRecord;
import io.github.kjmorse2.mconeworld.domain.season.SeasonStatus;
import io.github.kjmorse2.mconeworld.domain.validation.IssueLocation;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationIssue;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationResult;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkOrder;
import io.github.kjmorse2.mconeworld.tool.core.inventory.WorldChunks;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Proposes and validates the origin of a new season. Pure: the inputs are the world's occupied
 * chunks and the archive's seasons.
 *
 * <p>An origin is valid when:
 *
 * <ul>
 *   <li>it is aligned to 128 blocks (so its Nether counterpart is chunk-aligned; root AGENTS.md
 *       §8), and a misaligned one is reported, never rounded
 *   <li>the square of half-width {@link PlannerSettings#clearanceBlocks} around it fits inside the
 *       vanilla world border
 *   <li>that square holds no generated Overworld chunk, its counterpart divided by 8 holds no
 *       generated Nether chunk, and it does not overlap the square of any non-cancelled season
 * </ul>
 *
 * <p>{@link #propose} places the origin just beyond everything that exists, on the axis direction
 * nearest to (0, 0): the square then lies outside the bounding box of every occupied area, so it is
 * valid by construction (and validated anyway).
 */
public final class SeasonPlanner {
  private static final long ALIGN = OriginAlignment.RECOMMENDED_OVERWORLD_ALIGNMENT;
  private static final WorldBounds BORDER = WorldBounds.VANILLA_OVERWORLD;

  private final PlannerSettings settings;

  public SeasonPlanner(PlannerSettings settings) {
    this.settings = Objects.requireNonNull(settings, "settings");
  }

  /** An inclusive square of Overworld blocks. */
  record Box(long minX, long minZ, long maxX, long maxZ) {
    static Box around(long x, long z, long half) {
      return new Box(x - half, z - half, x + half, z + half);
    }

    boolean intersects(Box o) {
      return minX <= o.maxX && o.minX <= maxX && minZ <= o.maxZ && o.minZ <= maxZ;
    }

    Box union(Box o) {
      return new Box(
          Math.min(minX, o.minX),
          Math.min(minZ, o.minZ),
          Math.max(maxX, o.maxX),
          Math.max(maxZ, o.maxZ));
    }
  }

  /**
   * The origin nearest to (0, 0), on an axis direction, beyond every occupied area.
   *
   * @return the proposal, or empty when the world border leaves no room (see {@link #validate})
   */
  public Optional<SeasonProposal> propose(WorldChunks world, ArchiveState archive) {
    Box occupied = occupiedBox(world, archive).orElse(null);
    if (occupied == null) {
      return Optional.of(proposal(0, 0))
          .filter(p -> validate(p.overworld(), world, archive).isValid());
    }
    long half = settings.clearanceBlocks();
    List<long[]> candidates =
        List.of(
            new long[] {alignUp(occupied.maxX() + half + 1), 0},
            new long[] {0, alignUp(occupied.maxZ() + half + 1)},
            new long[] {alignDown(occupied.minX() - half - 1), 0},
            new long[] {0, alignDown(occupied.minZ() - half - 1)});
    SeasonProposal best = null;
    long bestDistance = Long.MAX_VALUE;
    for (long[] c : candidates) {
      SeasonProposal p = proposal(c[0], c[1]);
      long distance = Math.abs(c[0]) + Math.abs(c[1]);
      if (distance < bestDistance && validate(p.overworld(), world, archive).isValid()) {
        best = p;
        bestDistance = distance;
      }
    }
    return Optional.ofNullable(best);
  }

  /** Every rule for {@code overworldOrigin}, as errors with remediation. */
  public ValidationResult validate(
      DimensionOrigin overworldOrigin, WorldChunks world, ArchiveState archive) {
    if (!overworldOrigin.dimension().equals(DimensionKey.OVERWORLD)) {
      throw new IllegalArgumentException("The origin to validate is the Overworld's");
    }
    List<ValidationIssue> issues = new ArrayList<>();
    IssueLocation at = IssueLocation.root().field("origin");
    long x = overworldOrigin.x();
    long z = overworldOrigin.z();
    if (Math.floorMod(x, ALIGN) != 0 || Math.floorMod(z, ALIGN) != 0) {
      issues.add(
          ValidationIssue.error(
                  SeasonIssueCodes.ORIGIN_MISALIGNED,
                  at,
                  "The origin (" + x + ", " + z + ") is not aligned to " + ALIGN + " blocks")
              .withRemediation(
                  "Choose an aligned origin, for example ("
                      + alignDown(x)
                      + ", "
                      + alignDown(z)
                      + ") or ("
                      + alignUp(x)
                      + ", "
                      + alignUp(z)
                      + "); it is never rounded for you."));
      return ValidationResult.of(issues); // the other rules assume the paired Nether origin exists
    }
    long half = settings.clearanceBlocks();
    Box square = Box.around(x, z, half);
    if (!BORDER.containsHorizontal(square.minX(), square.minZ())
        || !BORDER.containsHorizontal(square.maxX(), square.maxZ())) {
      issues.add(
          ValidationIssue.error(
              SeasonIssueCodes.OUT_OF_BOUNDS,
              at,
              "The " + half + "-block clear area around the origin crosses the world border"));
    }
    long overworld = countIn(world, DimensionKey.OVERWORLD, square, 1);
    if (overworld > 0) {
      issues.add(
          ValidationIssue.error(
                  SeasonIssueCodes.OVERWORLD_OCCUPIED,
                  at,
                  overworld
                      + " generated Overworld chunks lie within "
                      + half
                      + " blocks of the origin")
              .withRemediation(
                  "Pick an origin further from existing terrain, or use the proposal."));
    }
    long nether =
        countIn(
            world, DimensionKey.THE_NETHER, square, OverworldNetherScaling.NETHER_COORDINATE_SCALE);
    if (nether > 0) {
      issues.add(
          ValidationIssue.error(
              SeasonIssueCodes.NETHER_OCCUPIED,
              at,
              nether
                  + " generated Nether chunks lie within the Nether counterpart of the clear area"));
    }
    for (SeasonRecord season : archive.seasons()) {
      if (season.status() == SeasonStatus.CANCELLED) {
        continue;
      }
      Optional<DimensionOrigin> other = season.origin(DimensionKey.OVERWORLD);
      if (other.isPresent()
          && Box.around(other.get().x(), other.get().z(), half).intersects(square)) {
        issues.add(
            ValidationIssue.error(
                SeasonIssueCodes.SEASON_OVERLAP,
                at,
                "The clear area overlaps season \"" + season.name() + "\"'s"));
      }
    }
    return ValidationResult.of(issues);
  }

  /**
   * The proposal for an aligned Overworld origin: the paired Nether origin and the nominal spawn.
   */
  public SeasonProposal proposal(long x, long z) {
    DimensionOrigin overworld = new DimensionOrigin(DimensionKey.OVERWORLD, x, settings.y(), z);
    return new SeasonProposal(
        overworld,
        OverworldNetherScaling.pairedNetherOrigin(overworld),
        new BlockPosition(DimensionKey.OVERWORLD, x, settings.y(), z));
  }

  /** The bounding box of every occupied chunk (Nether scaled by 8) and every season's square. */
  private Optional<Box> occupiedBox(WorldChunks world, ArchiveState archive) {
    Box box = null;
    for (DimensionKey dimension : List.of(DimensionKey.OVERWORLD, DimensionKey.THE_NETHER)) {
      long scale =
          dimension.equals(DimensionKey.THE_NETHER)
              ? OverworldNetherScaling.NETHER_COORDINATE_SCALE
              : 1;
      for (long key : world.keys().getOrDefault(dimension, new long[0])) {
        long cx = ChunkOrder.x(key);
        long cz = ChunkOrder.z(key);
        Box chunk =
            new Box(
                cx * 16 * scale,
                cz * 16 * scale,
                (cx * 16 + 15) * scale + scale - 1,
                (cz * 16 + 15) * scale + scale - 1);
        box = box == null ? chunk : box.union(chunk);
      }
    }
    for (SeasonRecord season : archive.seasons()) {
      Optional<DimensionOrigin> origin = season.origin(DimensionKey.OVERWORLD);
      if (season.status() != SeasonStatus.CANCELLED && origin.isPresent()) {
        Box square = Box.around(origin.get().x(), origin.get().z(), settings.clearanceBlocks());
        box = box == null ? square : box.union(square);
      }
    }
    return Optional.ofNullable(box);
  }

  /** How many chunks of {@code dimension}, scaled up by {@code scale}, intersect {@code square}. */
  private static long countIn(WorldChunks world, DimensionKey dimension, Box square, long scale) {
    long count = 0;
    for (long key : world.keys().getOrDefault(dimension, new long[0])) {
      long minX = ChunkOrder.x(key) * 16L * scale;
      long minZ = ChunkOrder.z(key) * 16L * scale;
      Box chunk = new Box(minX, minZ, minX + 16 * scale - 1, minZ + 16 * scale - 1);
      if (chunk.intersects(square)) {
        count++;
      }
    }
    return count;
  }

  private static long alignUp(long value) {
    return Math.floorDiv(value + ALIGN - 1, ALIGN) * ALIGN;
  }

  private static long alignDown(long value) {
    return Math.floorDiv(value, ALIGN) * ALIGN;
  }
}
