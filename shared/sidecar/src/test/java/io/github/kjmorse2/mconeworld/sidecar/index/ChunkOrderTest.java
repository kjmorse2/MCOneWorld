// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link ChunkOrder} against an independently written oracle: region X, region Z, then local Z and
 * local X, all by floor division.
 */
class ChunkOrderTest {
  private static final int[] EDGES = {
    Integer.MIN_VALUE,
    Integer.MIN_VALUE + 31,
    Integer.MIN_VALUE + 32,
    -33,
    -32,
    -31,
    -17,
    -16,
    -1,
    0,
    1,
    15,
    16,
    31,
    32,
    33,
    Integer.MAX_VALUE - 32,
    Integer.MAX_VALUE - 31,
    Integer.MAX_VALUE
  };

  /** The oracle order, written from the definition rather than from the bit layout. */
  private static final Comparator<int[]> ORACLE =
      Comparator.<int[]>comparingInt(p -> Math.floorDiv(p[0], 32))
          .thenComparingInt(p -> Math.floorDiv(p[1], 32))
          .thenComparingInt(p -> Math.floorMod(p[1], 32))
          .thenComparingInt(p -> Math.floorMod(p[0], 32));

  @ParameterizedTest
  @CsvSource({
    "0, 0, 0, 0, 0",
    "-1, -1, -1, -1, 1023",
    "31, 0, 0, 0, 31",
    "32, 0, 1, 0, 0",
    "0, 32, 0, 1, 0",
    "-33, -32, -2, -1, 31",
    "-2147483648, -2147483648, -67108864, -67108864, 0",
    "2147483647, 2147483647, 67108863, 67108863, 1023"
  })
  void keyEncodesRegionAndLocalIndex(int x, int z, int regionX, int regionZ, int local) {
    long key = ChunkOrder.key(x, z);
    assertThat(ChunkOrder.regionX(key)).isEqualTo(regionX);
    assertThat(ChunkOrder.regionZ(key)).isEqualTo(regionZ);
    assertThat(ChunkOrder.localIndex(key)).isEqualTo(local);
    assertThat(ChunkOrder.x(key)).isEqualTo(x);
    assertThat(ChunkOrder.z(key)).isEqualTo(z);
    assertThat(key)
        .isBetween(
            ChunkOrder.regionStart(regionX, regionZ), ChunkOrder.regionEnd(regionX, regionZ));
  }

  @Test
  void signedKeyOrderMatchesTheOracleOnEveryEdgePair() {
    List<int[]> points = new ArrayList<>();
    for (int x : EDGES) {
      for (int z : EDGES) {
        points.add(new int[] {x, z});
      }
    }
    for (int[] a : points) {
      for (int[] b : points) {
        assertThat(
                Integer.signum(
                    Long.compare(ChunkOrder.key(a[0], a[1]), ChunkOrder.key(b[0], b[1]))))
            .as("(%d,%d) vs (%d,%d)", a[0], a[1], b[0], b[1])
            .isEqualTo(Integer.signum(ORACLE.compare(a, b)));
      }
    }
  }

  @Test
  void comparatorOrdersByDimensionFirst() {
    ChunkPosition overworld = new ChunkPosition(DimensionKey.OVERWORLD, Integer.MIN_VALUE, 0);
    ChunkPosition nether = new ChunkPosition(DimensionKey.THE_NETHER, Integer.MAX_VALUE, 0);
    assertThat(Integer.signum(ChunkOrder.COMPARATOR.compare(overworld, nether)))
        .isEqualTo(Integer.signum(DimensionKey.OVERWORLD.compareTo(DimensionKey.THE_NETHER)));
  }

  @Property
  void keyRoundTripsEveryChunk(@ForAll int x, @ForAll int z) {
    long key = ChunkOrder.key(x, z);
    assertThat(ChunkOrder.position(DimensionKey.OVERWORLD, key))
        .isEqualTo(new ChunkPosition(DimensionKey.OVERWORLD, x, z));
  }

  @Property
  void keyOrderMatchesTheOracle(@ForAll int x1, @ForAll int z1, @ForAll int x2, @ForAll int z2) {
    assertThat(Integer.signum(Long.compare(ChunkOrder.key(x1, z1), ChunkOrder.key(x2, z2))))
        .isEqualTo(Integer.signum(ORACLE.compare(new int[] {x1, z1}, new int[] {x2, z2})));
  }
}
