// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.fixtures.index;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import java.util.List;

/**
 * The named benchmark and test datasets from {@code docs/benchmarks/chunk-index/budgets.md}. Each
 * is a {@link SyntheticWorld} with a fixed seed, so a name and scale always produce the same
 * records.
 *
 * <p>{@code scale} multiplies every linear size (radii, trail lengths, square sides), so the record
 * count grows roughly with its square. Benchmarks use scale 1; tests use small scales such as 0.1
 * to get the same shapes with far fewer records. Origins and positions do not scale, so coordinate
 * edge cases are kept at every scale.
 */
public final class ChunkDatasets {
  /** Every dataset name, in the order of the budgets document. */
  public static final List<String> NAMES =
      List.of(
          "friend-server",
          "sparse-exploration",
          "dense-square",
          "multi-season",
          "negative-coords",
          "revisited",
          "millions");

  private static final DimensionKey OVERWORLD = DimensionKey.OVERWORLD;
  private static final DimensionKey NETHER = DimensionKey.THE_NETHER;

  /** 100 000 blocks between season origins, in chunks. */
  private static final int ORIGIN_SPACING = 6250;

  private ChunkDatasets() {}

  /**
   * The dataset called {@code name} at linear {@code scale}.
   *
   * @throws IllegalArgumentException for an unknown name or a non-positive scale
   */
  public static SyntheticWorld named(String name, double scale) {
    if (!(scale > 0)) {
      throw new IllegalArgumentException("scale must be positive: " + scale);
    }
    Scale s = new Scale(scale);
    return switch (name) {
      case "friend-server" -> friendServer(s);
      case "sparse-exploration" -> sparseExploration(s);
      case "dense-square" -> denseSquare(s);
      case "multi-season" -> multiSeason(s);
      case "negative-coords" -> negativeCoords(s);
      case "revisited" -> revisited(s);
      case "millions" -> millions(s);
      default ->
          throw new IllegalArgumentException("Unknown dataset " + name + "; known: " + NAMES);
    };
  }

  /** Three seasons with spawn discs, a few trails, and some Nether: about 25 000 chunks. */
  private static SyntheticWorld friendServer(Scale s) {
    SyntheticWorld.Builder world = SyntheticWorld.builder("friend-server", 0xF00D);
    int[][] origins = {{0, 0}, {ORIGIN_SPACING, 0}, {0, ORIGIN_SPACING}};
    for (int[] origin : origins) {
      int season = world.season();
      world.disc(OVERWORLD, season, origin[0], origin[1], s.of(32), false);
      world.disc(OVERWORLD, season, origin[0], origin[1], s.of(4), true);
      for (int t = 0; t < 2; t++) {
        world.trail(OVERWORLD, season, origin[0], origin[1], s.of(260), 5);
      }
      world.disc(NETHER, season, origin[0] / 8, origin[1] / 8, s.of(18), false);
    }
    return world.build();
  }

  /** A legacy core and six seasons of long, thin travel paths: about 1 000 000 chunks. */
  private static SyntheticWorld sparseExploration(Scale s) {
    SyntheticWorld.Builder world = SyntheticWorld.builder("sparse-exploration", 0x5BA25E);
    world.disc(OVERWORLD, SyntheticWorld.LEGACY, 0, 0, s.of(150), false);
    for (int i = 0; i < 6; i++) {
      int season = world.season();
      int ox = (i % 3 - 1) * ORIGIN_SPACING;
      int oz = (i / 3) * ORIGIN_SPACING;
      world.disc(OVERWORLD, season, ox, oz, s.of(80), false);
      for (int t = 0; t < 4; t++) {
        world.trail(OVERWORLD, season, ox, oz, s.of(1850), 10);
      }
    }
    return world.build();
  }

  /** A pre-generated 1 024 × 1 024 legacy square, later revisited by one season. */
  private static SyntheticWorld denseSquare(Scale s) {
    SyntheticWorld.Builder world = SyntheticWorld.builder("dense-square", 0xDE45E);
    int half = s.of(512);
    world.rect(OVERWORLD, SyntheticWorld.LEGACY, -half, -half, half - 1, half - 1);
    int season = world.season();
    world.disc(OVERWORLD, season, 0, 0, s.of(100), false);
    return world.build();
  }

  /** Ten seasons with distant origins, each with a spawn area and trails. */
  private static SyntheticWorld multiSeason(Scale s) {
    SyntheticWorld.Builder world = SyntheticWorld.builder("multi-season", 0x3A5E);
    for (int i = 0; i < 10; i++) {
      int season = world.season();
      int ox = i * ORIGIN_SPACING + world.nextInt(-300, 300);
      int oz = (i % 3) * ORIGIN_SPACING + world.nextInt(-300, 300);
      world.disc(OVERWORLD, season, ox, oz, s.of(115), false);
      world.disc(OVERWORLD, season, ox, oz, s.of(6), true);
      for (int t = 0; t < 3; t++) {
        world.trail(OVERWORLD, season, ox, oz, s.of(1200), 8);
      }
    }
    return world.build();
  }

  /**
   * Negative coordinates only: around chunk -1, along region boundaries, far out, and at the very
   * end of the {@code int} chunk range. About 100 000 chunks.
   */
  private static SyntheticWorld negativeCoords(Scale s) {
    SyntheticWorld.Builder world = SyntheticWorld.builder("negative-coords", 0x0E6);
    int season = world.season();
    int later = world.season();
    world.rect(OVERWORLD, season, -s.of(40), -s.of(40), -1, -1);
    world.rect(OVERWORLD, season, -s.of(100) - 1, -33, -1, -31); // straddles region rows -2 and -1
    world.rect(OVERWORLD, later, -33, -s.of(100) - 1, -31, -1); // straddles region columns
    world.disc(OVERWORLD, season, -5000, -5000, s.of(100), false);
    world.disc(OVERWORLD, later, -5000, -5000, s.of(40), true);
    world.trail(OVERWORLD, later, -20_000, -20_000, s.of(600), 6);
    int edge = Integer.MIN_VALUE + 200;
    world.disc(OVERWORLD, season, edge, edge, s.of(120), false);
    world.rect(
        OVERWORLD,
        later,
        Integer.MIN_VALUE,
        Integer.MIN_VALUE,
        Integer.MIN_VALUE + s.of(31),
        Integer.MIN_VALUE + s.of(31));
    world.disc(NETHER, season, -625, -625, s.of(30), false);
    return world.build();
  }

  /** Six overlapping seasons: most chunks have activity in several seasons. */
  private static SyntheticWorld revisited(Scale s) {
    SyntheticWorld.Builder world = SyntheticWorld.builder("revisited", 0x2E715);
    world.disc(OVERWORLD, SyntheticWorld.LEGACY, 0, 0, s.of(120), false);
    for (int i = 0; i < 6; i++) {
      int season = world.season();
      world.disc(OVERWORLD, season, s.of(150) * i - s.of(375), 0, s.of(200), false);
      world.disc(OVERWORLD, season, s.of(150) * i - s.of(375), 0, s.of(12), true);
      world.trail(OVERWORLD, season, 0, 0, s.of(900), 7);
    }
    return world.build();
  }

  /** About 10 000 000 chunks: a large pre-generated square plus seasons, trails, and Nether. */
  private static SyntheticWorld millions(Scale s) {
    SyntheticWorld.Builder world = SyntheticWorld.builder("millions", 0x10E7);
    int half = s.of(1024);
    world.rect(OVERWORLD, SyntheticWorld.LEGACY, -half, -half, half - 1, half - 1);
    for (int i = 0; i < 12; i++) {
      int season = world.season();
      int ox = (i + 1) * ORIGIN_SPACING;
      int oz = (i % 4 - 2) * ORIGIN_SPACING;
      world.disc(OVERWORLD, season, ox, oz, s.of(220), false);
      world.disc(OVERWORLD, season, ox, oz, s.of(8), true);
      world.disc(OVERWORLD, season, 0, 0, s.of(300 + 40 * i), false); // revisits the square
      for (int t = 0; t < 4; t++) {
        world.trail(OVERWORLD, season, ox, oz, s.of(3600), 10);
      }
      world.disc(NETHER, season, ox / 8, oz / 8, s.of(90), false);
    }
    return world.build();
  }

  private record Scale(double factor) {
    int of(int size) {
      return Math.max(1, (int) Math.round(size * factor));
    }
  }
}
