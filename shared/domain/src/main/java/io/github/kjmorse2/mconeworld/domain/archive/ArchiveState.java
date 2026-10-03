// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.archive;

import static io.github.kjmorse2.mconeworld.domain.archive.ArchiveIssueCodes.ACTIVE_SEASON_MULTIPLE;
import static io.github.kjmorse2.mconeworld.domain.archive.ArchiveIssueCodes.ACTIVE_SEASON_NOT_ACTIVE;
import static io.github.kjmorse2.mconeworld.domain.archive.ArchiveIssueCodes.ACTIVE_SEASON_NOT_FOUND;
import static io.github.kjmorse2.mconeworld.domain.archive.ArchiveIssueCodes.ACTIVE_SEASON_UNREFERENCED;
import static io.github.kjmorse2.mconeworld.domain.archive.ArchiveIssueCodes.BACKUP_DUPLICATE_ID;
import static io.github.kjmorse2.mconeworld.domain.archive.ArchiveIssueCodes.ORIGIN_MISALIGNED;
import static io.github.kjmorse2.mconeworld.domain.archive.ArchiveIssueCodes.ORIGIN_NETHER_UNPAIRED;
import static io.github.kjmorse2.mconeworld.domain.archive.ArchiveIssueCodes.PENDING_ACTIVATION_STALE;
import static io.github.kjmorse2.mconeworld.domain.archive.ArchiveIssueCodes.PLAYER_ARCHIVE_DUPLICATE_ID;
import static io.github.kjmorse2.mconeworld.domain.archive.ArchiveIssueCodes.PORTAL_MARKER_DUPLICATE_ID;
import static io.github.kjmorse2.mconeworld.domain.archive.ArchiveIssueCodes.PROTECTED_REGION_DUPLICATE_ID;
import static io.github.kjmorse2.mconeworld.domain.archive.ArchiveIssueCodes.SEASON_DUPLICATE_ID;
import static io.github.kjmorse2.mconeworld.domain.archive.ArchiveIssueCodes.UNKNOWN_BACKUP_REFERENCE;
import static io.github.kjmorse2.mconeworld.domain.archive.ArchiveIssueCodes.UNKNOWN_SEASON_REFERENCE;

import io.github.kjmorse2.mconeworld.domain.BackupId;
import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.DisplayName;
import io.github.kjmorse2.mconeworld.domain.DomainErrorCode;
import io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.coordinate.DimensionOrigin;
import io.github.kjmorse2.mconeworld.domain.coordinate.OriginAlignment;
import io.github.kjmorse2.mconeworld.domain.coordinate.OverworldNetherScaling;
import io.github.kjmorse2.mconeworld.domain.provenance.ProtectedRegion;
import io.github.kjmorse2.mconeworld.domain.season.SeasonRecord;
import io.github.kjmorse2.mconeworld.domain.season.SeasonStatus;
import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import io.github.kjmorse2.mconeworld.domain.validation.IssueLocation;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationIssue;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * Immutable, manifest-independent state of an MCOneWorld archive.
 *
 * <p><b>Structure vs semantics.</b> The constructor enforces structure only: non-null fields and
 * unmodifiable defensive copies. Any combination of records can be represented, so a reader can
 * decode a damaged sidecar and then report <em>all</em> of its problems through {@link
 * #validate()}. Readers must validate before treating a state as usable. The transition methods
 * ({@link #activate}, {@link #appendSeason}, …) refuse to operate on an invalid state and always
 * produce a valid one.
 *
 * <p><b>Active season.</b> The active season is exactly {@link #activeSeasonId()}. It is never
 * inferred from timestamps or list order. A valid state has at most one season with status {@link
 * SeasonStatus#ACTIVE}, and it is the referenced one.
 *
 * <p><b>Order.</b> Every list keeps append (historical) order, which serializes deterministically.
 *
 * @param seasons all seasons in append order, including archived and cancelled ones
 * @param activeSeasonId the explicitly selected active season, or {@code null} if none is active
 * @param protectedRegions explicit protected areas
 * @param portalMarkers archive portal markers
 * @param backups verified backups
 *     <p><b>Pending activation</b> (manifest 1.1, ADR 0011). The Tool activates a season offline
 *     and records it in {@link #pendingActivation()}: the season is active in the sidecar, but its
 *     world spawn and player resets have not been applied by the server yet. While that is so, no
 *     other activation is allowed. A pending activation that names a season other than the active
 *     one is stale: it is reported as a warning and ignored.
 * @param playerArchives player archive metadata
 * @param worldBinding which world this archive belongs to, or {@code null} if never recorded
 * @param pendingActivation the active season whose activation awaits application by the server, or
 *     {@code null}
 * @param archivedDimensions dimensions archived out of the world, in append order
 */
public record ArchiveState(
    List<SeasonRecord> seasons,
    @Nullable SeasonId activeSeasonId,
    List<ProtectedRegion> protectedRegions,
    List<PortalMarker> portalMarkers,
    List<BackupRecord> backups,
    List<PlayerArchiveRecord> playerArchives,
    @Nullable WorldBinding worldBinding,
    @Nullable SeasonId pendingActivation,
    List<ArchivedDimension> archivedDimensions) {

  private static final IssueLocation SEASONS = IssueLocation.root().field("seasons");

  /**
   * Copies every list, so later changes to the arguments have no effect. Null elements are
   * rejected.
   */
  public ArchiveState {
    seasons = List.copyOf(seasons);
    protectedRegions = List.copyOf(protectedRegions);
    portalMarkers = List.copyOf(portalMarkers);
    backups = List.copyOf(backups);
    playerArchives = List.copyOf(playerArchives);
    archivedDimensions = List.copyOf(archivedDimensions);
  }

  /** A state without the manifest 1.1 records: no binding, nothing pending, nothing archived. */
  public ArchiveState(
      List<SeasonRecord> seasons,
      @Nullable SeasonId activeSeasonId,
      List<ProtectedRegion> protectedRegions,
      List<PortalMarker> portalMarkers,
      List<BackupRecord> backups,
      List<PlayerArchiveRecord> playerArchives) {
    this(
        seasons,
        activeSeasonId,
        protectedRegions,
        portalMarkers,
        backups,
        playerArchives,
        null,
        null,
        List.of());
  }

  /** A state with no records and no active season. */
  public static ArchiveState empty() {
    return new ArchiveState(List.of(), null, List.of(), List.of(), List.of(), List.of());
  }

  /** Returns the season with {@code id}, if present. */
  public Optional<SeasonRecord> season(SeasonId id) {
    return seasons.stream().filter(s -> s.id().equals(id)).findFirst();
  }

  /** Returns the explicitly selected active season, if one is selected and exists. */
  public Optional<SeasonRecord> activeSeason() {
    SeasonId active = activeSeasonId;
    return active == null ? Optional.empty() : season(active);
  }

  // ---------------------------------------------------------------------------------------------
  // Validation
  // ---------------------------------------------------------------------------------------------

  /**
   * Checks every cross-record rule and returns all findings in deterministic order. Nothing is
   * repaired.
   */
  public ValidationResult validate() {
    List<ValidationIssue> issues = new ArrayList<>();
    Set<SeasonId> seasonIds = new HashSet<>();
    List<SeasonRecord> activeStatus = new ArrayList<>();

    for (SeasonRecord season : seasons) {
      IssueLocation at = SEASONS.key(season.id().toString());
      if (!seasonIds.add(season.id())) {
        issues.add(
            ValidationIssue.error(
                SEASON_DUPLICATE_ID, at, "Season id " + season.id() + " appears more than once"));
      }
      if (season.status() == SeasonStatus.ACTIVE) {
        activeStatus.add(season);
        if (!season.id().equals(activeSeasonId)) {
          issues.add(
              ValidationIssue.error(
                      ACTIVE_SEASON_UNREFERENCED,
                      at.field("status"),
                      "Season "
                          + season.id()
                          + " has status ACTIVE but is not the selected active season")
                  .withRemediation("Select it explicitly as active, or archive it."));
        }
      }
      originIssues(season, at, issues);
    }

    if (activeStatus.size() > 1) {
      issues.add(
          ValidationIssue.error(
              ACTIVE_SEASON_MULTIPLE,
              SEASONS,
              activeStatus.size() + " seasons have status ACTIVE; at most one may"));
    }

    SeasonId active = activeSeasonId;
    if (active != null) {
      IssueLocation at = IssueLocation.root().field("activeSeason");
      Optional<SeasonRecord> referenced = season(active);
      if (referenced.isEmpty()) {
        issues.add(
            ValidationIssue.error(
                ACTIVE_SEASON_NOT_FOUND, at, "Active season " + active + " does not exist"));
      } else if (referenced.get().status() != SeasonStatus.ACTIVE) {
        issues.add(
            ValidationIssue.error(
                ACTIVE_SEASON_NOT_ACTIVE,
                at,
                "Active season " + active + " has status " + referenced.get().status()));
      }
    }

    duplicates(
        protectedRegions,
        r -> r.id().toString(),
        "protectedRegions",
        PROTECTED_REGION_DUPLICATE_ID,
        issues);
    duplicates(
        portalMarkers, m -> m.id().toString(), "portalMarkers", PORTAL_MARKER_DUPLICATE_ID, issues);
    duplicates(backups, b -> b.id().toString(), "backups", BACKUP_DUPLICATE_ID, issues);
    duplicates(
        playerArchives,
        a -> a.id().toString(),
        "playerArchives",
        PLAYER_ARCHIVE_DUPLICATE_ID,
        issues);

    for (PortalMarker marker : portalMarkers) {
      seasonReference(
          marker.destinationSeason(),
          IssueLocation.root()
              .field("portalMarkers")
              .key(marker.id().toString())
              .field("destinationSeason"),
          seasonIds,
          issues);
    }
    for (BackupRecord backup : backups) {
      seasonReference(
          backup.activeSeason(),
          IssueLocation.root().field("backups").key(backup.id().toString()).field("activeSeason"),
          seasonIds,
          issues);
    }
    for (PlayerArchiveRecord archive : playerArchives) {
      seasonReference(
          archive.season(),
          IssueLocation.root().field("playerArchives").key(archive.id().toString()).field("season"),
          seasonIds,
          issues);
    }

    SeasonId pending = pendingActivation;
    if (pending != null) {
      IssueLocation at = IssueLocation.root().field("pendingActivation");
      if (!seasonIds.contains(pending)) {
        seasonReference(pending, at, seasonIds, issues);
      } else if (!pending.equals(activeSeasonId)) {
        issues.add(
            ValidationIssue.warning(
                    PENDING_ACTIVATION_STALE,
                    at,
                    "The pending activation of season "
                        + pending
                        + " is stale: that season is not the active one, so it is ignored")
                .withRemediation(
                    "Check that every MCOneWorld component reads manifest 1.1 or newer."));
      }
    }
    Set<BackupId> backupIds = new HashSet<>();
    backups.forEach(b -> backupIds.add(b.id()));
    for (int i = 0; i < archivedDimensions.size(); i++) {
      ArchivedDimension archived = archivedDimensions.get(i);
      IssueLocation at = IssueLocation.root().field("archivedDimensions").index(i);
      seasonReference(archived.season(), at.field("season"), seasonIds, issues);
      if (!backupIds.contains(archived.backup())) {
        issues.add(
            ValidationIssue.error(
                UNKNOWN_BACKUP_REFERENCE,
                at.field("backup"),
                "Backup " + archived.backup() + " does not exist"));
      }
    }
    return ValidationResult.of(issues);
  }

  private static void originIssues(
      SeasonRecord season, IssueLocation at, List<ValidationIssue> issues) {
    for (DimensionOrigin origin : season.origins().values()) {
      OriginAlignment alignment = OriginAlignment.recommendedFor(origin);
      if (!alignment.isAligned()) {
        issues.add(
            ValidationIssue.warning(
                    ORIGIN_MISALIGNED,
                    at.field("origins").key(origin.dimension().toString()),
                    "Origin ("
                        + origin.x()
                        + ", "
                        + origin.z()
                        + ") is not aligned to "
                        + alignment.alignment()
                        + " blocks (remainders "
                        + alignment.remainderX()
                        + ", "
                        + alignment.remainderZ()
                        + ")")
                .withRemediation(
                    "Choose an aligned origin explicitly; MCOneWorld never rounds origins."));
      }
    }
    Optional<DimensionOrigin> overworld = season.origin(DimensionKey.OVERWORLD);
    Optional<DimensionOrigin> nether = season.origin(DimensionKey.THE_NETHER);
    if (overworld.isPresent()
        && nether.isPresent()
        && !OverworldNetherScaling.isPaired(overworld.get(), nether.get())) {
      issues.add(
          ValidationIssue.warning(
              ORIGIN_NETHER_UNPAIRED,
              at.field("origins").key(DimensionKey.THE_NETHER.toString()),
              "Nether origin is not exactly the Overworld origin divided by "
                  + OverworldNetherScaling.NETHER_COORDINATE_SCALE));
    }
  }

  private static <T> void duplicates(
      List<T> records,
      Function<T, String> id,
      String field,
      IssueCode code,
      List<ValidationIssue> issues) {
    Set<String> seen = new HashSet<>();
    for (T record : records) {
      String key = id.apply(record);
      if (!seen.add(key)) {
        issues.add(
            ValidationIssue.error(
                code,
                IssueLocation.root().field(field).key(key),
                "Id " + key + " appears more than once"));
      }
    }
  }

  private static void seasonReference(
      @Nullable SeasonId reference,
      IssueLocation at,
      Set<SeasonId> known,
      List<ValidationIssue> issues) {
    if (reference != null && !known.contains(reference)) {
      issues.add(
          ValidationIssue.error(
              UNKNOWN_SEASON_REFERENCE, at, "Season " + reference + " does not exist"));
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Transitions: each requires a valid state and returns a valid state.
  // ---------------------------------------------------------------------------------------------

  /**
   * Appends a new season. Use {@link #activate(SeasonId)} to make it active.
   *
   * @throws InvalidDomainValueException {@code DUPLICATE_ID} if the id exists, or {@code
   *     VALUE_INVALID} if the season is already ACTIVE (activation must be explicit)
   * @throws io.github.kjmorse2.mconeworld.domain.validation.ValidationException if this state is
   *     invalid
   */
  public ArchiveState appendSeason(SeasonRecord season) {
    validate().requireValid();
    if (season(season.id()).isPresent()) {
      throw new InvalidDomainValueException(
          DomainErrorCode.DUPLICATE_ID, "Season " + season.id() + " already exists");
    }
    if (season.status() == SeasonStatus.ACTIVE) {
      throw new InvalidDomainValueException(
          DomainErrorCode.VALUE_INVALID,
          "Append seasons as non-active and select them with activate()");
    }
    return withSeasons(append(seasons, season), activeSeasonId);
  }

  /**
   * Makes {@code target} the active season. The previously active season, if any, becomes {@link
   * SeasonStatus#ARCHIVED}.
   *
   * @throws InvalidDomainValueException {@code REFERENCE_NOT_FOUND} if the season does not exist,
   *     or {@code SEASON_TRANSITION_ILLEGAL} if it is not PLANNED
   * @throws io.github.kjmorse2.mconeworld.domain.validation.ValidationException if this state is
   *     invalid
   */
  public ArchiveState activate(SeasonId target) {
    validate().requireValid();
    requireNothingPending();
    SeasonRecord next = requireSeason(target);
    SeasonRecord activated = next.transitionTo(SeasonStatus.ACTIVE);
    SeasonId previous = activeSeasonId;
    List<SeasonRecord> updated =
        replace(
            seasons,
            s -> {
              if (s.id().equals(target)) {
                return activated;
              }
              if (s.id().equals(previous)) {
                return s.transitionTo(SeasonStatus.ARCHIVED);
              }
              return s;
            });
    return withSeasons(updated, target);
  }

  /**
   * Archives the active season without activating another. Afterwards no season is active.
   *
   * @throws InvalidDomainValueException {@code REFERENCE_NOT_FOUND} if no season is active
   */
  public ArchiveState archiveActiveSeason() {
    validate().requireValid();
    requireNothingPending();
    SeasonId active = activeSeasonId;
    if (active == null) {
      throw new InvalidDomainValueException(
          DomainErrorCode.REFERENCE_NOT_FOUND, "No season is active");
    }
    return withSeasons(
        replace(seasons, s -> s.id().equals(active) ? s.transitionTo(SeasonStatus.ARCHIVED) : s),
        null);
  }

  /** Cancels a PLANNED season. See {@link #activate} for exceptions. */
  public ArchiveState cancelSeason(SeasonId id) {
    validate().requireValid();
    SeasonRecord cancelled = requireSeason(id).transitionTo(SeasonStatus.CANCELLED);
    return withSeasons(replace(seasons, s -> s.id().equals(id) ? cancelled : s), activeSeasonId);
  }

  /** Renames a season. Its identity and every reference to it are unchanged. */
  public ArchiveState renameSeason(SeasonId id, DisplayName name) {
    validate().requireValid();
    SeasonRecord renamed = requireSeason(id).renamed(name);
    return withSeasons(replace(seasons, s -> s.id().equals(id) ? renamed : s), activeSeasonId);
  }

  /**
   * Activates {@code target} offline and marks the activation pending application by the server
   * (ADR 0011): {@link #activate} plus {@link #pendingActivation()} set to {@code target}.
   *
   * @throws InvalidDomainValueException as {@link #activate}, and {@code SEASON_TRANSITION_ILLEGAL}
   *     while another activation is pending
   */
  public ArchiveState activateForApplication(SeasonId target) {
    ArchiveState activated = activate(target);
    return activated.copy(
        activated.seasons,
        activated.activeSeasonId,
        backups,
        worldBinding,
        target,
        archivedDimensions);
  }

  /**
   * Records that the server applied the pending activation of {@code season} (the plugin's side of
   * ADR 0011).
   *
   * @throws InvalidDomainValueException {@code SEASON_TRANSITION_ILLEGAL} unless {@code season} is
   *     the active season and its activation is pending
   */
  public ArchiveState markActivationApplied(SeasonId season) {
    validate().requireValid();
    if (!isActivationPending() || !season.equals(pendingActivation)) {
      throw new InvalidDomainValueException(
          DomainErrorCode.SEASON_TRANSITION_ILLEGAL,
          "No pending activation of season " + season + " to mark applied");
    }
    return copy(seasons, activeSeasonId, backups, worldBinding, null, archivedDimensions);
  }

  /** Whether the active season's activation still awaits application (a stale marker does not). */
  public boolean isActivationPending() {
    SeasonId pending = pendingActivation;
    return pending != null && pending.equals(activeSeasonId);
  }

  /** Records which world this archive belongs to, replacing any earlier binding. */
  public ArchiveState withWorldBinding(WorldBinding binding) {
    return checked(
        copy(
            seasons,
            activeSeasonId,
            backups,
            Objects.requireNonNull(binding, "binding"),
            pendingActivation,
            archivedDimensions));
  }

  /** Appends an archived-dimension record; its backup must already be recorded. */
  public ArchiveState withArchivedDimension(ArchivedDimension archived) {
    return checked(
        copy(
            seasons,
            activeSeasonId,
            backups,
            worldBinding,
            pendingActivation,
            append(archivedDimensions, archived)));
  }

  /** Appends a protected region. The resulting state must validate. */
  public ArchiveState withProtectedRegion(ProtectedRegion region) {
    return checked(
        new ArchiveState(
            seasons,
            activeSeasonId,
            append(protectedRegions, region),
            portalMarkers,
            backups,
            playerArchives,
            worldBinding,
            pendingActivation,
            archivedDimensions));
  }

  /** Appends a portal marker. The resulting state must validate. */
  public ArchiveState withPortalMarker(PortalMarker marker) {
    return checked(
        new ArchiveState(
            seasons,
            activeSeasonId,
            protectedRegions,
            append(portalMarkers, marker),
            backups,
            playerArchives,
            worldBinding,
            pendingActivation,
            archivedDimensions));
  }

  /** Appends a backup record. The resulting state must validate. */
  public ArchiveState withBackup(BackupRecord backup) {
    return checked(
        copy(
            seasons,
            activeSeasonId,
            append(backups, backup),
            worldBinding,
            pendingActivation,
            archivedDimensions));
  }

  /** Appends a player archive record. The resulting state must validate. */
  public ArchiveState withPlayerArchive(PlayerArchiveRecord archive) {
    return checked(
        new ArchiveState(
            seasons,
            activeSeasonId,
            protectedRegions,
            portalMarkers,
            backups,
            append(playerArchives, archive),
            worldBinding,
            pendingActivation,
            archivedDimensions));
  }

  private ArchiveState checked(ArchiveState next) {
    validate().requireValid();
    next.validate().requireValid();
    return next;
  }

  private SeasonRecord requireSeason(SeasonId id) {
    return season(id)
        .orElseThrow(
            () ->
                new InvalidDomainValueException(
                    DomainErrorCode.REFERENCE_NOT_FOUND, "Season " + id + " does not exist"));
  }

  private ArchiveState withSeasons(List<SeasonRecord> newSeasons, @Nullable SeasonId newActive) {
    return copy(
        newSeasons, newActive, backups, worldBinding, pendingActivation, archivedDimensions);
  }

  /** A copy with the fields transitions change; regions, markers, and player archives are kept. */
  private ArchiveState copy(
      List<SeasonRecord> newSeasons,
      @Nullable SeasonId newActive,
      List<BackupRecord> newBackups,
      @Nullable WorldBinding newBinding,
      @Nullable SeasonId newPending,
      List<ArchivedDimension> newArchived) {
    return new ArchiveState(
        newSeasons,
        newActive,
        protectedRegions,
        portalMarkers,
        newBackups,
        playerArchives,
        newBinding,
        newPending,
        newArchived);
  }

  private void requireNothingPending() {
    if (isActivationPending()) {
      throw new InvalidDomainValueException(
          DomainErrorCode.SEASON_TRANSITION_ILLEGAL,
          "The activation of season "
              + pendingActivation
              + " has not been applied by the server yet; start the server once first");
    }
  }

  private static <T> List<T> append(List<T> list, T element) {
    List<T> copy = new ArrayList<>(list);
    copy.add(Objects.requireNonNull(element, "element"));
    return copy;
  }

  private static <T> List<T> replace(List<T> list, UnaryOperator<T> mapper) {
    return list.stream().map(mapper).toList();
  }
}
