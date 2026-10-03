// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.season;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.archive.ArchiveState;
import io.github.kjmorse2.mconeworld.domain.coordinate.DimensionOrigin;
import io.github.kjmorse2.mconeworld.domain.coordinate.OriginAlignment;
import io.github.kjmorse2.mconeworld.domain.coordinate.OverworldNetherScaling;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationIssue;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationResult;
import io.github.kjmorse2.mconeworld.fixtures.DomainFixtures;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkOrder;
import io.github.kjmorse2.mconeworld.tool.core.inventory.WorldChunks;
import io.github.kjmorse2.mconeworld.tool.core.inventory.WorldInventory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

/** The planner's rules, on explicit cases and on random worlds. */
class SeasonPlannerTest {
  private static final DimensionKey O = DimensionKey.OVERWORLD;
  private static final DimensionKey N = DimensionKey.THE_NETHER;
  private static final SeasonPlanner PLANNER = new SeasonPlanner(new PlannerSettings(2_048, 64));

  /** A world from chunk coordinates {x, z} per dimension. */
  private static WorldChunks world(Map<DimensionKey, List<int[]>> chunks) {
    Map<DimensionKey, long[]> keys = new TreeMap<>();
    chunks.forEach(
        (d, list) -> {
          long[] k = list.stream().mapToLong(c -> ChunkOrder.key(c[0], c[1])).distinct().toArray();
          Arrays.sort(k);
          keys.put(d, k);
        });
    return new WorldChunks(
        keys,
        new WorldInventory(
            List.of(), Checksum.parse("sha256:" + "0".repeat(64)), ValidationResult.ok()));
  }

  private static List<ValidationIssue> issues(
      DimensionOrigin origin, WorldChunks world, ArchiveState archive) {
    return PLANNER.validate(origin, world, archive).issues();
  }

  private static DimensionOrigin at(long x, long z) {
    return new DimensionOrigin(O, x, 64, z);
  }

  @Test
  void anEmptyWorldStartsAtTheCentre() {
    SeasonProposal p = PLANNER.propose(world(Map.of()), ArchiveState.empty()).orElseThrow();
    assertThat(p.overworld()).isEqualTo(at(0, 0));
    assertThat(p.nether()).isEqualTo(new DimensionOrigin(N, 0, 64, 0));
  }

  @Test
  void theProposalLiesJustBeyondEverythingOnTheNearestAxis() {
    // Overworld from chunk -10 to 10; the Nether reaches chunk 30 (Overworld block 3,967)
    // eastwards.
    List<int[]> overworld = new ArrayList<>();
    for (int i = -10; i <= 10; i++) {
      overworld.add(new int[] {i, i});
    }
    WorldChunks world = world(Map.of(O, overworld, N, List.<int[]>of(new int[] {30, 0})));
    SeasonProposal p = PLANNER.propose(world, ArchiveState.empty()).orElseThrow();
    // South (+Z) is nearest: the box's max Z is 175, so 175 + 2,048 + 1 rounded up to 128.
    assertThat(p.overworld()).isEqualTo(at(0, 2_304));
    assertThat(OverworldNetherScaling.isPaired(p.overworld(), p.nether())).isTrue();
    assertThat(PLANNER.validate(p.overworld(), world, ArchiveState.empty()).isValid()).isTrue();
  }

  @Test
  void everyRuleIsReported() {
    WorldChunks world =
        world(Map.of(O, List.<int[]>of(new int[] {0, 0}), N, List.<int[]>of(new int[] {200, 0})));
    ArchiveState archive = ArchiveState.empty();
    assertThat(issues(at(100, 0), world, archive))
        .extracting(ValidationIssue::code)
        .containsExactly(SeasonIssueCodes.ORIGIN_MISALIGNED);
    assertThat(issues(at(1_024, 0), world, archive))
        .extracting(ValidationIssue::code)
        .containsExactly(SeasonIssueCodes.OVERWORLD_OCCUPIED);
    // Nether chunk 200 covers Overworld blocks 25,600 to 25,727.
    assertThat(issues(at(25_600, 0), world, archive))
        .extracting(ValidationIssue::code)
        .containsExactly(SeasonIssueCodes.NETHER_OCCUPIED);
    assertThat(issues(at(29_999_872, 0), world, archive))
        .extracting(ValidationIssue::code)
        .containsExactly(SeasonIssueCodes.OUT_OF_BOUNDS);
    ArchiveState withSeason =
        ArchiveState.empty()
            .appendSeason(DomainFixtures.season(1).origin(O, 100_000, 64, 0).build());
    assertThat(issues(at(102_400, 0), world, withSeason))
        .extracting(ValidationIssue::code)
        .containsExactly(SeasonIssueCodes.SEASON_OVERLAP);
    assertThat(issues(at(104_192, 0), world, withSeason)).isEmpty();
  }

  @Test
  void cancelledSeasonsReserveNothingButTheirIdsStay() {
    ArchiveState cancelled =
        ArchiveState.empty()
            .appendSeason(DomainFixtures.season(1).origin(O, 0, 64, 0).build())
            .cancelSeason(DomainFixtures.seasonId(1));
    assertThat(PLANNER.propose(world(Map.of()), cancelled).orElseThrow().overworld())
        .isEqualTo(at(0, 0));
  }

  @Property(tries = 300)
  void proposalsAreAlignedPairedInBoundsAndClear(
      @ForAll("worlds") Map<DimensionKey, List<int[]>> chunks) {
    WorldChunks world = world(chunks);
    SeasonProposal p = PLANNER.propose(world, ArchiveState.empty()).orElseThrow();
    assertThat(OriginAlignment.recommendedFor(p.overworld()).isAligned()).isTrue();
    assertThat(OverworldNetherScaling.isPaired(p.overworld(), p.nether())).isTrue();
    assertThat(p.spawn().x()).isEqualTo(p.overworld().x());
    assertThat(PLANNER.validate(p.overworld(), world, ArchiveState.empty()).issues()).isEmpty();
    // A chunk inside the clear area is always caught.
    Map<DimensionKey, List<int[]>> crowded = new TreeMap<>(chunks);
    List<int[]> overworld = new ArrayList<>(crowded.getOrDefault(O, List.of()));
    overworld.add(
        new int[] {
          (int) Math.floorDiv(p.overworld().x(), 16) + 3,
          (int) Math.floorDiv(p.overworld().z(), 16) - 3
        });
    crowded.put(O, overworld);
    assertThat(
            PLANNER
                .validate(p.overworld(), world(crowded), ArchiveState.empty())
                .has(SeasonIssueCodes.OVERWORLD_OCCUPIED))
        .isTrue();
  }

  @Provide
  Arbitrary<Map<DimensionKey, List<int[]>>> worlds() {
    Arbitrary<int[]> near =
        Combinators.combine(
                Arbitraries.integers().between(-2_000, 2_000),
                Arbitraries.integers().between(-2_000, 2_000))
            .as((x, z) -> new int[] {x, z});
    Arbitrary<int[]> far =
        Combinators.combine(
                Arbitraries.integers().between(-200_000, 200_000),
                Arbitraries.integers().between(-200_000, 200_000))
            .as((x, z) -> new int[] {x, z});
    Arbitrary<List<int[]>> chunks = Arbitraries.oneOf(near, far).list().ofMaxSize(200);
    return Combinators.combine(chunks, chunks.map(l -> l.subList(0, Math.min(l.size(), 30))))
        .as(
            (o, n) ->
                Map.of(O, o, N, n.stream().map(c -> new int[] {c[0] / 8, c[1] / 8}).toList()));
  }
}
