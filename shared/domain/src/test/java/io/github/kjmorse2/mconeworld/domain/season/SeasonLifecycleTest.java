// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.season;

import static io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.season;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.DisplayName;
import io.github.kjmorse2.mconeworld.domain.DomainErrorCode;
import io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException;
import io.github.kjmorse2.mconeworld.domain.coordinate.DimensionOrigin;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SeasonLifecycleTest {

  private static final Map<SeasonStatus, Set<SeasonStatus>> LEGAL =
      Map.of(
          SeasonStatus.PLANNED, EnumSet.of(SeasonStatus.ACTIVE, SeasonStatus.CANCELLED),
          SeasonStatus.ACTIVE, EnumSet.of(SeasonStatus.ARCHIVED),
          SeasonStatus.ARCHIVED, EnumSet.noneOf(SeasonStatus.class),
          SeasonStatus.CANCELLED, EnumSet.noneOf(SeasonStatus.class));

  /** Exhaustive 4×4 matrix: every legal transition succeeds, and every other one fails. */
  @ParameterizedTest
  @EnumSource(SeasonStatus.class)
  void transitionMatrix(SeasonStatus from) {
    SeasonRecord record = season(1).status(from).build();
    for (SeasonStatus to : SeasonStatus.values()) {
      boolean legal = LEGAL.get(from).contains(to);
      assertThat(from.canTransitionTo(to)).as("%s -> %s", from, to).isEqualTo(legal);
      if (legal) {
        SeasonRecord moved = record.transitionTo(to);
        assertThat(moved.status()).isEqualTo(to);
        assertThat(moved.id()).isEqualTo(record.id());
      } else {
        assertThatThrownBy(() -> record.transitionTo(to))
            .isInstanceOfSatisfying(
                InvalidDomainValueException.class,
                e -> assertThat(e.code()).isEqualTo(DomainErrorCode.SEASON_TRANSITION_ILLEGAL));
      }
    }
    assertThat(from.isTerminal()).isEqualTo(LEGAL.get(from).isEmpty());
  }

  @Test
  void renamePreservesIdentityAndEverythingElse() {
    SeasonRecord original = season(7).origin(DimensionKey.OVERWORLD, 1_048_576, 64, 0).build();
    SeasonRecord renamed = original.renamed(DisplayName.of("Renamed"));
    assertThat(renamed.id()).isEqualTo(original.id());
    assertThat(renamed.name().value()).isEqualTo("Renamed");
    assertThat(renamed.origins()).isEqualTo(original.origins());
    assertThat(renamed.createdAt()).isEqualTo(original.createdAt());
    assertThat(renamed).isNotEqualTo(original);
  }

  @Test
  void anyDimensionMayOmitAnOrigin() {
    SeasonRecord noOrigins = season(1).build();
    assertThat(noOrigins.origins()).isEmpty();
    assertThat(noOrigins.origin(DimensionKey.OVERWORLD)).isEmpty();

    SeasonRecord overworldOnly =
        noOrigins.withOrigin(new DimensionOrigin(DimensionKey.OVERWORLD, 0, 64, 0));
    assertThat(overworldOnly.origin(DimensionKey.OVERWORLD)).isPresent();
    assertThat(overworldOnly.origin(DimensionKey.THE_END))
        .as("the End never inherits the Overworld origin")
        .isEmpty();
    assertThat(overworldOnly.withoutOrigin(DimensionKey.OVERWORLD).origins()).isEmpty();
  }

  @Test
  void originMustBeStoredUnderItsOwnDimension() {
    Map<DimensionKey, DimensionOrigin> wrong = new HashMap<>();
    wrong.put(DimensionKey.THE_END, new DimensionOrigin(DimensionKey.OVERWORLD, 0, 0, 0));
    SeasonRecord base = season(1).build();
    assertThatThrownBy(
            () ->
                new SeasonRecord(
                    base.id(),
                    base.name(),
                    base.createdAt(),
                    base.status(),
                    new TreeMap<>(wrong),
                    base.spawn()))
        .isInstanceOfSatisfying(
            InvalidDomainValueException.class,
            e -> assertThat(e.code()).isEqualTo(DomainErrorCode.DIMENSION_MISMATCH));
  }

  @Test
  void originsCannotBeMutatedThroughAliases() {
    TreeMap<DimensionKey, DimensionOrigin> source = new TreeMap<>();
    source.put(DimensionKey.OVERWORLD, new DimensionOrigin(DimensionKey.OVERWORLD, 0, 0, 0));
    SeasonRecord base = season(1).build();
    SeasonRecord record =
        new SeasonRecord(
            base.id(), base.name(), base.createdAt(), base.status(), source, base.spawn());

    source.put(DimensionKey.THE_END, new DimensionOrigin(DimensionKey.THE_END, 0, 0, 0));
    assertThat(record.origins()).containsOnlyKeys(DimensionKey.OVERWORLD);
    assertThatThrownBy(() -> record.origins().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () -> record.origins().put(DimensionKey.THE_END, source.get(DimensionKey.THE_END)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void originsIterateInDimensionKeyOrder() {
    SeasonRecord record =
        season(1)
            .origin(DimensionKey.THE_END, 0, 0, 0)
            .origin(DimensionKey.parse("a:first"), 0, 0, 0)
            .origin(DimensionKey.OVERWORLD, 0, 0, 0)
            .build();
    assertThat(record.origins().keySet())
        .containsExactly(
            DimensionKey.parse("a:first"), DimensionKey.OVERWORLD, DimensionKey.THE_END);
  }
}
