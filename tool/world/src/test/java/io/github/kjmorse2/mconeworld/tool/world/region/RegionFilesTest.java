// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.world.region;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.domain.coordinate.CoordinateLimits;
import io.github.kjmorse2.mconeworld.domain.coordinate.RegionPosition;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationIssue;
import io.github.kjmorse2.mconeworld.tool.world.WorldIssueCodes;
import io.github.kjmorse2.mconeworld.tool.world.layout.DimensionDirectory;
import io.github.kjmorse2.mconeworld.tool.world.layout.LayoutDetector;
import io.github.kjmorse2.mconeworld.tool.world.testing.SyntheticWorld;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Header parsing against exact ground truth, including every malformed-entry case. */
class RegionFilesTest {
  private static final DimensionKey O = DimensionKey.OVERWORLD;

  @TempDir Path temp;

  private DimensionDirectory overworld(SyntheticWorld world) throws Exception {
    return LayoutDetector.detect(world.dir()).dimension(O).orElseThrow();
  }

  @Test
  void theInventoryListsExactlyTheChunksWrittenAcrossNegativeAndExtremeRegions() throws Exception {
    Set<ChunkPosition> chunks = new HashSet<>();
    for (int x = -40; x < 40; x += 3) {
      for (int z = -40; z < 40; z += 5) {
        chunks.add(new ChunkPosition(O, x, z));
      }
    }
    chunks.add(new ChunkPosition(O, Integer.MIN_VALUE, Integer.MIN_VALUE));
    chunks.add(new ChunkPosition(O, Integer.MAX_VALUE, Integer.MAX_VALUE));
    SyntheticWorld world =
        SyntheticWorld.modern(temp.resolve("w"))
            .levelDat(4903)
            .chunks(O, "region", chunks, 1_780_000_000L);
    world.regionFile(O, "region", "r.5.5.mca", new byte[0]); // Minecraft leaves empty files behind
    List<ValidationIssue> skipped = new ArrayList<>();
    Set<ChunkPosition> seen = new HashSet<>();
    List<RegionPosition> order = new ArrayList<>();
    RegionInventory.scan(
        world.dir(),
        overworld(world),
        RegionKind.REGION,
        scan -> {
          order.add(scan.region());
          assertThat(scan.issues()).isEmpty();
          for (ChunkEntry e : scan.chunks()) {
            assertThat(seen.add(e.chunk())).isTrue();
            assertThat(e.chunk().region()).isEqualTo(scan.region());
            assertThat(e.lastSavedEpochSecond()).isEqualTo(1_780_000_000L);
          }
        },
        skipped::add);
    assertThat(seen).isEqualTo(chunks);
    assertThat(skipped).isEmpty();
    assertThat(order).isSorted();
    assertThat(order).contains(new RegionPosition(O, 5, 5));
    assertThat(order.getFirst())
        .isEqualTo(
            new RegionPosition(O, CoordinateLimits.MIN_REGION_XZ, CoordinateLimits.MIN_REGION_XZ));
  }

  @Test
  void onlyCanonicalRegionNamesAreRegionFiles() {
    assertThat(RegionFiles.parseName(O, "r.-1.0.mca")).hasValue(new RegionPosition(O, -1, 0));
    for (String name :
        List.of(
            "r.01.0.mca",
            "r.-0.0.mca",
            "r.1.mca",
            "r.1.2.mcc",
            "r.99999999999.0.mca",
            "R.1.2.mca",
            "r.1.2.mca.tmp")) {
      assertThat(RegionFiles.parseName(O, name)).as(name).isEmpty();
    }
  }

  @Test
  void otherFilesAndSymlinksAreReportedAndNeverRead() throws Exception {
    SyntheticWorld world =
        SyntheticWorld.modern(temp.resolve("w"))
            .levelDat(4903)
            .regionFile(O, "region", "notes.txt", new byte[] {1});
    java.nio.file.Files.createSymbolicLink(
        world.dimensionDir(O).resolve("region/r.0.0.mca"), world.dir().resolve("level.dat"));
    List<ValidationIssue> skipped = new ArrayList<>();
    RegionInventory.scan(
        world.dir(), overworld(world), RegionKind.REGION, scan -> {}, skipped::add);
    assertThat(skipped)
        .extracting(ValidationIssue::code)
        .containsExactlyInAnyOrder(WorldIssueCodes.UNKNOWN_FILE, WorldIssueCodes.SYMLINK_REFUSED);
  }

  /**
   * A header with the given 4-byte location entries at their indexes, in a file of {@code sectors}.
   */
  private static RegionScan parse(int sectors, int... indexAndLocation) {
    ByteBuffer header = ByteBuffer.allocate(RegionFiles.HEADER_BYTES);
    for (int i = 0; i < indexAndLocation.length; i += 2) {
      header.putInt(indexAndLocation[i] * 4, indexAndLocation[i + 1]);
    }
    return RegionFiles.parse(
        new RegionPosition(O, -1, -1),
        RegionKind.REGION,
        Path.of("r.-1.-1.mca"),
        (long) sectors * RegionFiles.SECTOR_BYTES,
        header,
        io.github.kjmorse2.mconeworld.domain.validation.IssueLocation.root());
  }

  @Test
  void entryIndexesMapToLocalChunkCoordinates() {
    RegionScan scan = parse(5, 0, (2 << 8) | 1, 33, (3 << 8) | 1, 1023, (4 << 8) | 1);
    assertThat(scan.chunks())
        .extracting(ChunkEntry::chunk)
        .containsExactly(
            new ChunkPosition(O, -32, -32),
            new ChunkPosition(O, -31, -31),
            new ChunkPosition(O, -1, -1));
  }

  @Test
  void impossibleEntriesAreReportedAndLeftOut() {
    // offset 1 (inside the header), offset without sectors, sectors without offset, past the end.
    RegionScan scan = parse(4, 0, (1 << 8) | 1, 1, 3 << 8, 2, 1, 3, (3 << 8) | 5);
    assertThat(scan.chunks()).isEmpty();
    assertThat(scan.issues())
        .extracting(ValidationIssue::code)
        .containsOnly(WorldIssueCodes.REGION_ENTRY_INVALID)
        .hasSize(4);
  }

  @Test
  void overlappingEntriesAreReportedAndKept() {
    RegionScan scan = parse(6, 0, (2 << 8) | 2, 1, (3 << 8) | 1);
    assertThat(scan.chunks()).hasSize(2);
    assertThat(scan.issues())
        .extracting(ValidationIssue::code)
        .containsExactly(WorldIssueCodes.REGION_ENTRIES_OVERLAP);
  }

  @Test
  void aTruncatedHeaderIsAnErrorAndAnEmptyFileIsNot() throws Exception {
    SyntheticWorld world =
        SyntheticWorld.modern(temp.resolve("w"))
            .levelDat(4903)
            .regionFile(O, "region", "r.0.0.mca", new byte[100])
            .regionFile(O, "region", "r.1.0.mca", new byte[0]);
    List<RegionScan> scans =
        RegionInventory.scanAll(world.dir(), overworld(world), RegionKind.REGION);
    assertThat(scans).hasSize(2);
    assertThat(scans.get(0).issues())
        .extracting(ValidationIssue::code)
        .containsExactly(WorldIssueCodes.REGION_HEADER_TRUNCATED);
    assertThat(scans.get(1).issues()).isEmpty();
    assertThat(scans.get(1).chunks()).isEmpty();
  }

  @Test
  void anInterruptStopsTheScanBetweenFiles() throws Exception {
    SyntheticWorld world =
        SyntheticWorld.modern(temp.resolve("w"))
            .levelDat(4903)
            .chunks(
                O, "region", List.of(new ChunkPosition(O, 0, 0), new ChunkPosition(O, 64, 0)), 1);
    List<RegionScan> seen = new ArrayList<>();
    try {
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () ->
                  RegionInventory.scan(
                      world.dir(),
                      overworld(world),
                      RegionKind.REGION,
                      scan -> {
                        seen.add(scan);
                        Thread.currentThread().interrupt();
                      },
                      issue -> {}))
          .isInstanceOf(InterruptedIOException.class);
    } finally {
      Thread.interrupted();
    }
    assertThat(seen).hasSize(1);
  }
}
