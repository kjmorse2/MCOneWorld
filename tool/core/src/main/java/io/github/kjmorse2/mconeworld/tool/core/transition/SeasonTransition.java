// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.transition;

import io.github.kjmorse2.mconeworld.domain.BackupId;
import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.OperationId;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.archive.ArchiveState;
import io.github.kjmorse2.mconeworld.domain.archive.ArchivedDimension;
import io.github.kjmorse2.mconeworld.domain.archive.BackupRecord;
import io.github.kjmorse2.mconeworld.domain.archive.WorldBinding;
import io.github.kjmorse2.mconeworld.domain.coordinate.BlockPosition;
import io.github.kjmorse2.mconeworld.domain.coordinate.DimensionOrigin;
import io.github.kjmorse2.mconeworld.domain.operation.ActionKey;
import io.github.kjmorse2.mconeworld.domain.operation.BackupReference;
import io.github.kjmorse2.mconeworld.domain.operation.BackupRequirement;
import io.github.kjmorse2.mconeworld.domain.operation.ExpectedManifest;
import io.github.kjmorse2.mconeworld.domain.operation.ImpactClass;
import io.github.kjmorse2.mconeworld.domain.operation.InputKey;
import io.github.kjmorse2.mconeworld.domain.operation.OperationPlan;
import io.github.kjmorse2.mconeworld.domain.operation.OperationTarget;
import io.github.kjmorse2.mconeworld.domain.operation.OperationType;
import io.github.kjmorse2.mconeworld.domain.operation.PreconditionSnapshot;
import io.github.kjmorse2.mconeworld.domain.operation.RollbackDeclaration;
import io.github.kjmorse2.mconeworld.domain.operation.RollbackStrategy;
import io.github.kjmorse2.mconeworld.domain.operation.StepDescriptor;
import io.github.kjmorse2.mconeworld.domain.operation.ValidationDescriptor;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProtection;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProvenance;
import io.github.kjmorse2.mconeworld.domain.provenance.ProtectedRegion;
import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import io.github.kjmorse2.mconeworld.domain.validation.IssueLocation;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationIssue;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationResult;
import io.github.kjmorse2.mconeworld.sidecar.backup.BackupEntry;
import io.github.kjmorse2.mconeworld.sidecar.backup.BackupFormatException;
import io.github.kjmorse2.mconeworld.sidecar.backup.BackupKind;
import io.github.kjmorse2.mconeworld.sidecar.backup.BackupManifest;
import io.github.kjmorse2.mconeworld.sidecar.backup.BackupManifestCodec;
import io.github.kjmorse2.mconeworld.sidecar.backup.BackupRecords;
import io.github.kjmorse2.mconeworld.sidecar.backup.BackupVerification;
import io.github.kjmorse2.mconeworld.sidecar.backup.BackupVerifier;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkCursor;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexBatch;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexExport;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexReader;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexSnapshot;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexState;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexStore;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkMutation;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkQuery;
import io.github.kjmorse2.mconeworld.sidecar.index.SidecarChunkIndex;
import io.github.kjmorse2.mconeworld.sidecar.manifest.FileReference;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestDocument;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestException;
import io.github.kjmorse2.mconeworld.sidecar.operation.OperationJournal;
import io.github.kjmorse2.mconeworld.sidecar.operation.OperationJournals;
import io.github.kjmorse2.mconeworld.sidecar.storage.BackupEntryPath;
import io.github.kjmorse2.mconeworld.sidecar.storage.BackupFileWriter;
import io.github.kjmorse2.mconeworld.sidecar.storage.Checksums;
import io.github.kjmorse2.mconeworld.sidecar.storage.JdkStorageFileSystem;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarPath;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarRoot;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarStore;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarTransaction;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarWriter;
import io.github.kjmorse2.mconeworld.sidecar.storage.StagedFile;
import io.github.kjmorse2.mconeworld.sidecar.storage.StaleLockPolicy;
import io.github.kjmorse2.mconeworld.tool.core.ProgressListener;
import io.github.kjmorse2.mconeworld.tool.core.ToolException;
import io.github.kjmorse2.mconeworld.tool.core.ToolInfo;
import io.github.kjmorse2.mconeworld.tool.core.backup.BackupService;
import io.github.kjmorse2.mconeworld.tool.core.backup.FileCopy;
import io.github.kjmorse2.mconeworld.tool.core.backup.FileTree;
import io.github.kjmorse2.mconeworld.tool.core.inventory.IndexComparison;
import io.github.kjmorse2.mconeworld.tool.core.inventory.WorldChunks;
import io.github.kjmorse2.mconeworld.tool.core.operation.OperationRunner;
import io.github.kjmorse2.mconeworld.tool.core.operation.OperationWork;
import io.github.kjmorse2.mconeworld.tool.core.reconcile.Reconciler;
import io.github.kjmorse2.mconeworld.tool.core.season.PlannerSettings;
import io.github.kjmorse2.mconeworld.tool.core.season.SeasonPlanner;
import io.github.kjmorse2.mconeworld.tool.core.season.SeasonProposal;
import io.github.kjmorse2.mconeworld.tool.core.session.ActionAvailability;
import io.github.kjmorse2.mconeworld.tool.core.session.SessionAction;
import io.github.kjmorse2.mconeworld.tool.core.session.SessionStatus;
import io.github.kjmorse2.mconeworld.tool.core.session.ToolLocations;
import io.github.kjmorse2.mconeworld.tool.core.session.ToolSession;
import io.github.kjmorse2.mconeworld.tool.world.WorldInspection;
import io.github.kjmorse2.mconeworld.tool.world.WorldInspector;
import io.github.kjmorse2.mconeworld.tool.world.layout.DimensionDirectory;
import io.github.kjmorse2.mconeworld.tool.world.lock.SessionLockProbe;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Creates the next season (operation {@code mconeworld:season_transition}; ADRs 0010 and 0011):
 * {@link #preview} plans it without writing; {@link #apply} records and carries it out; {@link
 * #resume} continues an interrupted one; {@link #rollBack} retries an interrupted rollback.
 *
 * <p>It may start only when the session allows a new season (server stopped, pinned version, 26.1+
 * layout, clean sidecar, nothing pending), the index matches the world, the placement is valid, and
 * a recorded full backup holds the world exactly as it is now. That backup is re-verified before
 * anything changes.
 *
 * <p>Steps, each safe to retry:
 *
 * <ol>
 *   <li>{@value #ARCHIVE_KEY}: copy every file of {@value #END_DIR} into a {@code
 *       DIMENSION_ARCHIVE} backup with this operation's id, verify it, and record it
 *   <li>{@value #REMOVE_KEY}: delete each End file whose bytes equal its archived copy, then the
 *       emptied directories. Any other file there stops the operation.
 *   <li>{@value #COMMIT_KEY}: in one manifest transaction, append the new season with its origins
 *       and planned spawn, activate it pending application by the server (archiving the previous
 *       one), and record the archived End. <b>This is the commit point.</b>
 *   <li>{@value #RECORDS_KEY}: write the End's index records to a report, then remove them
 * </ol>
 *
 * The first two run only when the world has End files. Before the commit, a terminal failure
 * restores the End from the archive ({@link OperationWork#rollback}); after it, the season stands
 * and the full backup is the way back.
 *
 * <p>The Tool never writes {@code level.dat}, player data, or another dimension: every file outside
 * the End is pinned in the plan and checked unchanged afterwards.
 */
public final class SeasonTransition {
  /** The operation type. */
  public static final OperationType TYPE = OperationType.of("mconeworld:season_transition");

  /** The End's directory in the 26.1+ layout, relative to the world. */
  public static final String END_DIR = "dimensions/minecraft/the_end";

  /** The role of the End-records report in the manifest's {@code files}. */
  public static final String REPORT_ROLE = "archived-records";

  static final String ARCHIVE_KEY = "mconeworld:archive_end";
  static final String REMOVE_KEY = "mconeworld:remove_end_files";
  static final String COMMIT_KEY = "mconeworld:commit_season";
  static final String RECORDS_KEY = "mconeworld:remove_end_records";

  static final String CHECK_END = "mconeworld:end_archived";
  static final String CHECK_OUTSIDE = "mconeworld:world_outside_end_unchanged";
  static final String CHECK_SEASON = "mconeworld:season_active_pending";
  static final String CHECK_INDEX = "mconeworld:index_matches_world";
  static final String CHECK_VERIFIES = "mconeworld:index_verifies";
  static final String CHECK_REPORT = "mconeworld:report_complete";

  static final InputKey WORLD_FILES = InputKey.of("world:files");
  static final InputKey OUTSIDE_END = InputKey.of("world:files_outside_end");
  static final InputKey END_FILES = InputKey.of("world:end_files");
  static final InputKey SEED = InputKey.of("world:seed");
  static final InputKey DATA_VERSION = InputKey.of("minecraft:data_version");
  static final InputKey END_RECORDS = InputKey.of("mconeworld:end_records");

  private static final int BATCH = 32_768;
  private static final long MAX_ARCHIVE_MANIFEST = 64L << 20;

  private final Clock clock;
  private final ProgressListener progress;
  private final OperationRunner.Hook hook;
  private final BackupService.SpaceProbe space;
  private final SeasonPlanner planner;
  private final PlannerSettings settings;

  public SeasonTransition(Clock clock, ProgressListener progress) {
    this(
        clock,
        progress,
        OperationRunner.Hook.NONE,
        BackupService.SpaceProbe.FILESYSTEM,
        PlannerSettings.DEFAULT);
  }

  SeasonTransition(
      Clock clock,
      ProgressListener progress,
      OperationRunner.Hook hook,
      BackupService.SpaceProbe space,
      PlannerSettings settings) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.progress = Objects.requireNonNull(progress, "progress");
    this.hook = Objects.requireNonNull(hook, "hook");
    this.space = Objects.requireNonNull(space, "space");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.planner = new SeasonPlanner(settings);
  }

  /** The End archive an operation writes: its id is the operation's id. */
  public static BackupId archiveOf(OperationId operation) {
    return BackupService.backupOf(operation);
  }

  /** The End-records report's sidecar path for {@code operation}. */
  public static SidecarPath reportPath(OperationId operation) {
    return SidecarPath.of("reports/season-transition-" + operation + ".jsonl");
  }

  // ---- preview ---------------------------------------------------------------------------------

  /**
   * Checks everything and plans the transition. Writes nothing.
   *
   * @throws ToolException {@code NOT_ALLOWED}, {@code RECONCILE_NEEDED}, {@code ORIGIN_INVALID},
   *     {@code END_UNSUPPORTED}, {@code PROTECTED_END}, {@code BACKUP_NEEDED}, or {@code
   *     INSUFFICIENT_SPACE}, each with its findings
   */
  public TransitionPreview preview(ToolLocations locations, TransitionRequest request)
      throws IOException {
    SessionStatus status = requireAllowed(locations);
    WorldInspection world = status.world();
    ManifestDocument doc = status.sidecar().manifest().orElseThrow().document();
    ArchiveState archive = doc.state();
    SidecarStore store = open(locations);
    WorldChunks chunks = WorldChunks.collect(world, progress);

    IndexComparison.Counter counter = new IndexComparison.Counter();
    ChunkIndexState state;
    long[] end;
    try (ChunkIndexSnapshot reader = ChunkIndexStore.of(store).openReader()) {
      state = reader.state();
      IndexComparison.compare(chunks, reader, counter);
      end = endRecords(reader, archive.protectedRegions());
    }
    if (!counter.same()) {
      throw refusal(
          TransitionIssueCodes.RECONCILE_NEEDED,
          "The chunk index differs from the world: "
              + counter.added()
              + " chunks unrecorded, "
              + counter.removed()
              + " records of missing chunks",
          "Run Reconcile first.");
    }

    ValidationResult placement = placement(request.placement(), chunks, archive);
    if (!placement.isValid()) {
      throw new ToolException(
          TransitionIssueCodes.ORIGIN_INVALID,
          "The new season's placement is not valid",
          placement,
          null);
    }

    FileTree worldFiles = BackupService.worldTree(locations);
    FileTree endFiles = endTree(locations, world);
    List<ValidationIssue> unsupported = new ArrayList<>(worldFiles.issues());
    for (FileTree.Entry e : endFiles.files()) {
      if (!BackupEntryPath.isValid(archived(e.path()))) {
        unsupported.add(
            ValidationIssue.error(
                TransitionIssueCodes.END_UNSUPPORTED,
                IssueLocation.root().key(END_DIR + "/" + e.path()),
                "An archive cannot hold this file name: " + e.path()));
      }
    }
    if (!unsupported.isEmpty()) {
      throw new ToolException(
          TransitionIssueCodes.END_UNSUPPORTED,
          "The world holds files the transition cannot archive or check",
          ValidationResult.of(unsupported),
          null);
    }
    if (end[1] > 0 && !request.acknowledgeProtectedEnd()) {
      throw refusal(
          TransitionIssueCodes.PROTECTED_END,
          end[1] + " protected End chunks would be archived out of the world",
          "Confirm that the protected End chunks may be archived; they stay restorable from the"
              + " archive.");
    }

    BackupRecord backup =
        BackupService.currentFullBackup(locations)
            .orElseThrow(
                () ->
                    refusal(
                        TransitionIssueCodes.BACKUP_NEEDED,
                        "No recorded full backup holds the world as it is now",
                        "Make a backup, then plan the season again."));

    long usable = space.usableBytes(locations.sidecarDir());
    requireSpace(endFiles.totalBytes(), usable);

    boolean archivesEnd = !endFiles.files().isEmpty();
    TransitionParameters parameters =
        new TransitionParameters(
            SeasonId.of(UUID.randomUUID()), request.name(), request.placement(), archivesEnd);
    SeasonId previous = archive.activeSeasonId();
    OperationPlan plan =
        plan(
            doc,
            parameters,
            previous,
            backup,
            endFiles,
            end[0],
            observed(locations, world, store, worldFiles, state, end[0]));
    return new TransitionPreview(
        locations,
        plan,
        request,
        parameters.season(),
        previous,
        backup,
        endFiles.files().size(),
        endFiles.totalBytes(),
        end[0],
        end[1]);
  }

  /** The placement rules: the planner's, a paired Nether origin, and a spawn in the clear area. */
  private ValidationResult placement(
      SeasonProposal placement, WorldChunks chunks, ArchiveState archive) {
    DimensionOrigin overworld = placement.overworld();
    if (!overworld.dimension().equals(DimensionKey.OVERWORLD)) {
      return invalid("The Overworld origin is in " + overworld.dimension());
    }
    ValidationResult rules = planner.validate(overworld, chunks, archive);
    if (!rules.isValid()) {
      return rules;
    }
    SeasonProposal paired = planner.proposal(overworld.x(), overworld.z());
    if (!placement.nether().equals(paired.nether())) {
      return invalid(
          "The Nether origin must be the Overworld origin divided by 8: " + paired.nether());
    }
    BlockPosition spawn = placement.spawn();
    long half = settings.clearanceBlocks();
    if (!spawn.dimension().equals(DimensionKey.OVERWORLD)
        || Math.abs(spawn.x() - overworld.x()) > half
        || Math.abs(spawn.z() - overworld.z()) > half) {
      return invalid("The spawn must be in the Overworld, within the clear area around the origin");
    }
    return ValidationResult.ok();
  }

  private static ValidationResult invalid(String message) {
    return ValidationResult.of(
        List.of(
            ValidationIssue.error(
                TransitionIssueCodes.ORIGIN_INVALID,
                IssueLocation.root().field("placement"),
                message)));
  }

  private OperationPlan plan(
      ManifestDocument doc,
      TransitionParameters parameters,
      @Nullable SeasonId previous,
      BackupRecord backup,
      FileTree endFiles,
      long endRecords,
      PreconditionSnapshot preconditions) {
    List<OperationTarget> targets = new ArrayList<>(parameters.targets());
    int endTarget = TransitionParameters.archivedTarget(targets);
    int manifestTarget = targets.size();
    targets.add(new OperationTarget.SidecarManifest());
    List<Integer> commitTargets = new ArrayList<>(List.of(0, manifestTarget));
    if (previous != null) {
      commitTargets.add(targets.size());
      targets.add(new OperationTarget.Season(previous));
    }
    OperationId id = OperationId.of(UUID.randomUUID());
    String name = "\"" + parameters.name().value() + "\"";
    List<StepDescriptor> steps = new ArrayList<>();
    List<ValidationDescriptor> checks = new ArrayList<>();
    if (parameters.archivesEnd()) {
      steps.add(
          new StepDescriptor(
              ActionKey.of(ARCHIVE_KEY),
              OperationRunner.text(
                  "Copy the End's "
                      + endFiles.files().size()
                      + " files ("
                      + endFiles.totalBytes()
                      + " bytes) into backups/"
                      + archiveOf(id)
                      + "/, verify every byte, then record the archive"),
              true,
              List.of(endTarget)));
      steps.add(
          new StepDescriptor(
              ActionKey.of(REMOVE_KEY),
              OperationRunner.text(
                  "Delete each End file that equals its archived copy, then the End directory"),
              true,
              List.of(endTarget)));
      checks.add(
          new ValidationDescriptor(
              ActionKey.of(CHECK_END),
              OperationRunner.text("The End directory is gone and its archive verifies")));
    }
    SeasonProposal p = parameters.placement();
    steps.add(
        new StepDescriptor(
            ActionKey.of(COMMIT_KEY),
            OperationRunner.text(
                "Append season "
                    + name
                    + " with origins Overworld ("
                    + p.overworld().x()
                    + ", "
                    + p.overworld().z()
                    + ") and Nether ("
                    + p.nether().x()
                    + ", "
                    + p.nether().z()
                    + "), and activate it pending application by the server"
                    + (previous == null ? "" : "; season " + previous + " is archived")),
            true,
            commitTargets));
    if (parameters.archivesEnd()) {
      steps.add(
          new StepDescriptor(
              ActionKey.of(RECORDS_KEY),
              OperationRunner.text(
                  "Report the End's " + endRecords + " index records, then remove them"),
              true,
              List.of(manifestTarget)));
    }
    checks.add(
        new ValidationDescriptor(
            ActionKey.of(CHECK_OUTSIDE),
            OperationRunner.text("Every file outside the End is unchanged")));
    checks.add(
        new ValidationDescriptor(
            ActionKey.of(CHECK_SEASON),
            OperationRunner.text(
                "Season " + name + " is active, pending application, and the archive validates")));
    checks.add(
        new ValidationDescriptor(
            ActionKey.of(CHECK_INDEX),
            OperationRunner.text("The index records exactly the world's chunks")));
    checks.add(
        new ValidationDescriptor(
            ActionKey.of(CHECK_VERIFIES), OperationRunner.text("The chunk index verifies")));
    if (parameters.archivesEnd()) {
      checks.add(
          new ValidationDescriptor(
              ActionKey.of(CHECK_REPORT),
              OperationRunner.text("The report holds every removed End record")));
    }
    return new OperationPlan(
        id,
        TYPE,
        clock.instant(),
        ToolInfo.IDENTITY,
        doc.archiveId(),
        ImpactClass.WORLD_MUTATING,
        targets,
        preconditions,
        new BackupRequirement.ExistingBackup(new BackupReference(backup.id(), backup.checksum())),
        steps,
        checks,
        new RollbackDeclaration(
            RollbackStrategy.INVERSE_STEPS,
            OperationRunner.text(
                "Before the season is committed, the End's files are restored from this"
                    + " operation's verified archive. After it, restore full backup "
                    + backup.id()
                    + " by hand.")));
  }

  /**
   * The preconditions: the manifest's exact bytes, the world's files (all, outside the End, and the
   * End's), the seed, the data version, the index state, and the End's record count.
   */
  private static PreconditionSnapshot observed(
      ToolLocations locations,
      WorldInspection world,
      SidecarStore store,
      FileTree worldFiles,
      ChunkIndexState state,
      long endRecords)
      throws IOException {
    TreeMap<InputKey, Checksum> checksums = new TreeMap<>();
    checksums.put(WORLD_FILES, worldFiles.fingerprint());
    checksums.put(OUTSIDE_END, outsideEnd(locations).fingerprint());
    checksums.put(END_FILES, endTree(locations, world).fingerprint());
    if (world.level().seed().isPresent()) {
      checksums.put(SEED, WorldBinding.seedChecksum(world.level().seed().getAsLong()));
    }
    checksums.put(ChunkIndexState.INPUT_KEY, state.pin());
    TreeMap<InputKey, String> facts = new TreeMap<>();
    if (world.level().dataVersion().isPresent()) {
      facts.put(DATA_VERSION, String.valueOf(world.level().dataVersion().getAsInt()));
    }
    facts.put(END_RECORDS, String.valueOf(endRecords));
    byte[] bytes = store.readManifestBytes();
    return new PreconditionSnapshot(
        new ExpectedManifest.AtRevision(
            manifest(store).revision(),
            Checksums.sha256(new ByteArrayInputStream(bytes), bytes.length).checksum()),
        checksums,
        facts);
  }

  // ---- apply, resume, rollback -----------------------------------------------------------------

  /**
   * Re-verifies the full backup, then records and carries out {@code preview}'s plan.
   *
   * @return the new season's id
   * @throws ToolException {@code NOT_ALLOWED}, {@code BACKUP_NEEDED}, {@code CHANGED}, {@code
   *     ARCHIVE_UNVERIFIED}, or {@code VALIDATION_FAILED}; after a terminal failure before the
   *     commit, the End has been restored (unless the rollback itself failed)
   * @throws IOException if interrupted; {@link #resume} continues it
   */
  public SeasonId apply(TransitionPreview preview) throws IOException {
    requireAllowed(preview.locations());
    requireBackupVerifies(preview.locations(), preview.backup().id());
    SidecarStore store = open(preview.locations());
    try (SidecarWriter writer =
        store.openWriter(ToolInfo.LOCK_OWNER, clock, StaleLockPolicy.REFUSE)) {
      OperationJournal journal = OperationJournals.of(store, clock).create(writer, preview.plan());
      hook.at("recorded");
      run(writer, journal, preview.locations(), true);
    }
    return preview.season();
  }

  /**
   * Continues the interrupted transition {@code operation}.
   *
   * @throws ToolException {@code NOT_RESUMABLE}, or as {@link #apply}
   */
  public SeasonId resume(ToolLocations locations, OperationId operation) throws IOException {
    requireServerStopped(locations);
    SidecarStore store = open(locations);
    try (SidecarWriter writer =
        store.openWriter(ToolInfo.LOCK_OWNER, clock, StaleLockPolicy.REFUSE)) {
      OperationJournal journal = openTransition(writer, operation);
      run(writer, journal, locations, false);
      return TransitionParameters.of(journal.progress().plan()).season();
    }
  }

  /**
   * Rolls back the failed transition {@code operation}, or retries an interrupted or failed
   * rollback: restores the End from the operation's archive. Refused once the season is committed.
   *
   * @throws ToolException {@code NOT_RESUMABLE}, {@link OperationRunner#ROLLBACK_REFUSED}, {@code
   *     ROLLBACK_CONFLICT}, or {@code ARCHIVE_UNVERIFIED}
   */
  public void rollBack(ToolLocations locations, OperationId operation) throws IOException {
    requireServerStopped(locations);
    SidecarStore store = open(locations);
    try (SidecarWriter writer =
        store.openWriter(ToolInfo.LOCK_OWNER, clock, StaleLockPolicy.REFUSE)) {
      OperationJournal journal = openTransition(writer, operation);
      new OperationRunner(hook).rollBack(journal, work(writer, journal, locations, false));
    }
  }

  private OperationJournal openTransition(SidecarWriter writer, OperationId operation)
      throws IOException {
    OperationJournal journal = OperationJournals.of(writer.store(), clock).open(writer, operation);
    if (!journal.progress().plan().type().equals(TYPE)) {
      throw new ToolException(
          TransitionIssueCodes.NOT_RESUMABLE, "Operation " + operation + " is not a transition");
    }
    return journal;
  }

  private void run(
      SidecarWriter writer, OperationJournal journal, ToolLocations locations, boolean verified)
      throws IOException {
    new OperationRunner(hook)
        .run(
            journal,
            work(writer, journal, locations, verified),
            TransitionIssueCodes.CHANGED,
            TransitionIssueCodes.VALIDATION_FAILED,
            TransitionIssueCodes.NOT_RESUMABLE);
  }

  private OperationWork work(
      SidecarWriter writer,
      OperationJournal journal,
      ToolLocations locations,
      boolean backupVerified) {
    OperationPlan plan = journal.progress().plan();
    TransitionParameters parameters;
    try {
      parameters = TransitionParameters.of(plan);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("Transition plan " + plan.id() + " is malformed", e);
    }
    SidecarStore store = writer.store();
    return new OperationWork() {
      @Override
      public BackupReference confirmedBackup() throws IOException {
        BackupReference reference = ((BackupRequirement.ExistingBackup) plan.backup()).backup();
        if (!backupVerified) {
          requireBackupVerifies(locations, reference.id());
        }
        return reference;
      }

      @Override
      public PreconditionSnapshot observe() throws IOException {
        WorldInspection world = WorldInspector.inspect(locations.worldDir());
        try (ChunkIndexSnapshot reader = ChunkIndexStore.of(store).openReader()) {
          long[] end = endRecords(reader, manifest(store).state().protectedRegions());
          return observed(
              locations, world, store, BackupService.worldTree(locations), reader.state(), end[0]);
        }
      }

      @Override
      public void perform(int step) throws IOException {
        switch (plan.steps().get(step).action().value()) {
          case ARCHIVE_KEY -> archiveEnd(plan, writer, locations);
          case REMOVE_KEY -> removeEnd(plan, store, locations);
          case COMMIT_KEY -> commitSeason(plan, parameters, writer);
          case RECORDS_KEY -> removeEndRecords(plan, writer);
          default -> throw new IllegalStateException("Unknown step");
        }
      }

      @Override
      public @Nullable String check(int check) throws IOException {
        return SeasonTransition.this.check(plan, parameters, store, locations, check);
      }

      @Override
      public boolean isTerminal(IssueCode code) {
        return code.equals(TransitionIssueCodes.CHANGED)
            || code.equals(TransitionIssueCodes.ARCHIVE_UNVERIFIED);
      }

      @Override
      public boolean canRollBack() throws IOException {
        return manifest(store).state().season(parameters.season()).isEmpty();
      }

      @Override
      public void rollback() throws IOException {
        restoreEnd(plan, store, locations);
      }
    };
  }

  // ---- steps -----------------------------------------------------------------------------------

  private void archiveEnd(OperationPlan plan, SidecarWriter writer, ToolLocations locations)
      throws IOException {
    SidecarStore store = writer.store();
    BackupId id = archiveOf(plan.id());
    ManifestDocument doc = manifest(store);
    if (recorded(doc, id) != null) {
      return; // recorded by an earlier attempt
    }
    WorldInspection world = WorldInspector.inspect(locations.worldDir());
    FileTree end = endTree(locations, world);
    if (!end.fingerprint().equals(plan.preconditions().checksums().get(END_FILES))) {
      throw new ToolException(TransitionIssueCodes.CHANGED, "The End changed since planning");
    }
    requireSpace(end.totalBytes(), space.usableBytes(locations.sidecarDir()));
    Path root = endPath(locations);
    List<BackupEntry> entries = new ArrayList<>();
    long total = end.totalBytes();
    long[] done = {0};
    try (BackupFileWriter out = writer.openBackupWriter(id)) {
      if (store.backupFiles().exists(id)) {
        out.deletePartial(); // this operation's own interrupted copy
      }
      out.createDirectory();
      for (FileTree.Entry e : end.files()) {
        long start = done[0];
        entries.add(
            FileCopy.copy(
                out,
                root.resolve(e.path()),
                entryPath(e.path()),
                copied -> {
                  done[0] = start + copied;
                  progress.progress("Archiving the End: " + e.path(), done[0], total);
                }));
      }
      out.syncDirectories();
      hook.at("end-copied");
    }
    if (!endTree(locations, world).fingerprint().equals(end.fingerprint())) {
      throw new ToolException(TransitionIssueCodes.CHANGED, "The End changed while it was copied");
    }
    BackupManifest manifest =
        new BackupManifest(
            id,
            BackupKind.DIMENSION_ARCHIVE,
            clock.instant(),
            DimensionKey.THE_END,
            world.level().dataVersion().isPresent() ? world.level().dataVersion().getAsInt() : null,
            null,
            entries);
    BackupVerification payload = new BackupVerifier().verifyPayload(store.backupFiles(), manifest);
    if (!payload.verified()) {
      throw new ToolException(
          TransitionIssueCodes.ARCHIVE_UNVERIFIED,
          "The End archive does not verify",
          payload.issues(),
          null);
    }
    hook.at("end-verified");
    SidecarTransaction tx = writer.begin(doc.revision());
    ManifestDocument next =
        BackupRecords.stageAndRecord(tx, doc, payload, plan.id(), doc.state().activeSeasonId());
    tx.commit(next.nextRevision());
  }

  /**
   * Deletes every End file that is byte-identical to its archived copy, then the emptied
   * directories. A file the archive does not hold, or one that differs, stops the operation before
   * anything more is deleted.
   */
  private void removeEnd(OperationPlan plan, SidecarStore store, ToolLocations locations)
      throws IOException {
    BackupManifest archive = archiveManifest(store, plan.id());
    Path root = endPath(locations);
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
      return; // removed by an earlier attempt
    }
    Map<String, BackupEntry> archived = byEndPath(archive);
    FileTree now = FileTree.walk(root, path -> false);
    if (!now.issues().isEmpty()) {
      throw new ToolException(
          TransitionIssueCodes.CHANGED,
          "The End now holds a link or special file",
          ValidationResult.of(now.issues()),
          null);
    }
    for (FileTree.Entry e : now.files()) {
      BackupEntry copy = archived.get(e.path());
      if (copy == null) {
        throw new ToolException(
            TransitionIssueCodes.CHANGED, "The End gained a file after archiving: " + e.path());
      }
      Path file = root.resolve(e.path());
      if (e.size() != copy.sizeBytes() || !FileCopy.sha256(file).equals(copy.checksum())) {
        throw new ToolException(
            TransitionIssueCodes.CHANGED, "An End file changed after archiving: " + e.path());
      }
    }
    int deleted = 0;
    for (FileTree.Entry e : now.files()) {
      Files.delete(root.resolve(e.path()));
      if (deleted++ == 0) {
        hook.at("end-file-deleted");
      }
    }
    List<Path> directories;
    try (Stream<Path> walk = Files.walk(root)) {
      // Only directories: a file not verified above is never deleted, and it stops the removal.
      directories =
          walk.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
              .sorted(Comparator.comparingInt(Path::getNameCount).reversed())
              .toList();
    }
    for (Path dir : directories) {
      try {
        Files.delete(dir);
      } catch (DirectoryNotEmptyException e) {
        throw new ToolException(
            TransitionIssueCodes.CHANGED, "A file appeared in the End during removal: " + dir);
      }
    }
    syncDirectory(Objects.requireNonNull(root.getParent()));
  }

  private void commitSeason(
      OperationPlan plan, TransitionParameters parameters, SidecarWriter writer)
      throws IOException {
    SidecarStore store = writer.store();
    ManifestDocument doc = manifest(store);
    ArchiveState state = doc.state();
    if (state.season(parameters.season()).isPresent()) {
      return; // committed by an earlier attempt
    }
    SeasonId previous = state.activeSeasonId();
    ArchiveState next;
    try {
      next =
          state
              .appendSeason(parameters.record(plan.createdAt()))
              .activateForApplication(parameters.season());
      if (parameters.archivesEnd()) {
        BackupId archive = archiveOf(plan.id());
        if (recorded(doc, archive) == null) {
          throw new ToolException(TransitionIssueCodes.CHANGED, "The End archive is not recorded");
        }
        next =
            next.withArchivedDimension(
                new ArchivedDimension(
                    DimensionKey.THE_END, previous, plan.id(), archive, clock.instant()));
      }
    } catch (RuntimeException e) {
      throw new ToolException(
          TransitionIssueCodes.CHANGED, "The season cannot be committed: " + e.getMessage());
    }
    hook.at("season-ready");
    SidecarTransaction tx = writer.begin(doc.revision());
    tx.commit(doc.withState(next).nextRevision());
  }

  /** Reports the End's records (once), then removes whichever are still in the index. */
  private void removeEndRecords(OperationPlan plan, SidecarWriter writer) throws IOException {
    SidecarStore store = writer.store();
    ManifestDocument doc = manifest(store);
    SidecarPath path = reportPath(plan.id());
    if (doc.files().stream().noneMatch(f -> f.path().equals(path))) {
      writeReport(plan, writer, doc, path);
      hook.at("report-written");
    }
    try (SidecarChunkIndex index = ChunkIndexStore.of(store).openWriter(writer)) {
      ChunkIndexBatch batch = index.begin();
      int pending = 0;
      try (ChunkIndexSnapshot reader = index.reader();
          ChunkCursor cursor = reader.scan(ChunkQuery.all(DimensionKey.THE_END))) {
        for (ChunkProvenance r = cursor.next(); r != null; r = cursor.next()) {
          batch.apply(new ChunkMutation.Remove(r.chunk()));
          if (++pending >= BATCH) {
            batch.commit();
            batch.close();
            hook.at("index-batch");
            batch = index.begin();
            pending = 0;
          }
        }
        batch.commit();
      } finally {
        batch.close();
      }
      index.checkpoint();
    }
  }

  private static void writeReport(
      OperationPlan plan, SidecarWriter writer, ManifestDocument doc, SidecarPath path)
      throws IOException {
    SidecarStore store = writer.store();
    Path temp = Files.createTempFile("mconeworld-transition-", ".jsonl");
    try {
      long count = 0;
      try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp));
          ChunkIndexSnapshot reader = ChunkIndexStore.of(store).openReader();
          ChunkCursor cursor = reader.scan(ChunkQuery.all(DimensionKey.THE_END))) {
        for (ChunkProvenance r = cursor.next(); r != null; r = cursor.next()) {
          out.write(ChunkIndexExport.recordLine(r));
          out.write('\n');
          count++;
        }
      }
      if (count != Long.parseLong(plan.preconditions().facts().getOrDefault(END_RECORDS, "0"))) {
        throw new ToolException(
            TransitionIssueCodes.CHANGED, "The End's index records differ from the plan");
      }
      byte[] header =
          ("{\"format\":\""
                  + Reconciler.REPORT_FORMAT
                  + "\",\"operation\":\""
                  + plan.id()
                  + "\",\"removed\":"
                  + count
                  + ",\"version\":\"1.0\"}\n")
              .getBytes(StandardCharsets.UTF_8);
      SidecarTransaction tx = writer.begin(doc.revision());
      StagedFile staged;
      try (InputStream in =
          new SequenceInputStream(new ByteArrayInputStream(header), Files.newInputStream(temp))) {
        staged = tx.stage(path, in, header.length + Files.size(temp));
      }
      tx.commit(
          doc.withFile(
                  new FileReference(
                      REPORT_ROLE,
                      DimensionKey.THE_END,
                      staged.target(),
                      staged.checksum(),
                      staged.sizeBytes()))
              .nextRevision());
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  // ---- rollback --------------------------------------------------------------------------------

  /**
   * Puts back every archived End file that is missing, through a temporary file and an atomic
   * rename, each verified against the archive. A file that exists must equal its archived copy;
   * nothing is ever overwritten. Without a recorded archive nothing was deleted, so there is
   * nothing to restore.
   */
  private void restoreEnd(OperationPlan plan, SidecarStore store, ToolLocations locations)
      throws IOException {
    BackupId id = archiveOf(plan.id());
    BackupRecord record = recorded(manifest(store), id);
    if (record == null) {
      return;
    }
    BackupVerification verification =
        new BackupVerifier().verify(store.backupFiles(), id, record.checksum());
    if (!verification.verified()) {
      throw new ToolException(
          TransitionIssueCodes.ARCHIVE_UNVERIFIED,
          "The End archive no longer verifies; restore the full backup instead",
          verification.issues(),
          null);
    }
    BackupManifest archive = Objects.requireNonNull(verification.manifest());
    Path root = endPath(locations);
    String suffix = ".mconeworld-restore-" + plan.id();
    for (BackupEntry entry : archive.entries()) {
      Path target = root.resolve(endRelative(entry.path()));
      if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
            && Files.size(target) == entry.sizeBytes()
            && FileCopy.sha256(target).equals(entry.checksum())) {
          continue; // never deleted, or restored by an earlier attempt
        }
        throw new ToolException(
            TransitionIssueCodes.ROLLBACK_CONFLICT,
            "The End file "
                + endRelative(entry.path())
                + " differs from its archived copy; nothing was overwritten");
      }
      Path dir = Objects.requireNonNull(target.getParent());
      Files.createDirectories(dir);
      Path temp = dir.resolve(target.getFileName() + suffix);
      Files.deleteIfExists(temp); // this rollback's own interrupted copy
      try (InputStream in = store.backupFiles().open(id, entry.path());
          FileChannel out =
              FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
        in.transferTo(Channels.newOutputStream(out));
        out.force(true);
      }
      if (!FileCopy.sha256(temp).equals(entry.checksum())) {
        throw new ToolException(
            TransitionIssueCodes.ARCHIVE_UNVERIFIED,
            "A restored End file does not match the archive: " + endRelative(entry.path()));
      }
      Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
      syncDirectory(dir);
      hook.at("end-file-restored");
    }
  }

  // ---- checks ----------------------------------------------------------------------------------

  private @Nullable String check(
      OperationPlan plan,
      TransitionParameters parameters,
      SidecarStore store,
      ToolLocations locations,
      int check)
      throws IOException {
    ManifestDocument doc = manifest(store);
    return switch (plan.validations().get(check).check().value()) {
      case CHECK_END -> {
        if (Files.exists(endPath(locations), LinkOption.NOFOLLOW_LINKS)) {
          yield "The End directory still exists";
        }
        BackupId id = archiveOf(plan.id());
        BackupRecord record = recorded(doc, id);
        if (record == null) {
          yield "The End archive is not recorded";
        }
        BackupVerification v =
            new BackupVerifier().verify(store.backupFiles(), id, record.checksum());
        yield v.verified() ? null : "The End archive does not verify: " + v.issues().issues();
      }
      case CHECK_OUTSIDE ->
          outsideEnd(locations)
                  .fingerprint()
                  .equals(plan.preconditions().checksums().get(OUTSIDE_END))
              ? null
              : "Files outside the End changed during the transition";
      case CHECK_SEASON -> {
        ArchiveState state = doc.state();
        if (!parameters.season().equals(state.activeSeasonId()) || !state.isActivationPending()) {
          yield "Season " + parameters.season() + " is not active and pending application";
        }
        if (parameters.archivesEnd()
            && state.archivedDimensions().stream()
                .noneMatch(
                    a ->
                        a.operation().equals(plan.id())
                            && a.dimension().equals(DimensionKey.THE_END)
                            && a.backup().equals(archiveOf(plan.id())))) {
          yield "The archived End is not recorded";
        }
        ValidationResult valid = state.validate();
        yield valid.isValid() ? null : "The archive does not validate: " + valid.issues();
      }
      case CHECK_INDEX -> {
        WorldChunks chunks =
            WorldChunks.collect(WorldInspector.inspect(locations.worldDir()), progress);
        IndexComparison.Counter counter = new IndexComparison.Counter();
        try (ChunkIndexSnapshot reader = ChunkIndexStore.of(store).openReader()) {
          IndexComparison.compare(chunks, reader, counter);
        }
        yield counter.same()
            ? null
            : "The index differs from the world: "
                + counter.added()
                + " missing, "
                + counter.removed()
                + " extra";
      }
      case CHECK_VERIFIES -> {
        ValidationResult result = ChunkIndexStore.of(store).verify();
        yield result.isValid() ? null : "The chunk index does not verify: " + result.issues();
      }
      case CHECK_REPORT -> {
        SidecarPath path = reportPath(plan.id());
        if (doc.files().stream().noneMatch(f -> f.path().equals(path))) {
          yield "The manifest does not reference the End-records report";
        }
        long lines;
        try (var reader =
            Files.newBufferedReader(store.root().resolve(path, JdkStorageFileSystem.INSTANCE))) {
          lines = reader.lines().count();
        }
        long planned = Long.parseLong(plan.preconditions().facts().getOrDefault(END_RECORDS, "0"));
        yield lines == planned + 1
            ? null
            : "The report holds " + (lines - 1) + " records, not " + planned;
      }
      default -> throw new IllegalStateException("Unknown check");
    };
  }

  // ---- helpers ---------------------------------------------------------------------------------

  static Path endPath(ToolLocations locations) {
    return locations.worldDir().resolve(END_DIR);
  }

  /**
   * The End's files. The End must be exactly {@value #END_DIR} (the verified 26.1+ layout); a world
   * that has not generated the End yet has none.
   */
  private static FileTree endTree(ToolLocations locations, WorldInspection world)
      throws IOException {
    Path root = endPath(locations);
    DimensionDirectory detected = world.layout().dimension(DimensionKey.THE_END).orElse(null);
    if (detected != null
        && !detected
            .root()
            .toAbsolutePath()
            .normalize()
            .equals(root.toAbsolutePath().normalize())) {
      throw refusal(
          TransitionIssueCodes.END_UNSUPPORTED,
          "The End is at " + detected.root() + ", not " + END_DIR,
          "Only the 26.1+ vanilla layout is supported.");
    }
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
      return new FileTree(root, List.of(), List.of());
    }
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
      throw refusal(
          TransitionIssueCodes.END_UNSUPPORTED,
          END_DIR + " is not a plain directory",
          "Replace the link or file with the End's directory.");
    }
    return FileTree.walk(root, path -> false);
  }

  /** Every world file but {@code session.lock} and the End's. */
  private static FileTree outsideEnd(ToolLocations locations) throws IOException {
    return FileTree.walk(
        locations.worldDir(),
        path -> path.equals(SessionLockProbe.FILE_NAME) || path.equals(END_DIR));
  }

  private static BackupEntryPath entryPath(String endRelative) {
    return BackupEntryPath.of(archived(endRelative));
  }

  private static String archived(String endRelative) {
    return BackupEntryPath.WORLD + "/" + END_DIR + "/" + endRelative;
  }

  /** The End-relative path of an archive entry. */
  private static String endRelative(BackupEntryPath entry) throws ToolException {
    String prefix = BackupEntryPath.WORLD + "/" + END_DIR + "/";
    if (!entry.value().startsWith(prefix)) {
      throw new ToolException(
          TransitionIssueCodes.ARCHIVE_UNVERIFIED, "Unexpected archive entry " + entry.value());
    }
    return entry.value().substring(prefix.length());
  }

  private static Map<String, BackupEntry> byEndPath(BackupManifest archive) throws ToolException {
    Map<String, BackupEntry> map = new HashMap<>();
    for (BackupEntry e : archive.entries()) {
      map.put(endRelative(e.path()), e);
    }
    return map;
  }

  /** The recorded archive's manifest, verified against its recorded checksum. */
  private static BackupManifest archiveManifest(SidecarStore store, OperationId operation)
      throws IOException {
    BackupId id = archiveOf(operation);
    BackupRecord record = recorded(manifest(store), id);
    if (record == null) {
      throw new ToolException(TransitionIssueCodes.CHANGED, "The End archive is not recorded");
    }
    byte[] bytes = store.backupFiles().readManifest(id, MAX_ARCHIVE_MANIFEST);
    if (!Checksums.sha256(new ByteArrayInputStream(bytes), bytes.length)
        .checksum()
        .equals(record.checksum())) {
      throw new ToolException(
          TransitionIssueCodes.ARCHIVE_UNVERIFIED, "The End archive's manifest changed");
    }
    try {
      return new BackupManifestCodec().decode(bytes);
    } catch (BackupFormatException e) {
      throw new ToolException(
          TransitionIssueCodes.ARCHIVE_UNVERIFIED, "The End archive's manifest is unreadable");
    }
  }

  private static @Nullable BackupRecord recorded(ManifestDocument doc, BackupId id) {
    return doc.state().backups().stream().filter(b -> b.id().equals(id)).findFirst().orElse(null);
  }

  /** The End's record count and how many of them are protected. */
  private static long[] endRecords(ChunkIndexReader reader, List<ProtectedRegion> regions)
      throws IOException {
    long[] counts = new long[2];
    try (ChunkCursor cursor = reader.scan(ChunkQuery.all(DimensionKey.THE_END))) {
      for (ChunkProvenance r = cursor.next(); r != null; r = cursor.next()) {
        counts[0]++;
        ChunkProvenance record = r;
        if (record.protection() == ChunkProtection.PROTECTED
            || regions.stream().anyMatch(region -> region.protects(record.chunk()))) {
          counts[1]++;
        }
      }
    }
    return counts;
  }

  private static void syncDirectory(Path dir) throws IOException {
    JdkStorageFileSystem.INSTANCE.syncDirectory(dir);
  }

  private void requireBackupVerifies(ToolLocations locations, BackupId id) throws IOException {
    progress.progress("Verifying backup " + id, 0, 1);
    BackupVerification v = BackupService.verify(locations, id);
    if (!v.verified()) {
      throw new ToolException(
          TransitionIssueCodes.BACKUP_NEEDED,
          "Backup " + id + " does not verify; make a new one and plan again",
          v.issues(),
          null);
    }
    progress.progress("Verifying backup " + id, 1, 1);
  }

  private static void requireSpace(long bytes, long usable) throws ToolException {
    long required = BackupService.required(bytes);
    if (usable < required) {
      throw refusal(
          TransitionIssueCodes.INSUFFICIENT_SPACE,
          "The End archive needs " + required + " bytes free; " + usable + " are available",
          "Free space, or move the sidecar to a larger disk.");
    }
  }

  private static void requireServerStopped(ToolLocations locations) throws IOException {
    if (!WorldInspector.inspect(locations.worldDir()).lock().allowsWrites()) {
      throw new ToolException(
          TransitionIssueCodes.NOT_ALLOWED, "Minecraft has the world open; stop the server first");
    }
  }

  private SessionStatus requireAllowed(ToolLocations locations) throws IOException {
    SessionStatus status = ToolSession.inspect(locations, clock);
    ActionAvailability action = status.action(SessionAction.NEW_SEASON);
    if (!action.available()) {
      throw new ToolException(
          TransitionIssueCodes.NOT_ALLOWED,
          "A new season cannot be created now",
          ValidationResult.of(action.blockers()),
          null);
    }
    return status;
  }

  private static ToolException refusal(IssueCode code, String message, String remediation) {
    return new ToolException(
        code,
        message,
        ValidationResult.of(
            List.of(
                ValidationIssue.error(code, IssueLocation.root(), message)
                    .withRemediation(remediation))),
        null);
  }

  private static SidecarStore open(ToolLocations locations) throws IOException {
    return SidecarStore.open(SidecarRoot.open(locations.sidecarDir()));
  }

  private static ManifestDocument manifest(SidecarStore store) throws IOException {
    try {
      return store.readManifest().document();
    } catch (ManifestException e) {
      throw new ToolException(
          TransitionIssueCodes.CHANGED, "The manifest cannot be read", e.issues(), e);
    }
  }
}
