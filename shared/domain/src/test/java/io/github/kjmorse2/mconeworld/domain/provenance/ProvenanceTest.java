// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.provenance;

import static io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.instant;
import static io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.seasonId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.DomainErrorCode;
import io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProvenanceTest {
  private static final ChunkPosition CHUNK = new ChunkPosition(DimensionKey.OVERWORLD, -3, 7);
  private static final ProvenanceEvidence RUNTIME =
      new ProvenanceEvidence(EvidenceSource.RUNTIME_OBSERVATION, Confidence.HIGH);
  private static final ProvenanceEvidence IMPORT =
      new ProvenanceEvidence(EvidenceSource.WORLD_IMPORT, Confidence.LOW);

  @Test
  void legacyChunkHasNoKnownSeasonButCanHaveActivityInManySeasons() {
    ChunkProvenance legacy =
        ChunkProvenance.firstObserved(CHUNK, new FirstObservation.Legacy(instant(0), IMPORT))
            .withActivity(new SeasonActivity(seasonId(1), instant(100), RUNTIME))
            .withActivity(new SeasonActivity(seasonId(2), instant(200), RUNTIME));

    assertThat(legacy.isLegacy()).isTrue();
    assertThat(legacy.firstObservation().knownSeason()).isNull();
    assertThat(legacy.hasActivityIn(seasonId(1))).isTrue();
    assertThat(legacy.hasActivityIn(seasonId(2))).isTrue();
    assertThat(legacy.hasActivityIn(seasonId(3))).isFalse();
    assertThat(legacy.lastActivity())
        .isNotNull()
        .extracting(SeasonActivity::season)
        .isEqualTo(seasonId(2));
  }

  @Test
  void firstSeasonAndLaterActivityAreSeparateConcepts() {
    ChunkProvenance p =
        ChunkProvenance.firstObserved(
                CHUNK, new FirstObservation.InSeason(seasonId(1), instant(10), RUNTIME))
            .withActivity(new SeasonActivity(seasonId(3), instant(900), RUNTIME));
    assertThat(p.isLegacy()).isFalse();
    assertThat(p.firstObservation().knownSeason()).isEqualTo(seasonId(1));
    assertThat(p.lastActivity())
        .isNotNull()
        .extracting(SeasonActivity::season)
        .isEqualTo(seasonId(3));
    assertThat(p.hasActivityIn(seasonId(1))).as("first observation is not activity").isFalse();
  }

  @Test
  void laterObservationWinsAndOlderNeverOverwrites() {
    ChunkProvenance p =
        ChunkProvenance.firstObserved(CHUNK, new FirstObservation.Legacy(instant(0), IMPORT))
            .withActivity(new SeasonActivity(seasonId(1), instant(500), RUNTIME));
    ChunkProvenance older = p.withActivity(new SeasonActivity(seasonId(1), instant(100), IMPORT));
    ChunkProvenance newer = p.withActivity(new SeasonActivity(seasonId(1), instant(600), IMPORT));
    assertThat(older.activity())
        .singleElement()
        .extracting(SeasonActivity::lastObservedAt)
        .isEqualTo(instant(500));
    assertThat(newer.activity())
        .singleElement()
        .extracting(SeasonActivity::lastObservedAt)
        .isEqualTo(instant(600));
  }

  @Test
  void duplicateSeasonActivityIsRejected() {
    List<SeasonActivity> dup =
        List.of(
            new SeasonActivity(seasonId(1), instant(1), RUNTIME),
            new SeasonActivity(seasonId(1), instant(2), RUNTIME));
    assertThatThrownBy(
            () ->
                new ChunkProvenance(
                    CHUNK,
                    new FirstObservation.Legacy(instant(0), IMPORT),
                    dup,
                    ChunkProtection.NONE))
        .isInstanceOfSatisfying(
            InvalidDomainValueException.class,
            e -> assertThat(e.code()).isEqualTo(DomainErrorCode.DUPLICATE_ID));
  }

  @Test
  void activityIsSortedDeterministicallyAndUnaliased() {
    List<SeasonActivity> source = new ArrayList<>();
    source.add(new SeasonActivity(seasonId(3), instant(1), RUNTIME));
    source.add(new SeasonActivity(seasonId(1), instant(2), RUNTIME));
    ChunkProvenance p =
        new ChunkProvenance(
            CHUNK, new FirstObservation.Legacy(instant(0), IMPORT), source, ChunkProtection.NONE);
    source.clear();
    assertThat(p.activity())
        .extracting(SeasonActivity::season)
        .containsExactly(seasonId(1), seasonId(3));
    assertThatThrownBy(() -> p.activity().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void lastActivityTieBreaksBySeasonId() {
    ChunkProvenance p =
        ChunkProvenance.firstObserved(CHUNK, new FirstObservation.Legacy(instant(0), IMPORT))
            .withActivity(new SeasonActivity(seasonId(2), instant(5), RUNTIME))
            .withActivity(new SeasonActivity(seasonId(1), instant(5), RUNTIME));
    assertThat(p.lastActivity())
        .isNotNull()
        .extracting(SeasonActivity::season)
        .isEqualTo(seasonId(2));
  }

  @Test
  void protectionIsIndependentOfProvenance() {
    ChunkProvenance legacy =
        ChunkProvenance.firstObserved(CHUNK, new FirstObservation.Legacy(instant(0), IMPORT));
    ChunkProvenance protectedLegacy = legacy.withProtection(ChunkProtection.PROTECTED);
    assertThat(protectedLegacy.protection()).isEqualTo(ChunkProtection.PROTECTED);
    assertThat(protectedLegacy.firstObservation()).isEqualTo(legacy.firstObservation());
    assertThat(protectedLegacy.activity()).isEqualTo(legacy.activity());
    // Adding activity does not change protection, and vice versa.
    assertThat(
            protectedLegacy
                .withActivity(new SeasonActivity(seasonId(1), instant(1), RUNTIME))
                .protection())
        .isEqualTo(ChunkProtection.PROTECTED);
  }
}
