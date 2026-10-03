// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.transition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kjmorse2.mconeworld.domain.BackupId;
import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.DisplayName;
import io.github.kjmorse2.mconeworld.domain.OperationId;
import io.github.kjmorse2.mconeworld.domain.ProtectedRegionId;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.archive.ArchiveState;
import io.github.kjmorse2.mconeworld.domain.archive.ArchivedDimension;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkRectangle;
import io.github.kjmorse2.mconeworld.domain.operation.OperationState;
import io.github.kjmorse2.mconeworld.domain.provenance.ProtectedRegion;
import io.github.kjmorse2.mconeworld.domain.season.SeasonRecord;
import io.github.kjmorse2.mconeworld.domain.season.SeasonStatus;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexSnapshot;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexStore;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkQuery;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestDocument;
import io.github.kjmorse2.mconeworld.sidecar.operation.OperationJournals;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarRoot;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarStore;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarTransaction;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarWriter;
import io.github.kjmorse2.mconeworld.sidecar.storage.StaleLockPolicy;
import io.github.kjmorse2.mconeworld.tool.core.ProgressListener;
import io.github.kjmorse2.mconeworld.tool.core.ToolException;
import io.github.kjmorse2.mconeworld.tool.core.ToolInfo;
import io.github.kjmorse2.mconeworld.tool.core.backup.BackupService;
import io.github.kjmorse2.mconeworld.tool.core.backup.FileCopy;
import io.github.kjmorse2.mconeworld.tool.core.init.SidecarInitializer;
import io.github.kjmorse2.mconeworld.tool.core.operation.OperationRunner;
import io.github.kjmorse2.mconeworld.tool.core.season.PlannerSettings;
import io.github.kjmorse2.mconeworld.tool.core.season.SeasonPlanner;
import io.github.kjmorse2.mconeworld.tool.core.season.SeasonProposal;
import io.github.kjmorse2.mconeworld.tool.core.session.SessionAction;
import io.github.kjmorse2.mconeworld.tool.core.session.SessionIssueCodes;
import io.github.kjmorse2.mconeworld.tool.core.session.ToolLocations;
import io.github.kjmorse2.mconeworld.tool.core.session.ToolSession;
import io.github.kjmorse2.mconeworld.tool.world.testing.SyntheticWorld;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The season transition on synthetic 26.2 worlds: the End archived and removed, the season
 * committed pending application, nothing else in the world touched; every refusal; a crash at every
 * point resumed to the same result; and rollback after a terminal failure.
 */
class SeasonTransitionTest {
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-03T12:00:00Z"), ZoneOffset.UTC);
  private static final DimensionKey O = DimensionKey.OVERWORLD;
  private static final DimensionKey N = DimensionKey.THE_NETHER;
  private static final DimensionKey E = DimensionKey.THE_END;
  private static final BackupService.SpaceProbe PLENTY = dir -> Long.MAX_VALUE;
  private static final String FIGHT =
      SeasonTransition.END_DIR + "/data/minecraft/ender_dragon_fight.dat";
  private static final byte[] FIGHT_BYTES = "dragon".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PAPER_CONFIG = "_version: 31\n".getBytes(StandardCharsets.UTF_8);
  private static final SeasonProposal PLACEMENT =
      new SeasonPlanner(PlannerSettings.DEFAULT).proposal(20_480, 0);

  @TempDir Path temp;

  // ---- fixtures --------------------------------------------------------------------------------

  private static List<ChunkPosition> square(DimensionKey d, int from, int to) {
    List<ChunkPosition> chunks = new ArrayList<>();
    for (int x = from; x < to; x++) {
      for (int z = from; z < to; z++) {
        chunks.add(new ChunkPosition(d, x, z));
      }
    }
    return chunks;
  }

  /** A 26.2 world with chunks in all three dimensions, the dragon fight, and a player. */
  private SyntheticWorld world(int dataVersion, boolean withEnd) {
    SyntheticWorld w =
        SyntheticWorld.modern(temp.resolve("server/world"))
            .levelDat(dataVersion)
            .seed(7L)
            .sessionLock()
            .chunks(O, "region", square(O, -2, 2), 1_790_000_000L)
            .chunks(N, "region", square(N, -1, 1), 1_790_000_000L)
            .file("players/data/5cd87f22-1adf-47f2-abf3-04a41dae96ee.dat", new byte[] {1, 2, 3});
    if (withEnd) {
      w.chunks(E, "region", square(E, -2, 2), 1_790_000_000L)
          .chunks(E, "entities", square(E, -1, 1), 1_790_000_000L)
          .file(FIGHT, FIGHT_BYTES)
          // Paper 26.1+'s per-world configuration and data: kept in place
          .file(SeasonTransition.END_DIR + "/paper-world.yml", PAPER_CONFIG)
          .file(SeasonTransition.END_DIR + "/data/paper/level_overrides.dat", new byte[] {4, 5});
    }
    return w;
  }

  private static ToolLocations locations(SyntheticWorld w) {
    return new ToolLocations(w.dir(), w.dir().resolveSibling("mconeworld-data"));
  }

  /** Initialized, optionally backed up. */
  private static ToolLocations prepared(SyntheticWorld w, boolean backup) throws IOException {
    ToolLocations l = locations(w);
    SidecarInitializer init = new SidecarInitializer(CLOCK, ProgressListener.NONE);
    init.apply(init.preview(l));
    if (backup) {
      BackupService backups = new BackupService(CLOCK, ProgressListener.NONE);
      backups.apply(backups.preview(l));
    }
    return l;
  }

  private ToolLocations ready() throws IOException {
    return prepared(world(4903, true), true);
  }

  private static SeasonTransition service(OperationRunner.Hook hook) {
    return new SeasonTransition(
        CLOCK, ProgressListener.NONE, hook, PLENTY, PlannerSettings.DEFAULT);
  }

  private static SeasonTransition service() {
    return service(OperationRunner.Hook.NONE);
  }

  private static TransitionRequest request() {
    return new TransitionRequest(DisplayName.of("Season 2 ☃"), PLACEMENT, false);
  }

  /** Every world file but session.lock, by path: its SHA-256. */
  private static Map<String, String> files(Path world, boolean includeEnd) throws IOException {
    Map<String, String> files = new TreeMap<>();
    try (Stream<Path> walk = Files.walk(world)) {
      for (Path f : walk.filter(Files::isRegularFile).toList()) {
        String relative = world.relativize(f).toString().replace('\\', '/');
        String end = SeasonTransition.END_DIR + "/";
        if (relative.equals("session.lock")
            || (!includeEnd
                && relative.startsWith(end)
                && !SeasonTransition.isKept(relative.substring(end.length())))) {
          continue;
        }
        files.put(relative, FileCopy.sha256(f).toString());
      }
    }
    return files;
  }

  /** The End's files that are archived: all but the kept ones. */
  private static Map<String, String> endFiles(Path world) throws IOException {
    Map<String, String> all = files(world, true);
    String end = SeasonTransition.END_DIR + "/";
    all.keySet()
        .removeIf(p -> !p.startsWith(end) || SeasonTransition.isKept(p.substring(end.length())));
    return all;
  }

  private static SidecarStore store(ToolLocations l) throws IOException {
    return SidecarStore.open(SidecarRoot.open(l.sidecarDir()));
  }

  private static ManifestDocument manifest(ToolLocations l) throws Exception {
    return store(l).readManifest().document();
  }

  private static long endRecords(ToolLocations l) throws IOException {
    try (ChunkIndexSnapshot reader = ChunkIndexStore.of(store(l)).openReader()) {
      return reader.count(ChunkQuery.all(E));
    }
  }

  private static OperationState state(ToolLocations l, OperationId op) throws IOException {
    return OperationJournals.of(store(l), CLOCK).inspect(op).progress().state();
  }

  /** Asserts the finished transition of {@code preview}, against the world before it. */
  private static void assertTransitioned(
      ToolLocations l, TransitionPreview preview, Map<String, String> outsideBefore)
      throws Exception {
    OperationId op = preview.plan().id();
    // Only Paper's kept configuration and data remain in the End; Minecraft creates the rest.
    try (Stream<Path> left = Files.walk(SeasonTransition.endPath(l))) {
      assertThat(
              left.filter(Files::isRegularFile)
                  .map(p -> SeasonTransition.endPath(l).relativize(p).toString().replace('\\', '/'))
                  .sorted()
                  .toList())
          .containsExactly("data/paper/level_overrides.dat", "paper-world.yml");
    }
    assertThat(files(l.worldDir(), true)).isEqualTo(outsideBefore);

    ManifestDocument doc = manifest(l);
    ArchiveState state = doc.state();
    assertThat(state.activeSeasonId()).isEqualTo(preview.season());
    assertThat(state.isActivationPending()).isTrue();
    SeasonRecord season = state.season(preview.season()).orElseThrow();
    assertThat(season.name().value()).isEqualTo("Season 2 ☃");
    assertThat(season.createdAt()).isEqualTo(preview.plan().createdAt());
    assertThat(season.origin(O)).contains(PLACEMENT.overworld());
    assertThat(season.origin(N)).contains(PLACEMENT.nether());
    assertThat(season.origin(E)).isEmpty();
    assertThat(season.spawn()).isEqualTo(PLACEMENT.spawn());
    BackupId archive = SeasonTransition.archiveOf(op);
    assertThat(state.archivedDimensions())
        .containsExactly(new ArchivedDimension(E, null, op, archive, CLOCK.instant()));
    assertThat(BackupService.verify(l, archive).verified()).isTrue();
    Path fight =
        l.sidecarDir().resolve("backups").resolve(archive.toString()).resolve("world/" + FIGHT);
    assertThat(Files.readAllBytes(fight)).isEqualTo(FIGHT_BYTES);

    assertThat(endRecords(l)).isZero();
    Path report = l.sidecarDir().resolve(SeasonTransition.reportPath(op).value());
    assertThat(Files.readAllLines(report)).hasSize(1 + 16);
    assertThat(state(l, op)).isEqualTo(OperationState.COMPLETED);
    assertThat(ToolSession.inspect(l, CLOCK).action(SessionAction.NEW_SEASON).blockers())
        .extracting(i -> i.code())
        .containsExactly(SessionIssueCodes.ACTIVATION_PENDING);
  }

  // ---- the happy path --------------------------------------------------------------------------

  @Test
  void previewChecksAndPlansWithoutWriting() throws Exception {
    ToolLocations l = ready();
    Map<String, String> before = files(l.worldDir(), true);
    byte[] manifestBefore = store(l).readManifestBytes();

    TransitionPreview preview = service().preview(l, request());

    // Chunks -2..1 and -1..0 each span 4 regions: 4 region files, 4 entities files, the fight
    assertThat(preview.endFiles()).isEqualTo(9); // Paper's two kept files are not counted
    assertThat(preview.endRecords()).isEqualTo(16);
    assertThat(preview.protectedEndRecords()).isZero();
    assertThat(preview.previous()).isNull();
    assertThat(preview.archivesEnd()).isTrue();
    assertThat(preview.plan().steps()).hasSize(4);
    assertThat(TransitionParameters.of(preview.plan()))
        .isEqualTo(new TransitionParameters(preview.season(), request().name(), PLACEMENT, true));
    assertThat(files(l.worldDir(), true)).isEqualTo(before);
    assertThat(store(l).readManifestBytes()).isEqualTo(manifestBefore);
  }

  @Test
  void theEndIsArchivedAndTheSeasonActivatedPendingApplication() throws Exception {
    ToolLocations l = ready();
    Map<String, String> outside = files(l.worldDir(), false);
    Map<String, String> end = endFiles(l.worldDir());
    TransitionPreview preview = service().preview(l, request());

    assertThat(service().apply(preview)).isEqualTo(preview.season());

    assertTransitioned(l, preview, outside);
    BackupId archive = SeasonTransition.archiveOf(preview.plan().id());
    for (Map.Entry<String, String> e : end.entrySet()) {
      Path copy =
          l.sidecarDir()
              .resolve("backups")
              .resolve(archive.toString())
              .resolve("world/" + e.getKey());
      assertThat(FileCopy.sha256(copy).toString()).as(e.getKey()).isEqualTo(e.getValue());
    }
  }

  @Test
  void thePreviousSeasonIsArchivedAndRecordedAsTheEndsSeason() throws Exception {
    ToolLocations l = ready();
    SeasonId first = SeasonId.of(UUID.fromString("00000000-0000-4000-8000-000000000001"));
    edit(
        l,
        s ->
            s.appendSeason(
                    SeasonRecord.planned(
                        first,
                        DisplayName.of("Season 1"),
                        Instant.parse("2026-01-01T00:00:00Z"),
                        new io.github.kjmorse2.mconeworld.domain.coordinate.BlockPosition(
                            O, 0, 64, 0)))
                .activate(first));
    rebackup(l);
    TransitionPreview preview = service().preview(l, request());
    assertThat(preview.previous()).isEqualTo(first);

    service().apply(preview);

    ArchiveState state = manifest(l).state();
    assertThat(state.season(first).orElseThrow().status()).isEqualTo(SeasonStatus.ARCHIVED);
    assertThat(state.archivedDimensions().getFirst().season()).isEqualTo(first);
  }

  @Test
  void withoutPaperFilesTheEndDirectoryIsRemovedEntirely() throws Exception {
    SyntheticWorld w = world(4903, true);
    Files.delete(w.dir().resolve(SeasonTransition.END_DIR + "/paper-world.yml"));
    Files.delete(w.dir().resolve(SeasonTransition.END_DIR + "/data/paper/level_overrides.dat"));
    Files.delete(w.dir().resolve(SeasonTransition.END_DIR + "/data/paper"));
    ToolLocations l = prepared(w, true);
    service().apply(service().preview(l, request()));
    assertThat(SeasonTransition.endPath(l)).doesNotExist();
    assertThat(l.worldDir().resolve("dimensions/minecraft/overworld")).isDirectory();
  }

  @Test
  void aWorldWithoutAnEndOnlyCommitsTheSeason() throws Exception {
    ToolLocations l = prepared(world(4903, false), true);
    Map<String, String> before = files(l.worldDir(), true);
    TransitionPreview preview = service().preview(l, request());
    assertThat(preview.archivesEnd()).isFalse();
    assertThat(preview.plan().steps()).hasSize(1);

    service().apply(preview);

    assertThat(files(l.worldDir(), true)).isEqualTo(before);
    ArchiveState state = manifest(l).state();
    assertThat(state.activeSeasonId()).isEqualTo(preview.season());
    assertThat(state.isActivationPending()).isTrue();
    assertThat(state.archivedDimensions()).isEmpty();
  }

  // ---- refusals --------------------------------------------------------------------------------

  @Test
  void withoutABackupOfTheWorldAsItIsTheTransitionIsRefused() throws Exception {
    ToolLocations none = prepared(world(4903, true), false);
    assertThatThrownBy(() -> service().preview(none, request()))
        .isInstanceOf(ToolException.class)
        .extracting(e -> ((ToolException) e).code())
        .isEqualTo(TransitionIssueCodes.BACKUP_NEEDED);
  }

  @Test
  void aBackupOfAnEarlierWorldStateDoesNotCount() throws Exception {
    ToolLocations l = ready();
    Files.write(
        l.worldDir().resolve("players/data/5cd87f22-1adf-47f2-abf3-04a41dae96ee.dat"),
        new byte[] {9});
    assertThatThrownBy(() -> service().preview(l, request()))
        .extracting(e -> ((ToolException) e).code())
        .isEqualTo(TransitionIssueCodes.BACKUP_NEEDED);
  }

  @Test
  void aBackupDamagedSincePlanningStopsApplyBeforeAnythingIsRecorded() throws Exception {
    ToolLocations l = ready();
    TransitionPreview preview = service().preview(l, request());
    Path level =
        l.sidecarDir()
            .resolve("backups")
            .resolve(preview.backup().id().toString())
            .resolve("world/level.dat");
    Files.write(level, new byte[] {0});
    assertThatThrownBy(() -> service().apply(preview))
        .extracting(e -> ((ToolException) e).code())
        .isEqualTo(TransitionIssueCodes.BACKUP_NEEDED);
    assertThat(OperationJournals.of(store(l), CLOCK).list()).doesNotContain(preview.plan().id());
    assertThat(SeasonTransition.endPath(l)).exists();
  }

  @Test
  void anotherVersionIsRefused() throws Exception {
    ToolLocations l = prepared(world(4440, true), true);
    assertThatThrownBy(() -> service().preview(l, request()))
        .extracting(e -> ((ToolException) e).code())
        .isEqualTo(TransitionIssueCodes.NOT_ALLOWED);
  }

  @Test
  void aRunningServerIsRefused() throws Exception {
    ToolLocations l = ready();
    TransitionPreview preview = service().preview(l, request());
    try (FileChannel channel =
            FileChannel.open(l.worldDir().resolve("session.lock"), StandardOpenOption.WRITE);
        FileLock lock = channel.lock()) {
      assertThat(lock.isValid()).isTrue();
      assertThatThrownBy(() -> service().preview(l, request()))
          .extracting(e -> ((ToolException) e).code())
          .isEqualTo(TransitionIssueCodes.NOT_ALLOWED);
      assertThatThrownBy(() -> service().apply(preview))
          .extracting(e -> ((ToolException) e).code())
          .isEqualTo(TransitionIssueCodes.NOT_ALLOWED);
    }
    assertThat(SeasonTransition.endPath(l)).exists();
  }

  @Test
  void anIndexThatDiffersFromTheWorldNeedsAReconcileFirst() throws Exception {
    ToolLocations l = ready();
    Files.delete(l.worldDir().resolve("dimensions/minecraft/overworld/region/r.0.0.mca"));
    assertThatThrownBy(() -> service().preview(l, request()))
        .extracting(e -> ((ToolException) e).code())
        .isEqualTo(TransitionIssueCodes.RECONCILE_NEEDED);
  }

  @Test
  void anInvalidPlacementIsRefused() throws Exception {
    ToolLocations l = ready();
    SeasonPlanner planner = new SeasonPlanner(PlannerSettings.DEFAULT);
    SeasonProposal misaligned = planner.proposal(20_488, 0);
    SeasonProposal occupied = planner.proposal(0, 0);
    SeasonProposal unpaired =
        new SeasonProposal(
            PLACEMENT.overworld(), planner.proposal(20_608, 0).nether(), PLACEMENT.spawn());
    SeasonProposal farSpawn =
        new SeasonProposal(
            PLACEMENT.overworld(),
            PLACEMENT.nether(),
            new io.github.kjmorse2.mconeworld.domain.coordinate.BlockPosition(O, 0, 64, 0));
    for (SeasonProposal p : List.of(misaligned, occupied, unpaired, farSpawn)) {
      assertThatThrownBy(
              () -> service().preview(l, new TransitionRequest(DisplayName.of("x"), p, false)))
          .as(p.toString())
          .extracting(e -> ((ToolException) e).code())
          .isEqualTo(TransitionIssueCodes.ORIGIN_INVALID);
    }
  }

  @Test
  void protectedEndChunksNeedAcknowledgement() throws Exception {
    ToolLocations l = ready();
    edit(
        l,
        s ->
            s.withProtectedRegion(
                new ProtectedRegion(
                    ProtectedRegionId.of(UUID.fromString("00000000-0000-4000-8000-0000000000aa")),
                    DisplayName.of("Exit portal"),
                    new ChunkRectangle(E, -1, -1, 0, 0),
                    CLOCK.instant())));
    rebackup(l);
    assertThatThrownBy(() -> service().preview(l, request()))
        .extracting(e -> ((ToolException) e).code())
        .isEqualTo(TransitionIssueCodes.PROTECTED_END);
    TransitionPreview preview =
        service().preview(l, new TransitionRequest(request().name(), PLACEMENT, true));
    assertThat(preview.protectedEndRecords()).isEqualTo(4);
  }

  @Test
  void aSecondTransitionWaitsUntilTheServerAppliedTheFirst() throws Exception {
    ToolLocations l = ready();
    service().apply(service().preview(l, request()));
    rebackup(l);
    SeasonProposal next = new SeasonPlanner(PlannerSettings.DEFAULT).proposal(-20_480, 0);
    assertThatThrownBy(
            () -> service().preview(l, new TransitionRequest(DisplayName.of("3"), next, false)))
        .extracting(e -> ((ToolException) e).code())
        .isEqualTo(TransitionIssueCodes.NOT_ALLOWED);
  }

  // ---- interruption ----------------------------------------------------------------------------

  static Stream<String> crashPoints() {
    return Stream.of(
        "recorded",
        "backup-confirmed",
        "apply-started",
        "step-0-started",
        "end-copied",
        "end-verified",
        "step-0-completed",
        "step-1-started",
        "end-file-deleted",
        "step-1-completed",
        "step-2-started",
        "season-ready",
        "step-2-completed",
        "step-3-started",
        "report-written",
        "step-3-completed",
        "validation-started",
        "check-0-passed",
        "check-1-passed",
        "check-2-passed",
        "check-3-passed",
        "check-4-passed");
  }

  /** A crash at {@code point}, then an explicit resume, ends exactly like an uninterrupted run. */
  @ParameterizedTest
  @MethodSource("crashPoints")
  void aCrashAnywhereResumesToTheSameResult(String point) throws Exception {
    ToolLocations l = ready();
    Map<String, String> outside = files(l.worldDir(), false);
    TransitionPreview preview = service().preview(l, request());
    boolean[] fired = {false};
    SeasonTransition crashing =
        service(
            at -> {
              if (at.equals(point) && !fired[0]) {
                fired[0] = true;
                throw new IOException("simulated crash at " + at);
              }
            });
    assertThatThrownBy(() -> crashing.apply(preview)).hasMessageContaining("simulated crash");
    assertThat(fired[0]).isTrue();
    assertThat(ToolSession.inspect(l, CLOCK).action(SessionAction.RESUME_OPERATION).available())
        .isTrue();

    assertThat(service().resume(l, preview.plan().id())).isEqualTo(preview.season());

    assertTransitioned(l, preview, outside);
  }

  // ---- rollback --------------------------------------------------------------------------------

  /**
   * A file appearing in the End while it is removed is a terminal failure before the commit: the
   * deleted files come back from the archive, verified, and nothing is committed.
   */
  @Test
  void aTerminalFailureBeforeTheCommitRestoresTheEnd() throws Exception {
    ToolLocations l = ready();
    Map<String, String> end = endFiles(l.worldDir());
    Path intruder = SeasonTransition.endPath(l).resolve("region/r.9.9.mca");
    TransitionPreview preview = service().preview(l, request());
    SeasonTransition racing =
        service(
            at -> {
              if (at.equals("end-file-deleted")) {
                Files.write(intruder, new byte[] {1});
              }
            });

    assertThatThrownBy(() -> racing.apply(preview))
        .extracting(e -> ((ToolException) e).code())
        .isEqualTo(TransitionIssueCodes.CHANGED);

    OperationId op = preview.plan().id();
    assertThat(state(l, op)).isEqualTo(OperationState.ROLLED_BACK);
    Map<String, String> restored = endFiles(l.worldDir());
    restored.remove(SeasonTransition.END_DIR + "/region/r.9.9.mca");
    assertThat(restored).isEqualTo(end);
    ArchiveState state = manifest(l).state();
    assertThat(state.season(preview.season())).isEmpty();
    assertThat(state.activeSeasonId()).isNull();
    assertThat(state.archivedDimensions()).isEmpty();
    // The verified archive stays recorded: a copy, never a loss.
    assertThat(BackupService.verify(l, SeasonTransition.archiveOf(op)).verified()).isTrue();
    assertThat(endRecords(l)).isEqualTo(16);
    assertThat(ToolSession.inspect(l, CLOCK).action(SessionAction.RESUME_OPERATION).available())
        .isFalse();
  }

  /** A rollback never overwrites: a changed End file stops it, and a retry finishes it. */
  @Test
  void aRollbackThatMeetsAChangedFileFailsWithoutOverwritingAndCanBeRetried() throws Exception {
    ToolLocations l = ready();
    Path fight = l.worldDir().resolve(FIGHT);
    TransitionPreview preview = service().preview(l, request());
    SeasonTransition tampering =
        service(
            at -> {
              if (at.equals("step-1-started")) {
                Files.write(fight, "tampered".getBytes(StandardCharsets.UTF_8));
              }
            });

    assertThatThrownBy(() -> tampering.apply(preview))
        .extracting(e -> ((ToolException) e).code())
        .isEqualTo(TransitionIssueCodes.ROLLBACK_CONFLICT);
    OperationId op = preview.plan().id();
    assertThat(state(l, op)).isEqualTo(OperationState.ROLLBACK_FAILED);
    assertThat(Files.readString(fight)).isEqualTo("tampered");

    Files.write(fight, FIGHT_BYTES);
    service().rollBack(l, op);
    assertThat(state(l, op)).isEqualTo(OperationState.ROLLED_BACK);
  }

  @Test
  void anInterruptedRollbackIsRetriedExplicitly() throws Exception {
    ToolLocations l = ready();
    Map<String, String> end = endFiles(l.worldDir());
    Path intruder = SeasonTransition.endPath(l).resolve("region/r.9.9.mca");
    TransitionPreview preview = service().preview(l, request());
    boolean[] crashed = {false};
    SeasonTransition failing =
        service(
            at -> {
              if (at.equals("end-file-deleted")) {
                Files.write(intruder, new byte[] {1});
              }
              if (at.equals("end-file-restored") && !crashed[0]) {
                crashed[0] = true;
                throw new IOException("simulated crash during rollback");
              }
            });
    assertThatThrownBy(() -> failing.apply(preview)).hasMessageContaining("during rollback");
    OperationId op = preview.plan().id();
    assertThat(state(l, op)).isEqualTo(OperationState.ROLLING_BACK);

    service().rollBack(l, op);

    assertThat(state(l, op)).isEqualTo(OperationState.ROLLED_BACK);
    Map<String, String> restored = endFiles(l.worldDir());
    restored.remove(SeasonTransition.END_DIR + "/region/r.9.9.mca");
    assertThat(restored).isEqualTo(end);
  }

  @Test
  void aCommittedTransitionCannotBeRolledBack() throws Exception {
    ToolLocations l = ready();
    TransitionPreview preview = service().preview(l, request());
    service().apply(preview);
    assertThatThrownBy(() -> service().rollBack(l, preview.plan().id()))
        .extracting(e -> ((ToolException) e).code())
        .isEqualTo(OperationRunner.ROLLBACK_REFUSED);
  }

  // ---- plan parameters -------------------------------------------------------------------------

  @Test
  void theNewSeasonRoundTripsThroughThePlan() throws Exception {
    ToolLocations l = ready();
    String longName = "Ω".repeat(30) + "🐉".repeat(34);
    SeasonProposal negative = new SeasonPlanner(PlannerSettings.DEFAULT).proposal(-20_480, -20_480);
    TransitionPreview preview =
        service().preview(l, new TransitionRequest(DisplayName.of(longName), negative, false));
    TransitionParameters p = TransitionParameters.of(preview.plan());
    assertThat(p.name().value()).isEqualTo(longName);
    assertThat(p.placement()).isEqualTo(negative);
    assertThat(p.season()).isEqualTo(preview.season());
  }

  // ---- helpers ---------------------------------------------------------------------------------

  private interface StateEdit {
    ArchiveState apply(ArchiveState state);
  }

  /** Commits a change to the archive state, as another MCOneWorld actor would. */
  private static void edit(ToolLocations l, StateEdit edit) throws Exception {
    SidecarStore store = store(l);
    try (SidecarWriter writer =
        store.openWriter(ToolInfo.LOCK_OWNER, CLOCK, StaleLockPolicy.REFUSE)) {
      ManifestDocument doc = store.readManifest().document();
      SidecarTransaction tx = writer.begin(doc.revision());
      tx.commit(doc.withState(edit.apply(doc.state())).nextRevision());
    }
  }

  private static void rebackup(ToolLocations l) throws IOException {
    BackupService backups = new BackupService(CLOCK, ProgressListener.NONE);
    backups.apply(backups.preview(l));
  }
}
