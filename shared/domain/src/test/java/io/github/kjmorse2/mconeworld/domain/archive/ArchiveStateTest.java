// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.archive;

import static io.github.kjmorse2.mconeworld.domain.archive.ArchiveIssueCodes.ACTIVE_SEASON_MULTIPLE;
import static io.github.kjmorse2.mconeworld.domain.archive.ArchiveIssueCodes.ACTIVE_SEASON_NOT_ACTIVE;
import static io.github.kjmorse2.mconeworld.domain.archive.ArchiveIssueCodes.ACTIVE_SEASON_NOT_FOUND;
import static io.github.kjmorse2.mconeworld.domain.archive.ArchiveIssueCodes.ACTIVE_SEASON_UNREFERENCED;
import static io.github.kjmorse2.mconeworld.domain.archive.ArchiveIssueCodes.BACKUP_DUPLICATE_ID;
import static io.github.kjmorse2.mconeworld.domain.archive.ArchiveIssueCodes.ORIGIN_MISALIGNED;
import static io.github.kjmorse2.mconeworld.domain.archive.ArchiveIssueCodes.ORIGIN_NETHER_UNPAIRED;
import static io.github.kjmorse2.mconeworld.domain.archive.ArchiveIssueCodes.PLAYER_ARCHIVE_DUPLICATE_ID;
import static io.github.kjmorse2.mconeworld.domain.archive.ArchiveIssueCodes.PORTAL_MARKER_DUPLICATE_ID;
import static io.github.kjmorse2.mconeworld.domain.archive.ArchiveIssueCodes.PROTECTED_REGION_DUPLICATE_ID;
import static io.github.kjmorse2.mconeworld.domain.archive.ArchiveIssueCodes.SEASON_DUPLICATE_ID;
import static io.github.kjmorse2.mconeworld.domain.archive.ArchiveIssueCodes.UNKNOWN_SEASON_REFERENCE;
import static io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.backupId;
import static io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.instant;
import static io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.overworld;
import static io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.playerArchiveId;
import static io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.playerId;
import static io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.portalMarkerId;
import static io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.season;
import static io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.seasonId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.DisplayName;
import io.github.kjmorse2.mconeworld.domain.DomainErrorCode;
import io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.season.SeasonRecord;
import io.github.kjmorse2.mconeworld.domain.season.SeasonStatus;
import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import io.github.kjmorse2.mconeworld.domain.validation.Severity;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationException;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationIssue;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ArchiveStateTest {
  private static final Checksum SUM =
      Checksum.parse("sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");

  private static ArchiveState state(@Nullable SeasonId active, SeasonRecord... seasons) {
    return new ArchiveState(List.of(seasons), active, List.of(), List.of(), List.of(), List.of());
  }

  private static List<String> codes(ValidationResult result) {
    return result.issues().stream().map(i -> i.code().value()).toList();
  }

  @Test
  void emptyStateIsValidAndHasNoActiveSeason() {
    assertThat(ArchiveState.empty().validate().issues()).isEmpty();
    assertThat(ArchiveState.empty().activeSeason()).isEmpty();
  }

  @Test
  void activeSeasonIsSelectedExplicitlyNotByOrderOrTime() {
    // Season 2 is newer and last in the list, but season 1 is the explicitly selected active one.
    ArchiveState s =
        state(
            seasonId(1),
            season(1).status(SeasonStatus.ACTIVE).createdAt(instant(1)).build(),
            season(2).createdAt(instant(999)).build());
    assertThat(s.validate().isValid()).isTrue();
    assertThat(s.activeSeason()).map(SeasonRecord::id).contains(seasonId(1));
  }

  @Test
  void missingActiveReferenceIsReported() {
    ArchiveState s = state(null, season(1).status(SeasonStatus.ACTIVE).build());
    assertThat(codes(s.validate())).containsExactly(ACTIVE_SEASON_UNREFERENCED.value());
    assertThat(s.activeSeason()).as("never inferred").isEmpty();
  }

  @Test
  void nonexistentActiveReferenceIsReported() {
    ArchiveState s = state(seasonId(9), season(1).build());
    assertThat(codes(s.validate())).containsExactly(ACTIVE_SEASON_NOT_FOUND.value());
  }

  @Test
  void referencedSeasonMustHaveActiveStatus() {
    ArchiveState s = state(seasonId(1), season(1).status(SeasonStatus.ARCHIVED).build());
    assertThat(codes(s.validate())).containsExactly(ACTIVE_SEASON_NOT_ACTIVE.value());
  }

  @Test
  void duplicatedActiveStatusIsReported() {
    ArchiveState s =
        state(
            seasonId(1),
            season(1).status(SeasonStatus.ACTIVE).build(),
            season(2).status(SeasonStatus.ACTIVE).build());
    assertThat(codes(s.validate()))
        .containsExactlyInAnyOrder(
            ACTIVE_SEASON_MULTIPLE.value(), ACTIVE_SEASON_UNREFERENCED.value());
  }

  @Test
  void duplicatedSeasonIdIsReported() {
    ArchiveState s = state(null, season(1).build(), season(1).name("Other name").build());
    assertThat(codes(s.validate())).containsExactly(SEASON_DUPLICATE_ID.value());
  }

  @Test
  void activationArchivesThePreviousSeasonAndMovesTheReference() {
    ArchiveState s =
        ArchiveState.empty()
            .appendSeason(season(1).build())
            .activate(seasonId(1))
            .appendSeason(season(2).build());
    ArchiveState next = s.activate(seasonId(2));

    assertThat(next.activeSeasonId()).isEqualTo(seasonId(2));
    assertThat(next.season(seasonId(1))).map(SeasonRecord::status).contains(SeasonStatus.ARCHIVED);
    assertThat(next.season(seasonId(2))).map(SeasonRecord::status).contains(SeasonStatus.ACTIVE);
    assertThat(next.validate().issues()).isEmpty();
    // Original state is untouched.
    assertThat(s.activeSeasonId()).isEqualTo(seasonId(1));
  }

  @Test
  void activationRequiresAnExistingPlannedSeason() {
    ArchiveState s = ArchiveState.empty().appendSeason(season(1).build()).activate(seasonId(1));
    assertThatThrownBy(() -> s.activate(seasonId(7)))
        .isInstanceOfSatisfying(
            InvalidDomainValueException.class,
            e -> assertThat(e.code()).isEqualTo(DomainErrorCode.REFERENCE_NOT_FOUND));
    assertThatThrownBy(() -> s.activate(seasonId(1)))
        .isInstanceOfSatisfying(
            InvalidDomainValueException.class,
            e -> assertThat(e.code()).isEqualTo(DomainErrorCode.SEASON_TRANSITION_ILLEGAL));
    ArchiveState archived = s.archiveActiveSeason();
    assertThat(archived.activeSeasonId()).isNull();
    assertThatThrownBy(() -> archived.activate(seasonId(1)))
        .as("archived seasons are never reactivated")
        .isInstanceOf(InvalidDomainValueException.class);
  }

  @Test
  void transitionsRefuseToOperateOnAnInvalidState() {
    ArchiveState invalid = state(seasonId(9), season(1).build());
    assertThatThrownBy(() -> invalid.activate(seasonId(1))).isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> invalid.appendSeason(season(2).build()))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void appendRejectsDuplicatesAndImplicitActivation() {
    ArchiveState s = ArchiveState.empty().appendSeason(season(1).build());
    assertThatThrownBy(() -> s.appendSeason(season(1).build()))
        .isInstanceOfSatisfying(
            InvalidDomainValueException.class,
            e -> assertThat(e.code()).isEqualTo(DomainErrorCode.DUPLICATE_ID));
    assertThatThrownBy(() -> s.appendSeason(season(2).status(SeasonStatus.ACTIVE).build()))
        .isInstanceOf(InvalidDomainValueException.class);
  }

  @Test
  void renameKeepsIdentityAndReferences() {
    ArchiveState s = ArchiveState.empty().appendSeason(season(1).build()).activate(seasonId(1));
    ArchiveState renamed = s.renameSeason(seasonId(1), DisplayName.of("Winter"));
    assertThat(renamed.activeSeasonId()).isEqualTo(seasonId(1));
    assertThat(renamed.activeSeason()).map(r -> r.name().value()).contains("Winter");
    // Names need not be unique.
    ArchiveState twoWinters = renamed.appendSeason(season(2).name("Winter").build());
    assertThat(twoWinters.validate().isValid()).isTrue();
  }

  @Test
  void unknownSeasonReferencesAndDuplicateRecordsAreReported() {
    PortalMarker marker =
        new PortalMarker(
            portalMarkerId(1),
            DisplayName.of("Old base"),
            overworld(0, 70, 0),
            seasonId(5),
            instant(0));
    BackupRecord backup = new BackupRecord(backupId(1), instant(0), SUM, 10, null, seasonId(6));
    PlayerArchiveRecord archive =
        new PlayerArchiveRecord(
            playerArchiveId(1),
            playerId(1),
            seasonId(7),
            instant(0),
            Set.of(ArchiveCategory.INVENTORY),
            List.of());
    ArchiveState s =
        new ArchiveState(
            List.of(season(1).build()),
            null,
            List.of(),
            List.of(marker, marker),
            List.of(backup),
            List.of(archive));
    assertThat(codes(s.validate()))
        .containsExactlyInAnyOrder(
            PORTAL_MARKER_DUPLICATE_ID.value(),
            UNKNOWN_SEASON_REFERENCE.value(),
            UNKNOWN_SEASON_REFERENCE.value(),
            UNKNOWN_SEASON_REFERENCE.value(),
            UNKNOWN_SEASON_REFERENCE.value());
  }

  @Test
  void addingRecordsKeepsTheStateValid() {
    ArchiveState s = ArchiveState.empty().appendSeason(season(1).build());
    PortalMarker marker =
        new PortalMarker(
            portalMarkerId(1), DisplayName.of("Hub"), overworld(0, 70, 0), seasonId(1), instant(0));
    ArchiveState withMarker = s.withPortalMarker(marker);
    assertThat(withMarker.portalMarkers()).containsExactly(marker);
    assertThatThrownBy(() -> withMarker.withPortalMarker(marker))
        .isInstanceOf(ValidationException.class);
    PortalMarker dangling =
        new PortalMarker(
            portalMarkerId(2),
            DisplayName.of("Nowhere"),
            overworld(0, 70, 0),
            seasonId(99),
            instant(0));
    assertThatThrownBy(() -> s.withPortalMarker(dangling)).isInstanceOf(ValidationException.class);
  }

  @Test
  void originWarningsDoNotInvalidate() {
    ArchiveState s =
        state(
            null,
            season(1)
                .origin(DimensionKey.OVERWORLD, 100, 64, 0) // not 128-aligned
                .origin(DimensionKey.THE_NETHER, 16, 64, 0) // not 100/8
                .build());
    ValidationResult result = s.validate();
    assertThat(result.isValid()).isTrue();
    assertThat(result.issues()).allMatch(i -> i.severity() == Severity.WARNING);
    assertThat(codes(result))
        .containsExactlyInAnyOrder(ORIGIN_MISALIGNED.value(), ORIGIN_NETHER_UNPAIRED.value());
    assertThat(result.issues().get(0).remediation()).isNotNull();
  }

  @Test
  void issueOrderDoesNotDependOnRecordOrder() {
    SeasonRecord a =
        season(1).status(SeasonStatus.ACTIVE).origin(DimensionKey.OVERWORLD, 5, 0, 5).build();
    SeasonRecord b = season(2).status(SeasonStatus.ACTIVE).build();
    SeasonRecord c = season(3).origin(DimensionKey.THE_END, 3, 0, 3).build();
    List<ValidationIssue> forward = state(seasonId(8), a, b, c).validate().issues();
    List<ValidationIssue> backward = state(seasonId(8), c, b, a).validate().issues();
    assertThat(forward).isNotEmpty().containsExactlyElementsOf(backward);
  }

  @Test
  void listsCannotBeMutatedThroughAliases() {
    List<SeasonRecord> source = new ArrayList<>(List.of(season(1).build()));
    ArchiveState s = new ArchiveState(source, null, List.of(), List.of(), List.of(), List.of());
    source.add(season(2).build());
    assertThat(s.seasons()).hasSize(1);
    assertThatThrownBy(() -> s.seasons().add(season(3).build()))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> s.backups().clear()).isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void playerArchiveCollectionsAreImmutableAndDeterministic() {
    var categories =
        new java.util.HashSet<>(Set.of(ArchiveCategory.RECIPES, ArchiveCategory.INVENTORY));
    PlayerArchiveRecord r =
        new PlayerArchiveRecord(
            playerArchiveId(1),
            playerId(1),
            seasonId(1),
            instant(0),
            categories,
            List.of(overworld(1, 2, 3)));
    categories.add(ArchiveCategory.STATISTICS);
    assertThat(r.categories()).containsExactly(ArchiveCategory.INVENTORY, ArchiveCategory.RECIPES);
    assertThatThrownBy(() -> r.categories().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> r.physicalContainers().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(
            () ->
                new PlayerArchiveRecord(
                    playerArchiveId(1), playerId(1), seasonId(1), instant(0), Set.of(), List.of()))
        .isInstanceOf(InvalidDomainValueException.class);
  }

  @Test
  void backupSizeMustNotBeNegative() {
    assertThatThrownBy(() -> new BackupRecord(backupId(1), instant(0), SUM, -1, null, null))
        .isInstanceOfSatisfying(
            InvalidDomainValueException.class,
            e -> assertThat(e.code()).isEqualTo(DomainErrorCode.VALUE_INVALID));
  }

  @Test
  void issueCodesAreStable() {
    List<IssueCode> all =
        List.of(
            SEASON_DUPLICATE_ID,
            ACTIVE_SEASON_NOT_FOUND,
            ACTIVE_SEASON_NOT_ACTIVE,
            ACTIVE_SEASON_UNREFERENCED,
            ACTIVE_SEASON_MULTIPLE,
            PROTECTED_REGION_DUPLICATE_ID,
            PORTAL_MARKER_DUPLICATE_ID,
            BACKUP_DUPLICATE_ID,
            PLAYER_ARCHIVE_DUPLICATE_ID,
            UNKNOWN_SEASON_REFERENCE,
            ORIGIN_MISALIGNED,
            ORIGIN_NETHER_UNPAIRED);
    assertThat(all)
        .extracting(IssueCode::value)
        .containsExactly(
            "archive.season.duplicate_id",
            "archive.active_season.not_found",
            "archive.active_season.not_active",
            "archive.active_season.unreferenced",
            "archive.active_season.multiple",
            "archive.protected_region.duplicate_id",
            "archive.portal_marker.duplicate_id",
            "archive.backup.duplicate_id",
            "archive.player_archive.duplicate_id",
            "archive.reference.unknown_season",
            "season.origin.misaligned",
            "season.origin.nether_unpaired");
  }
}
