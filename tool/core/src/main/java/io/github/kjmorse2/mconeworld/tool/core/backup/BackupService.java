// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.backup;

import io.github.kjmorse2.mconeworld.domain.BackupId;
import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.domain.OperationId;
import io.github.kjmorse2.mconeworld.domain.archive.BackupRecord;
import io.github.kjmorse2.mconeworld.domain.operation.ActionKey;
import io.github.kjmorse2.mconeworld.domain.operation.BackupReference;
import io.github.kjmorse2.mconeworld.domain.operation.BackupRequirement;
import io.github.kjmorse2.mconeworld.domain.operation.ExpectedManifest;
import io.github.kjmorse2.mconeworld.domain.operation.ImpactClass;
import io.github.kjmorse2.mconeworld.domain.operation.InputKey;
import io.github.kjmorse2.mconeworld.domain.operation.OperationPlan;
import io.github.kjmorse2.mconeworld.domain.operation.OperationState;
import io.github.kjmorse2.mconeworld.domain.operation.OperationTarget;
import io.github.kjmorse2.mconeworld.domain.operation.OperationType;
import io.github.kjmorse2.mconeworld.domain.operation.PreconditionSnapshot;
import io.github.kjmorse2.mconeworld.domain.operation.RollbackDeclaration;
import io.github.kjmorse2.mconeworld.domain.operation.RollbackStrategy;
import io.github.kjmorse2.mconeworld.domain.operation.StepDescriptor;
import io.github.kjmorse2.mconeworld.domain.operation.ValidationDescriptor;
import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import io.github.kjmorse2.mconeworld.domain.validation.IssueLocation;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationIssue;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationResult;
import io.github.kjmorse2.mconeworld.sidecar.backup.BackupEntry;
import io.github.kjmorse2.mconeworld.sidecar.backup.BackupKind;
import io.github.kjmorse2.mconeworld.sidecar.backup.BackupManifest;
import io.github.kjmorse2.mconeworld.sidecar.backup.BackupRecords;
import io.github.kjmorse2.mconeworld.sidecar.backup.BackupVerification;
import io.github.kjmorse2.mconeworld.sidecar.backup.BackupVerifier;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestDocument;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestException;
import io.github.kjmorse2.mconeworld.sidecar.operation.JournalException;
import io.github.kjmorse2.mconeworld.sidecar.operation.OperationJournal;
import io.github.kjmorse2.mconeworld.sidecar.operation.OperationJournals;
import io.github.kjmorse2.mconeworld.sidecar.operation.OperationStatus;
import io.github.kjmorse2.mconeworld.sidecar.storage.BackupEntryPath;
import io.github.kjmorse2.mconeworld.sidecar.storage.BackupFileWriter;
import io.github.kjmorse2.mconeworld.sidecar.storage.BackupFiles;
import io.github.kjmorse2.mconeworld.sidecar.storage.Checksums;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarRoot;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarStore;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarTransaction;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarWriter;
import io.github.kjmorse2.mconeworld.sidecar.storage.StaleLockPolicy;
import io.github.kjmorse2.mconeworld.tool.core.ProgressListener;
import io.github.kjmorse2.mconeworld.tool.core.ToolException;
import io.github.kjmorse2.mconeworld.tool.core.ToolInfo;
import io.github.kjmorse2.mconeworld.tool.core.operation.OperationRunner;
import io.github.kjmorse2.mconeworld.tool.core.operation.OperationWork;
import io.github.kjmorse2.mconeworld.tool.core.session.ActionAvailability;
import io.github.kjmorse2.mconeworld.tool.core.session.SessionAction;
import io.github.kjmorse2.mconeworld.tool.core.session.SessionStatus;
import io.github.kjmorse2.mconeworld.tool.core.session.ToolLocations;
import io.github.kjmorse2.mconeworld.tool.core.session.ToolSession;
import io.github.kjmorse2.mconeworld.tool.world.WorldInspection;
import io.github.kjmorse2.mconeworld.tool.world.WorldInspector;
import io.github.kjmorse2.mconeworld.tool.world.lock.SessionLockProbe;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Full backups (operation {@code mconeworld:backup}; ADR 0012): {@link #preview} sizes and plans
 * one without writing; {@link #apply} copies, verifies, and records it; {@link #resume} continues
 * an interrupted one; {@link #verify} re-checks a recorded backup at any time.
 *
 * <p>What is copied: every regular file of the world except {@code session.lock}, and every file of
 * the sidecar except its {@code backups/} area, its lock files, its staging area, and the running
 * backup's own journal. Symbolic links are never followed: one in the world refuses the backup.
 *
 * <p>The single step is retry-safe: a partial copy of this backup, left by an interruption, is
 * removed and the copy starts again. The world must not change while it is copied: its paths,
 * sizes, and modification times are pinned in the plan and checked again after the copy.
 */
public final class BackupService {
  /** The operation type. */
  public static final OperationType TYPE = OperationType.of("mconeworld:backup");

  static final InputKey WORLD_FILES = InputKey.of("world:files");
  static final InputKey SIDECAR_FILES = InputKey.of("sidecar:files");

  /** Headroom over the copy's size: 5 % plus 64 MiB. */
  public static long required(long bytes) {
    return Math.addExact(Math.addExact(bytes, bytes / 20), 64L << 20);
  }

  /** Free space on the filesystem holding a directory; a seam for tests. */
  @FunctionalInterface
  public interface SpaceProbe {
    SpaceProbe FILESYSTEM = dir -> Files.getFileStore(dir).getUsableSpace();

    long usableBytes(Path dir) throws IOException;
  }

  private final Clock clock;
  private final ProgressListener progress;
  private final OperationRunner.Hook hook;
  private final SpaceProbe space;

  public BackupService(Clock clock, ProgressListener progress) {
    this(clock, progress, OperationRunner.Hook.NONE, SpaceProbe.FILESYSTEM);
  }

  BackupService(
      Clock clock, ProgressListener progress, OperationRunner.Hook hook, SpaceProbe space) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.progress = Objects.requireNonNull(progress, "progress");
    this.hook = Objects.requireNonNull(hook, "hook");
    this.space = Objects.requireNonNull(space, "space");
  }

  /** The backup an operation writes: its id is the operation's id. */
  public static BackupId backupOf(OperationId operation) {
    return BackupId.of(operation.value());
  }

  // ---- preview ---------------------------------------------------------------------------------

  /**
   * Lists and sizes everything to copy, checks free space, and plans the backup. Writes nothing.
   *
   * @throws ToolException {@code NOT_ALLOWED}, {@code UNSUPPORTED_FILE}, or {@code
   *     INSUFFICIENT_SPACE}, each with its findings
   */
  public BackupPreview preview(ToolLocations locations) throws IOException {
    SessionStatus status = requireAllowed(locations);
    OperationId id = OperationId.of(UUID.randomUUID());
    FileTree world = worldTree(locations);
    FileTree sidecar = sidecarTree(locations, id);
    List<ValidationIssue> problems = new ArrayList<>(world.issues());
    problems.addAll(sidecar.issues());
    for (FileTree.Entry e : world.files()) {
      if (!BackupEntryPath.isValid(BackupEntryPath.WORLD + "/" + e.path())) {
        problems.add(
            ValidationIssue.error(
                BackupIssueCodes.UNSUPPORTED_FILE,
                IssueLocation.root().key(e.path()),
                "A backup cannot hold this file name: " + e.path()));
      }
    }
    if (!problems.isEmpty()) {
      throw new ToolException(
          BackupIssueCodes.UNSUPPORTED_FILE,
          "Some files cannot be backed up",
          ValidationResult.of(problems),
          null);
    }
    long total = Math.addExact(world.totalBytes(), sidecar.totalBytes());
    long usable = space.usableBytes(locations.sidecarDir());
    requireSpace(total, usable);
    ManifestDocument doc = status.sidecar().manifest().orElseThrow().document();
    OperationPlan plan =
        new OperationPlan(
            id,
            TYPE,
            clock.instant(),
            ToolInfo.IDENTITY,
            doc.archiveId(),
            ImpactClass.SIDECAR_MUTATING,
            List.of(new OperationTarget.SidecarManifest()),
            preconditions(locations, world, sidecar),
            new BackupRequirement.NotRequired(
                OperationRunner.text("This operation is the backup; it changes only the sidecar.")),
            List.of(
                new StepDescriptor(
                    ActionKey.of("mconeworld:copy_verify_record"),
                    OperationRunner.text(
                        "Copy "
                            + world.files().size()
                            + " world files and "
                            + sidecar.files().size()
                            + " sidecar files ("
                            + total
                            + " bytes) into backups/"
                            + backupOf(id)
                            + "/, verify every byte, then record the backup"),
                    true,
                    List.of(0))),
            List.of(
                new ValidationDescriptor(
                    ActionKey.of("mconeworld:backup_verifies"),
                    OperationRunner.text("The recorded backup verifies against its manifest")),
                new ValidationDescriptor(
                    ActionKey.of("mconeworld:world_unchanged"),
                    OperationRunner.text("The world did not change during the backup"))),
            new RollbackDeclaration(
                RollbackStrategy.NOT_APPLICABLE,
                OperationRunner.text(
                    "Nothing in the world changes. An unrecorded partial backup can be deleted explicitly.")));
    return new BackupPreview(
        locations,
        plan,
        world.files().size(),
        sidecar.files().size(),
        total,
        required(total),
        usable);
  }

  // ---- apply, resume, verify -------------------------------------------------------------------

  /**
   * Copies, verifies, and records {@code preview}'s backup.
   *
   * @return the recorded backup
   * @throws ToolException {@code NOT_ALLOWED}, {@code CHANGED}, {@code INSUFFICIENT_SPACE}, or
   *     {@code VALIDATION_FAILED}
   * @throws IOException if interrupted; {@link #resume} continues it
   */
  public BackupRecord apply(BackupPreview preview) throws IOException {
    SessionStatus status = requireAllowed(preview.locations());
    SidecarStore store = SidecarStore.open(SidecarRoot.open(preview.locations().sidecarDir()));
    try (SidecarWriter writer =
        store.openWriter(ToolInfo.LOCK_OWNER, clock, StaleLockPolicy.REFUSE)) {
      OperationJournal journal = OperationJournals.of(store, clock).create(writer, preview.plan());
      hook.at("recorded");
      return run(writer, journal, preview.locations(), status.world());
    }
  }

  /**
   * Continues the interrupted backup {@code operation}.
   *
   * @throws ToolException {@code NOT_RESUMABLE}, or as {@link #apply}
   */
  public BackupRecord resume(ToolLocations locations, OperationId operation) throws IOException {
    WorldInspection world = WorldInspector.inspect(locations.worldDir());
    if (!world.lock().allowsWrites()) {
      throw new ToolException(
          BackupIssueCodes.NOT_ALLOWED, "Minecraft has the world open; stop the server first");
    }
    SidecarStore store = SidecarStore.open(SidecarRoot.open(locations.sidecarDir()));
    try (SidecarWriter writer =
        store.openWriter(ToolInfo.LOCK_OWNER, clock, StaleLockPolicy.REFUSE)) {
      OperationJournal journal = OperationJournals.of(store, clock).open(writer, operation);
      if (!journal.progress().plan().type().equals(TYPE)) {
        throw new ToolException(
            BackupIssueCodes.NOT_RESUMABLE, "Operation " + operation + " is not a backup");
      }
      return run(writer, journal, locations, world);
    }
  }

  /**
   * Re-reads every file of the recorded backup {@code id} and compares it with its manifest.
   * Read-only.
   */
  public static BackupVerification verify(ToolLocations locations, BackupId id) throws IOException {
    SidecarStore store = SidecarStore.open(SidecarRoot.open(locations.sidecarDir()));
    BackupRecord record =
        manifest(store).state().backups().stream()
            .filter(b -> b.id().equals(id))
            .findFirst()
            .orElseThrow(
                () ->
                    new ToolException(
                        BackupIssueCodes.VALIDATION_FAILED, "No backup " + id + " is recorded"));
    return new BackupVerifier().verify(store.backupFiles(), id, record.checksum());
  }

  private BackupRecord run(
      SidecarWriter writer,
      OperationJournal journal,
      ToolLocations locations,
      WorldInspection world)
      throws IOException {
    OperationPlan plan = journal.progress().plan();
    SidecarStore store = writer.store();
    BackupId id = backupOf(plan.id());
    new OperationRunner(hook)
        .run(
            journal,
            new OperationWork() {
              @Override
              public @Nullable BackupReference confirmedBackup() {
                return null;
              }

              @Override
              public PreconditionSnapshot observe() throws IOException {
                return preconditions(
                    locations, worldTree(locations), sidecarTree(locations, plan.id()));
              }

              @Override
              public void perform(int step) throws IOException {
                copyVerifyRecord(plan, writer, locations, world);
              }

              @Override
              public @Nullable String check(int check) throws IOException {
                if (check == 0) {
                  BackupVerification v = verify(locations, id);
                  if (!v.verified()) {
                    return "The backup does not verify: " + v.issues().issues().getFirst();
                  }
                  return null;
                }
                Checksum pinned = plan.preconditions().checksums().get(WORLD_FILES);
                if (!worldTree(locations).fingerprint().equals(pinned)) {
                  return "The world changed during the backup";
                }
                return null;
              }

              @Override
              public boolean isTerminal(IssueCode code) {
                return code.equals(BackupIssueCodes.CHANGED)
                    || code.equals(BackupIssueCodes.VALIDATION_FAILED);
              }
            },
            BackupIssueCodes.CHANGED,
            BackupIssueCodes.VALIDATION_FAILED,
            BackupIssueCodes.NOT_RESUMABLE);
    return manifest(store).state().backups().stream()
        .filter(b -> b.id().equals(id))
        .findFirst()
        .orElseThrow();
  }

  private void copyVerifyRecord(
      OperationPlan plan, SidecarWriter writer, ToolLocations locations, WorldInspection world)
      throws IOException {
    SidecarStore store = writer.store();
    BackupId id = backupOf(plan.id());
    ManifestDocument doc = manifest(store);
    if (doc.state().backups().stream().anyMatch(b -> b.id().equals(id))) {
      return; // recorded by an earlier attempt
    }
    FileTree worldFiles = worldTree(locations);
    FileTree sidecarFiles = sidecarTree(locations, plan.id());
    if (!worldFiles.fingerprint().equals(plan.preconditions().checksums().get(WORLD_FILES))) {
      throw new ToolException(BackupIssueCodes.CHANGED, "The world changed since planning");
    }
    requireSpace(
        Math.addExact(worldFiles.totalBytes(), sidecarFiles.totalBytes()),
        space.usableBytes(locations.sidecarDir()));
    long total = Math.addExact(worldFiles.totalBytes(), sidecarFiles.totalBytes());
    List<BackupEntry> entries = new ArrayList<>();
    try (BackupFileWriter out = writer.openBackupWriter(id)) {
      if (store.backupFiles().exists(id)) {
        out.deletePartial(); // this operation's own interrupted copy
      }
      out.createDirectory();
      long[] done = {0};
      for (FileTree.Entry e : worldFiles.files()) {
        entries.add(copy(out, locations.worldDir(), e, BackupEntryPath.WORLD, done, total));
      }
      for (FileTree.Entry e : sidecarFiles.files()) {
        entries.add(copy(out, locations.sidecarDir(), e, BackupEntryPath.SIDECAR, done, total));
      }
      out.syncDirectories();
      hook.at("copied");
    }
    if (!worldTree(locations).fingerprint().equals(worldFiles.fingerprint())) {
      throw new ToolException(BackupIssueCodes.CHANGED, "The world changed while it was copied");
    }
    BackupManifest manifest =
        new BackupManifest(
            id,
            BackupKind.FULL,
            clock.instant(),
            null,
            world.level().dataVersion().isPresent() ? world.level().dataVersion().getAsInt() : null,
            doc.revision(),
            entries);
    BackupVerification payload = new BackupVerifier().verifyPayload(store.backupFiles(), manifest);
    if (!payload.verified()) {
      throw new ToolException(
          BackupIssueCodes.VALIDATION_FAILED, "The copy does not verify", payload.issues(), null);
    }
    hook.at("verified");
    SidecarTransaction tx = writer.begin(doc.revision());
    ManifestDocument next =
        BackupRecords.stageAndRecord(tx, doc, payload, plan.id(), doc.state().activeSeasonId());
    tx.commit(next.nextRevision());
  }

  private BackupEntry copy(
      BackupFileWriter out, Path base, FileTree.Entry e, String area, long[] done, long total)
      throws IOException {
    long start = done[0];
    return FileCopy.copy(
        out,
        base.resolve(e.path()),
        BackupEntryPath.of(area + "/" + e.path()),
        copied -> {
          done[0] = start + copied;
          progress.progress("Copying " + e.path(), done[0], total);
        });
  }

  // ---- helpers ---------------------------------------------------------------------------------

  /** Every file of the world a backup copies: all but {@code session.lock}. */
  public static FileTree worldTree(ToolLocations locations) throws IOException {
    return FileTree.walk(locations.worldDir(), path -> path.equals(SessionLockProbe.FILE_NAME));
  }

  /**
   * The newest recorded full backup that holds the world exactly as it is now: its backup operation
   * completed, and the world's paths, sizes, and modification times equal those it pinned.
   * Read-only; the backup's bytes are not re-verified here (see {@link #verify}).
   */
  public static Optional<BackupRecord> currentFullBackup(ToolLocations locations)
      throws IOException {
    SidecarStore store = SidecarStore.open(SidecarRoot.open(locations.sidecarDir()));
    Checksum now = worldTree(locations).fingerprint();
    OperationJournals journals = OperationJournals.of(store, Clock.systemUTC());
    List<BackupRecord> newestFirst = new ArrayList<>(manifest(store).state().backups());
    newestFirst.sort(Comparator.comparing(BackupRecord::createdAt).reversed());
    for (BackupRecord record : newestFirst) {
      OperationId operation = record.operation();
      if (operation == null || !backupOf(operation).equals(record.id())) {
        continue;
      }
      OperationStatus status;
      try {
        status = journals.inspect(operation);
      } catch (JournalException e) {
        continue; // its journal is gone: the backup cannot be matched to a world state
      }
      if (status.plan() != null
          && status.progress() != null
          && status.plan().plan().type().equals(TYPE)
          && status.progress().state() == OperationState.COMPLETED
          && now.equals(status.plan().plan().preconditions().checksums().get(WORLD_FILES))) {
        return Optional.of(record);
      }
    }
    return Optional.empty();
  }

  /**
   * The sidecar files a backup holds: all but backups, locks, staging, and this operation's own
   * journal.
   */
  private static FileTree sidecarTree(ToolLocations locations, OperationId running)
      throws IOException {
    Set<String> skipped =
        Set.of(
            BackupFiles.AREA,
            SidecarRoot.LOCK,
            SidecarRoot.LOCK_OWNER,
            SidecarRoot.LOCK_OWNER + ".tmp",
            SidecarRoot.STAGING);
    String own = SidecarRoot.OPERATIONS + "/" + running;
    Predicate<String> excluded = path -> skipped.contains(path) || path.equals(own);
    return FileTree.walk(locations.sidecarDir(), excluded);
  }

  private static PreconditionSnapshot preconditions(
      ToolLocations locations, FileTree world, FileTree sidecar) throws IOException {
    SidecarStore store = SidecarStore.open(SidecarRoot.open(locations.sidecarDir()));
    byte[] bytes = store.readManifestBytes();
    TreeMap<InputKey, Checksum> checksums = new TreeMap<>();
    checksums.put(WORLD_FILES, world.fingerprint());
    checksums.put(SIDECAR_FILES, sidecar.fingerprint());
    return new PreconditionSnapshot(
        new ExpectedManifest.AtRevision(
            manifest(store).revision(),
            Checksums.sha256(new ByteArrayInputStream(bytes), bytes.length).checksum()),
        checksums,
        new TreeMap<>());
  }

  private static void requireSpace(long bytes, long usable) throws ToolException {
    long required = required(bytes);
    if (usable < required) {
      throw new ToolException(
          BackupIssueCodes.INSUFFICIENT_SPACE,
          "The backup needs "
              + required
              + " bytes free on the sidecar's disk; "
              + usable
              + " are available",
          ValidationResult.of(
              List.of(
                  ValidationIssue.error(
                          BackupIssueCodes.INSUFFICIENT_SPACE,
                          IssueLocation.root(),
                          "Not enough free space for the backup")
                      .withRemediation("Free space, or move the sidecar to a larger disk."))),
          null);
    }
  }

  private SessionStatus requireAllowed(ToolLocations locations) throws IOException {
    SessionStatus status = ToolSession.inspect(locations, clock);
    ActionAvailability action = status.action(SessionAction.BACKUP);
    if (!action.available()) {
      throw new ToolException(
          BackupIssueCodes.NOT_ALLOWED,
          "A backup cannot be made now",
          ValidationResult.of(action.blockers()),
          null);
    }
    return status;
  }

  private static ManifestDocument manifest(SidecarStore store) throws IOException {
    try {
      return store.readManifest().document();
    } catch (ManifestException e) {
      throw new ToolException(
          BackupIssueCodes.CHANGED, "The manifest cannot be read", e.issues(), e);
    }
  }
}
