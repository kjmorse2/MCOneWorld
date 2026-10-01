// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProvenance;
import io.github.kjmorse2.mconeworld.fixtures.index.ChunkDatasets;
import io.github.kjmorse2.mconeworld.fixtures.index.ChunkRecordText;
import io.github.kjmorse2.mconeworld.fixtures.index.SyntheticWorld;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.SplittableRandom;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

/** The benchmark datasets are deterministic, ordered, and shaped as documented. */
class SyntheticWorldTest {
  static final java.util.List<String> NAMES = ChunkDatasets.NAMES;

  private static String digest(SyntheticWorld world) throws Exception {
    MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
    for (DimensionKey dimension : world.dimensions()) {
      for (Iterator<ChunkProvenance> it = world.records(dimension); it.hasNext(); ) {
        sha256.update(ChunkRecordText.line(it.next()).getBytes(StandardCharsets.UTF_8));
      }
    }
    return HexFormat.of().formatHex(sha256.digest());
  }

  @ParameterizedTest
  @FieldSource("NAMES")
  void generationIsDeterministic(String name) throws Exception {
    assertThat(digest(ChunkDatasets.named(name, 0.1)))
        .isEqualTo(digest(ChunkDatasets.named(name, 0.1)));
  }

  @ParameterizedTest
  @FieldSource("NAMES")
  void recordsStreamInStrictChunkOrderAndAgreeWithPointLookups(String name) {
    SyntheticWorld world = ChunkDatasets.named(name, 0.1);
    for (DimensionKey dimension : world.dimensions()) {
      long previous = Long.MIN_VALUE;
      boolean first = true;
      for (Iterator<ChunkProvenance> it = world.records(dimension); it.hasNext(); ) {
        ChunkProvenance record = it.next();
        long key = ChunkOrder.key(record.chunk());
        assertThat(first || key > previous).as("order at %s", record.chunk()).isTrue();
        assertThat(world.recordAt(record.chunk())).isEqualTo(record);
        previous = key;
        first = false;
      }
    }
    SplittableRandom random = new SplittableRandom(7);
    for (int i = 0; i < 200; i++) {
      ChunkPosition chunk = world.sampleExisting(random);
      assertThat(world.recordAt(chunk)).isNotNull();
    }
  }

  @ParameterizedTest
  @FieldSource("NAMES")
  void datasetsHaveTheirDocumentedCharacter(String name) {
    SyntheticWorld world = ChunkDatasets.named(name, 0.1);
    long records = 0;
    long legacy = 0;
    long multiSeason = 0;
    boolean allNegative = true;
    for (DimensionKey dimension : world.dimensions()) {
      for (Iterator<ChunkProvenance> it = world.records(dimension); it.hasNext(); ) {
        ChunkProvenance r = it.next();
        records++;
        legacy += r.isLegacy() ? 1 : 0;
        multiSeason += r.activity().size() >= 2 ? 1 : 0;
        allNegative &= r.chunk().x() < 0 && r.chunk().z() < 0;
      }
    }
    assertThat(records).isPositive();
    switch (name) {
      case "negative-coords" -> {
        assertThat(allNegative).isTrue();
        assertThat(
                world.recordAt(
                    new ChunkPosition(
                        DimensionKey.OVERWORLD, Integer.MIN_VALUE, Integer.MIN_VALUE)))
            .isNotNull();
      }
      case "dense-square" -> assertThat(legacy).isEqualTo(records);
      case "revisited" -> assertThat(multiSeason).isGreaterThan(records / 5);
      default -> {}
    }
  }
}
