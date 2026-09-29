// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.provenance;

import static io.github.kjmorse2.mconeworld.domain.DimensionKey.OVERWORLD;
import static io.github.kjmorse2.mconeworld.domain.DimensionKey.THE_END;
import static io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.instant;
import static io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.protectedRegionId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kjmorse2.mconeworld.domain.DisplayName;
import io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkRectangle;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ChunkEnvelopeTest {

  @Test
  void emptyEnvelopeHasNoBoundsAndIncludesNothing() {
    ChunkEnvelope empty = ChunkEnvelope.empty(OVERWORLD);
    assertThat(empty.bounds()).isEmpty();
    assertThat(empty.boundsMayInclude(new ChunkPosition(OVERWORLD, 0, 0))).isFalse();
    assertThat(ChunkEnvelope.of(OVERWORLD, List.<ChunkPosition>of().iterator())).isEqualTo(empty);
  }

  @Test
  void envelopeIsTheBoundingBoxOfStreamedChunks() {
    List<ChunkPosition> chunks =
        List.of(
            new ChunkPosition(OVERWORLD, -5, 2),
            new ChunkPosition(OVERWORLD, 3, -9),
            new ChunkPosition(OVERWORLD, 0, 0));
    ChunkEnvelope envelope = ChunkEnvelope.of(OVERWORLD, chunks.iterator());
    assertThat(envelope.bounds()).contains(new ChunkRectangle(OVERWORLD, -5, -9, 3, 2));
  }

  @Test
  void boundsInclusionIsNotMembership() {
    ChunkEnvelope envelope =
        ChunkEnvelope.of(
            OVERWORLD,
            List.of(new ChunkPosition(OVERWORLD, 0, 0), new ChunkPosition(OVERWORLD, 10, 10))
                .iterator());
    ChunkPosition neverSummarized = new ChunkPosition(OVERWORLD, 5, 5);
    // Inside the rectangle, but was never one of the summarized chunks.
    assertThat(envelope.boundsMayInclude(neverSummarized)).isTrue();
    // The API has no method named "contains" that could be mistaken for membership.
    assertThat(Arrays.stream(ChunkEnvelope.class.getMethods()).map(Method::getName))
        .doesNotContain("contains", "containsChunk", "members");
  }

  @Test
  void extremeEnvelopeSpansTheFullIntRangeWithoutOverflow() {
    ChunkEnvelope extreme =
        ChunkEnvelope.empty(OVERWORLD)
            .including(new ChunkPosition(OVERWORLD, Integer.MIN_VALUE, Integer.MIN_VALUE))
            .including(new ChunkPosition(OVERWORLD, Integer.MAX_VALUE, Integer.MAX_VALUE));
    ChunkRectangle bounds = extreme.bounds().orElseThrow();
    assertThat(bounds.widthChunks()).isEqualTo(1L << 32);
    assertThat(bounds.heightChunks()).isEqualTo(1L << 32);
  }

  @Test
  void unionHandlesEmptySidesAndRejectsOtherDimensions() {
    ChunkEnvelope a = ChunkEnvelope.empty(OVERWORLD).including(new ChunkPosition(OVERWORLD, 1, 1));
    ChunkEnvelope b = ChunkEnvelope.empty(OVERWORLD).including(new ChunkPosition(OVERWORLD, -1, 4));
    assertThat(a.union(ChunkEnvelope.empty(OVERWORLD))).isEqualTo(a);
    assertThat(ChunkEnvelope.empty(OVERWORLD).union(a)).isEqualTo(a);
    assertThat(a.union(b).bounds()).contains(new ChunkRectangle(OVERWORLD, -1, 1, 1, 4));
    assertThatThrownBy(() -> a.union(ChunkEnvelope.empty(THE_END)))
        .isInstanceOf(InvalidDomainValueException.class);
    assertThatThrownBy(() -> a.including(new ChunkPosition(THE_END, 0, 0)))
        .isInstanceOf(InvalidDomainValueException.class);
  }

  @Test
  void streamingIsConstantMemory() {
    // One million chunks through an iterator; no collection of chunks is ever built.
    var iterator =
        IntStream.range(0, 1_000_000)
            .mapToObj(i -> new ChunkPosition(OVERWORLD, i % 1000, i / 1000))
            .iterator();
    assertThat(ChunkEnvelope.of(OVERWORLD, iterator).bounds())
        .contains(new ChunkRectangle(OVERWORLD, 0, 0, 999, 999));
  }

  @Test
  void protectedRegionRectangleIsAuthoritative() {
    ProtectedRegion region =
        new ProtectedRegion(
            protectedRegionId(1),
            DisplayName.of("Spawn town"),
            new ChunkRectangle(OVERWORLD, -2, -2, 2, 2),
            instant(0));
    assertThat(region.protects(new ChunkPosition(OVERWORLD, 2, -2))).isTrue();
    assertThat(region.protects(new ChunkPosition(OVERWORLD, 3, 0))).isFalse();
    assertThat(region.protects(new ChunkPosition(THE_END, 0, 0))).isFalse();
  }

  @Test
  void rectangleRejectsInvertedBoundsAndSpansCornersInAnyOrder() {
    assertThatThrownBy(() -> new ChunkRectangle(OVERWORLD, 1, 0, 0, 0))
        .isInstanceOf(InvalidDomainValueException.class);
    assertThat(
            ChunkRectangle.spanning(
                new ChunkPosition(OVERWORLD, 5, -5), new ChunkPosition(OVERWORLD, -5, 5)))
        .isEqualTo(new ChunkRectangle(OVERWORLD, -5, -5, 5, 5));
    ChunkRectangle r = new ChunkRectangle(OVERWORLD, 0, 0, 4, 4);
    assertThat(r.intersects(new ChunkRectangle(OVERWORLD, 4, 4, 9, 9))).isTrue();
    assertThat(r.intersects(new ChunkRectangle(OVERWORLD, 5, 0, 9, 9))).isFalse();
    assertThat(r.intersects(new ChunkRectangle(THE_END, 0, 0, 4, 4))).isFalse();
  }
}
