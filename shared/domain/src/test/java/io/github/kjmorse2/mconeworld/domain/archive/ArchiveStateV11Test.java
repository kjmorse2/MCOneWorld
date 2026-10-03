// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.archive;

import static io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.backupId;
import static io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.instant;
import static io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.operationId;
import static io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.season;
import static io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.seasonId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.DomainErrorCode;
import io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException;
import io.github.kjmorse2.mconeworld.domain.season.SeasonStatus;
import io.github.kjmorse2.mconeworld.domain.validation.Severity;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationException;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationIssue;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The manifest 1.1 records: world binding, pending activation (ADR 0011), archived dimensions. */
class ArchiveStateV11Test {

  private static ArchiveState twoSeasons() {
    return ArchiveState.empty()
        .appendSeason(season(1).build())
        .activate(seasonId(1))
        .appendSeason(season(2).build());
  }

  private static InvalidDomainValueException transitionRefused(Runnable action) {
    try {
      action.run();
    } catch (InvalidDomainValueException e) {
      assertThat(e.code()).isEqualTo(DomainErrorCode.SEASON_TRANSITION_ILLEGAL);
      return e;
    }
    throw new AssertionError("expected the transition to be refused");
  }

  @Test
  void anOfflineActivationIsPendingUntilMarkedAppliedAndBlocksOtherTransitions() {
    ArchiveState state = twoSeasons().activateForApplication(seasonId(2));
    assertThat(state.activeSeasonId()).isEqualTo(seasonId(2));
    assertThat(state.season(seasonId(1)).orElseThrow().status()).isEqualTo(SeasonStatus.ARCHIVED);
    assertThat(state.pendingActivation()).isEqualTo(seasonId(2));
    assertThat(state.isActivationPending()).isTrue();
    assertThat(state.validate().issues()).isEmpty();

    ArchiveState withThird = state.appendSeason(season(3).build());
    transitionRefused(() -> withThird.activate(seasonId(3)));
    transitionRefused(() -> withThird.activateForApplication(seasonId(3)));
    transitionRefused(withThird::archiveActiveSeason);
    transitionRefused(() -> withThird.markActivationApplied(seasonId(1)));

    ArchiveState applied = withThird.markActivationApplied(seasonId(2));
    assertThat(applied.pendingActivation()).isNull();
    assertThat(applied.isActivationPending()).isFalse();
    transitionRefused(() -> applied.markActivationApplied(seasonId(2)));
    assertThat(applied.activate(seasonId(3)).activeSeasonId()).isEqualTo(seasonId(3));
  }

  @Test
  void aStalePendingActivationIsAWarningAndIgnored() {
    // As left by a writer that did not understand the marker and activated another season.
    ArchiveState base = twoSeasons();
    ArchiveState stale =
        new ArchiveState(
            base.seasons(),
            base.activeSeasonId(),
            base.protectedRegions(),
            base.portalMarkers(),
            base.backups(),
            base.playerArchives(),
            null,
            seasonId(2),
            List.of());
    assertThat(stale.validate().isValid()).isTrue();
    assertThat(stale.validate().issues())
        .extracting(ValidationIssue::code, ValidationIssue::severity)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                ArchiveIssueCodes.PENDING_ACTIVATION_STALE, Severity.WARNING));
    assertThat(stale.isActivationPending()).isFalse();
    assertThat(stale.activate(seasonId(2)).activeSeasonId()).isEqualTo(seasonId(2));
  }

  @Test
  void aPendingActivationOfAnUnknownSeasonIsAnError() {
    ArchiveState base = twoSeasons();
    ArchiveState broken =
        new ArchiveState(
            base.seasons(),
            base.activeSeasonId(),
            base.protectedRegions(),
            base.portalMarkers(),
            base.backups(),
            base.playerArchives(),
            null,
            seasonId(9),
            List.of());
    assertThat(broken.validate().has(ArchiveIssueCodes.UNKNOWN_SEASON_REFERENCE)).isTrue();
  }

  @Test
  void archivedDimensionsMustReferenceARecordedBackup() {
    ArchivedDimension end =
        new ArchivedDimension(
            DimensionKey.THE_END, seasonId(1), operationId(1), backupId(1), instant(5));
    assertThatThrownBy(() -> twoSeasons().withArchivedDimension(end))
        .isInstanceOf(ValidationException.class);
    ArchiveState withBackup =
        twoSeasons()
            .withBackup(
                new BackupRecord(
                    backupId(1),
                    instant(4),
                    Checksum.parse("sha256:" + "ab".repeat(32)),
                    10,
                    operationId(1),
                    seasonId(1)));
    ArchiveState archived = withBackup.withArchivedDimension(end).withArchivedDimension(end);
    assertThat(archived.archivedDimensions()).containsExactly(end, end);
    assertThat(archived.validate().issues()).isEmpty();
  }

  @Test
  void transitionsCarryTheNewRecordsAlong() {
    WorldBinding binding =
        new WorldBinding(WorldBinding.seedChecksum(42), "World", 4903, instant(1));
    ArchiveState bound = twoSeasons().withWorldBinding(binding).activateForApplication(seasonId(2));
    assertThat(bound.worldBinding()).isEqualTo(binding);
    assertThat(
            bound
                .renameSeason(
                    seasonId(1), io.github.kjmorse2.mconeworld.domain.DisplayName.of("Old"))
                .pendingActivation())
        .isEqualTo(seasonId(2));
    assertThat(bound.appendSeason(season(3).build()).worldBinding()).isEqualTo(binding);
  }

  @Test
  void theWorldBindingIdentifiesTheSeedAndBoundsItsText() {
    WorldBinding binding = new WorldBinding(WorldBinding.seedChecksum(-1), null, 0, instant(0));
    assertThat(binding.matchesSeed(-1)).isTrue();
    assertThat(binding.matchesSeed(1)).isFalse();
    // SHA-256 of the 8 bytes 0x00..00: a fixed vector, so the identity never drifts.
    assertThat(WorldBinding.seedChecksum(0).toString())
        .isEqualTo("sha256:af5570f5a1810b7af78caf4bc70a660f0df51e42baf91d4de5b2328de0e83dfc");
    assertThatThrownBy(
            () -> new WorldBinding(WorldBinding.seedChecksum(1), " padded", 1, instant(0)))
        .isInstanceOf(InvalidDomainValueException.class);
    assertThatThrownBy(() -> new WorldBinding(WorldBinding.seedChecksum(1), null, -1, instant(0)))
        .isInstanceOf(InvalidDomainValueException.class);
    assertThat(WorldBinding.storableLevelName("Real Realm (fr fr)"))
        .isEqualTo("Real Realm (fr fr)");
    assertThat(WorldBinding.storableLevelName("bad\u0007")).isNull();
    assertThat(WorldBinding.storableLevelName("x".repeat(300))).isNull();
  }
}
