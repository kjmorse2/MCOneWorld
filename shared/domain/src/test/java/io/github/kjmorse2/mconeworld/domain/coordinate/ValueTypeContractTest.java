// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.coordinate;

import static io.github.kjmorse2.mconeworld.domain.DimensionKey.OVERWORLD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.OperationId;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Equality and immutability guarantees for every public value type introduced by Prompt 02. When a
 * type is added, add it to {@link #VALUE_TYPES}.
 */
class ValueTypeContractTest {

  static final List<Class<?>> VALUE_TYPES =
      List.of(
          SeasonId.class,
          OperationId.class,
          DimensionKey.class,
          BlockPosition.class,
          ChunkPosition.class,
          RegionPosition.class,
          DimensionOrigin.class,
          SeasonLocalPosition.class,
          OriginAlignment.class,
          WorldBounds.class,
          ChunkRectangle.class,
          io.github.kjmorse2.mconeworld.domain.BackupId.class,
          io.github.kjmorse2.mconeworld.domain.PortalMarkerId.class,
          io.github.kjmorse2.mconeworld.domain.ProtectedRegionId.class,
          io.github.kjmorse2.mconeworld.domain.PlayerArchiveId.class,
          io.github.kjmorse2.mconeworld.domain.PlayerId.class,
          io.github.kjmorse2.mconeworld.domain.DisplayName.class,
          io.github.kjmorse2.mconeworld.domain.Checksum.class,
          io.github.kjmorse2.mconeworld.domain.validation.IssueCode.class,
          io.github.kjmorse2.mconeworld.domain.validation.IssueLocation.class,
          io.github.kjmorse2.mconeworld.domain.provenance.ProvenanceEvidence.class,
          io.github.kjmorse2.mconeworld.domain.provenance.SeasonActivity.class,
          io.github.kjmorse2.mconeworld.domain.provenance.FirstObservation.InSeason.class,
          io.github.kjmorse2.mconeworld.domain.provenance.FirstObservation.Legacy.class,
          io.github.kjmorse2.mconeworld.domain.provenance.ChunkEnvelope.Empty.class,
          io.github.kjmorse2.mconeworld.domain.provenance.ChunkEnvelope.Bounded.class,
          io.github.kjmorse2.mconeworld.domain.provenance.ProtectedRegion.class,
          io.github.kjmorse2.mconeworld.domain.archive.PortalMarker.class,
          io.github.kjmorse2.mconeworld.domain.archive.BackupRecord.class);

  /**
   * Component types known to be deeply immutable. Records with collection components (SeasonRecord,
   * ChunkProvenance, PlayerArchiveRecord, ArchiveState, ValidationResult) are covered by dedicated
   * aliasing tests instead.
   */
  static final Set<Class<?>> IMMUTABLE_COMPONENTS =
      Set.of(
          String.class,
          UUID.class,
          java.time.Instant.class,
          DimensionKey.class,
          DimensionOrigin.class,
          BlockPosition.class,
          ChunkRectangle.class,
          io.github.kjmorse2.mconeworld.domain.SeasonId.class,
          io.github.kjmorse2.mconeworld.domain.OperationId.class,
          io.github.kjmorse2.mconeworld.domain.BackupId.class,
          io.github.kjmorse2.mconeworld.domain.PortalMarkerId.class,
          io.github.kjmorse2.mconeworld.domain.ProtectedRegionId.class,
          io.github.kjmorse2.mconeworld.domain.DisplayName.class,
          io.github.kjmorse2.mconeworld.domain.Checksum.class,
          io.github.kjmorse2.mconeworld.domain.Checksum.Algorithm.class,
          io.github.kjmorse2.mconeworld.domain.provenance.EvidenceSource.class,
          io.github.kjmorse2.mconeworld.domain.provenance.Confidence.class,
          io.github.kjmorse2.mconeworld.domain.provenance.ProvenanceEvidence.class);

  static List<Class<?>> valueTypes() {
    return VALUE_TYPES;
  }

  @ParameterizedTest
  @MethodSource("valueTypes")
  void isARecordWithOnlyImmutableComponents(Class<?> type) {
    assertThat(type.isRecord()).as("%s is a record", type).isTrue();
    assertThat(Modifier.isFinal(type.getModifiers())).isTrue();
    for (RecordComponent component : type.getRecordComponents()) {
      Class<?> componentType = component.getType();
      assertThat(componentType.isPrimitive() || IMMUTABLE_COMPONENTS.contains(componentType))
          .as(
              "%s.%s has immutable type %s",
              type.getSimpleName(), component.getName(), componentType)
          .isTrue();
    }
  }

  @Test
  void equalValuesAreEqualAndHashAlike() {
    assertThat(new BlockPosition(OVERWORLD, -1, 2, -3))
        .isEqualTo(new BlockPosition(DimensionKey.parse("minecraft:overworld"), -1, 2, -3))
        .hasSameHashCodeAs(new BlockPosition(OVERWORLD, -1, 2, -3));
    assertThat(new BlockPosition(OVERWORLD, 0, 0, 0))
        .isNotEqualTo(new BlockPosition(DimensionKey.THE_NETHER, 0, 0, 0));
    assertThat(new ChunkPosition(OVERWORLD, 1, 2)).isEqualTo(new ChunkPosition(OVERWORLD, 1, 2));
  }

  @Test
  void naturalOrderIsDimensionThenXThenZ() {
    ChunkPosition a = new ChunkPosition(DimensionKey.parse("a:a"), 9, 9);
    ChunkPosition b = new ChunkPosition(OVERWORLD, -5, 7);
    ChunkPosition c = new ChunkPosition(OVERWORLD, -5, 8);
    ChunkPosition d = new ChunkPosition(OVERWORLD, 3, -100);
    assertThat(List.of(d, c, a, b).stream().sorted()).containsExactly(a, b, c, d);
  }

  static List<Executable> nullConstructions() {
    DimensionOrigin origin = new DimensionOrigin(OVERWORLD, 0, 0, 0);
    return List.of(
        () -> new SeasonId(nullOf()),
        () -> new OperationId(nullOf()),
        () -> new DimensionKey(nullOf()),
        () -> new BlockPosition(nullOf(), 0, 0, 0),
        () -> new ChunkPosition(nullOf(), 0, 0),
        () -> new RegionPosition(nullOf(), 0, 0),
        () -> new DimensionOrigin(nullOf(), 0, 0, 0),
        () -> new SeasonLocalPosition(nullOf(), 0, 0, 0),
        () -> new OriginAlignment(nullOf(), 16),
        () -> origin.toLocal(nullOf()));
  }

  /**
   * Callers without NullAway (for example a platform plugin) can still pass null, so every
   * constructor rejects it at runtime rather than creating a value with a missing dimension or
   * identity.
   */
  @ParameterizedTest
  @MethodSource("nullConstructions")
  void nullIsRejectedAtRuntime(Executable construction) {
    assertThatThrownBy(construction::execute).isInstanceOf(NullPointerException.class);
  }

  @SuppressWarnings({"NullAway", "TypeParameterUnusedInFormals"})
  private static <T> T nullOf() {
    return null;
  }
}
