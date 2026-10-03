// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.reconcile;

import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.OperationId;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.archive.WorldBinding;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
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
import io.github.kjmorse2.mconeworld.domain.provenance.Confidence;
import io.github.kjmorse2.mconeworld.domain.provenance.EvidenceSource;
import io.github.kjmorse2.mconeworld.domain.provenance.FirstObservation;
import io.github.kjmorse2.mconeworld.domain.provenance.ProtectedRegion;
import io.github.kjmorse2.mconeworld.domain.provenance.ProvenanceEvidence;
import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import io.github.kjmorse2.mconeworld.domain.validation.IssueLocation;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationIssue;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationResult;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexBatch;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexExport;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexSnapshot;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexState;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexStore;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkMutation;
import io.github.kjmorse2.mconeworld.sidecar.index.SidecarChunkIndex;
import io.github.kjmorse2.mconeworld.sidecar.manifest.FileReference;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestDocument;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestException;
import io.github.kjmorse2.mconeworld.sidecar.operation.OperationJournal;
import io.github.kjmorse2.mconeworld.sidecar.operation.OperationJournals;
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
import io.github.kjmorse2.mconeworld.tool.core.inventory.IndexComparison;
import io.github.kjmorse2.mconeworld.tool.core.inventory.WorldChunks;
import io.github.kjmorse2.mconeworld.tool.core.operation.OperationRunner;
import io.github.kjmorse2.mconeworld.tool.core.operation.OperationWork;
import io.github.kjmorse2.mconeworld.tool.core.session.ActionAvailability;
import io.github.kjmorse2.mconeworld.tool.core.session.SessionAction;
import io.github.kjmorse2.mconeworld.tool.core.session.SessionStatus;
import io.github.kjmorse2.mconeworld.tool.core.session.ToolLocations;
import io.github.kjmorse2.mconeworld.tool.core.session.ToolSession;
import io.github.kjmorse2.mconeworld.tool.world.WorldInspection;
import io.github.kjmorse2.mconeworld.tool.world.WorldInspector;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Brings the chunk index in line with the world: {@link #preview} plans it without writing; {@link
 * #apply} records and carries it out; {@link #resume} continues an interrupted one.
 *
 * <p>Steps, each safe to retry:
 *
 * <ol>
 *   <li>{@value #REPORT_KEY}: write every record about to be removed, in full, to {@code
 *       reports/reconcile-<operation>.jsonl} (referenced by the manifest), and record the world
 *       binding if the sidecar had none. History is never lost without a trace.
 *   <li>{@value #APPLY_KEY}: record each chunk the world gained, and remove each record of a chunk
 *       it lost. A gained chunk is in-season for the active season (low confidence: inferred from
 *       an offline scan), or legacy if no season is active.
 * </ol>
 *
 * Checks: the index now matches the world exactly; the index verifies; the report holds exactly the
 * planned removals.
 */
public final class Reconciler {
  /** The operation type. */
  public static final OperationType TYPE = OperationType.of("mconeworld:reconcile");

  static final String REPORT_KEY = "mconeworld:write_reconcile_report";
  static final String APPLY_KEY = "mconeworld:apply_index_changes";

  /** The report format, a header line then one export record line per removed record. */
  public static final String REPORT_FORMAT = "mconeworld-reconcile-report";

  static final InputKey SEED = InputKey.of("world:seed");
  static final InputKey INVENTORY = InputKey.of("world:region_inventory");
  static final InputKey ADDITIONS = InputKey.of("mconeworld:planned_additions");
  static final InputKey REMOVALS = InputKey.of("mconeworld:planned_removals");

  static final int BATCH = 32_768;

  private static final ProvenanceEvidence SCANNED =
      new ProvenanceEvidence(EvidenceSource.OFFLINE_SCAN, Confidence.LOW);

  private final Clock clock;
  private final ProgressListener progress;
  private final OperationRunner.Hook hook;
  private final int batchSize;

  public Reconciler(Clock clock, ProgressListener progress) {
    this(clock, progress, OperationRunner.Hook.NONE, BATCH);
  }

  Reconciler(Clock clock, ProgressListener progress, OperationRunner.Hook hook, int batchSize) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.progress = Objects.requireNonNull(progress, "progress");
    this.hook = Objects.requireNonNull(hook, "hook");
    this.batchSize = batchSize;
  }

  /** The report's sidecar path for {@code operation}. */
  public static SidecarPath reportPath(OperationId operation) {
    return SidecarPath.of("reports/reconcile-" + operation + ".jsonl");
  }

  // ---- preview ---------------------------------------------------------------------------------

  /**
   * Compares the world with the index and plans the reconcile. Writes nothing.
   *
   * @param acknowledgeProtectedRemovals the user confirmed that records of protected chunks the
   *     world no longer has may be removed (they are reported first)
   * @throws ToolException {@code NOT_ALLOWED}, or {@code PROTECTED_REMOVAL} listing the protected
   *     chunks when they are not acknowledged
   */
  public ReconcilePreview preview(ToolLocations locations, boolean acknowledgeProtectedRemovals)
      throws IOException {
    SessionStatus status = requireAllowed(locations);
    WorldInspection world = status.world();
    ManifestDocument doc = status.sidecar().manifest().orElseThrow().document();
    SidecarStore store = SidecarStore.open(SidecarRoot.open(locations.sidecarDir()));
    WorldChunks chunks = WorldChunks.collect(world, progress);
    DiffCollector diff = new DiffCollector(doc.state().protectedRegions());
    ChunkIndexState state;
    try (ChunkIndexSnapshot reader = ChunkIndexStore.of(store).openReader()) {
      state = reader.state();
      IndexComparison.compare(chunks, reader, diff);
    }
    boolean bindsWorld = doc.state().worldBinding() == null;
    ReconcileDiff result = diff.result(bindsWorld);
    if (result.protectedRemovedTotal() > 0 && !acknowledgeProtectedRemovals) {
      List<ValidationIssue> issues = new ArrayList<>();
      for (ChunkPosition chunk : result.protectedRemoved()) {
        issues.add(
            ValidationIssue.error(
                ReconcileIssueCodes.PROTECTED_REMOVAL,
                IssueLocation.root().key(chunk.dimension() + " " + chunk.x() + " " + chunk.z()),
                "Protected chunk "
                    + chunk.x()
                    + ", "
                    + chunk.z()
                    + " in "
                    + chunk.dimension()
                    + " is gone from the world"));
      }
      throw new ToolException(
          ReconcileIssueCodes.PROTECTED_REMOVAL,
          result.protectedRemovedTotal()
              + " protected chunks are gone from the world; confirm to remove their records",
          ValidationResult.of(issues),
          null);
    }
    OperationPlan plan =
        plan(doc, store, world, chunks, state, result, acknowledgeProtectedRemovals);
    return new ReconcilePreview(locations, plan, result);
  }

  private OperationPlan plan(
      ManifestDocument doc,
      SidecarStore store,
      WorldInspection world,
      WorldChunks chunks,
      ChunkIndexState state,
      ReconcileDiff diff,
      boolean acknowledged)
      throws IOException {
    List<OperationTarget> targets = new ArrayList<>();
    targets.add(new OperationTarget.SidecarManifest());
    SeasonId active = doc.state().activeSeasonId();
    String gained =
        active == null
            ? "as legacy (no season is active)"
            : "as first seen in active season " + active + " (low confidence)";
    String protectedNote =
        diff.protectedRemovedTotal() == 0
            ? ""
            : "; "
                + diff.protectedRemovedTotal()
                + " of them protected, removal acknowledged: "
                + acknowledged;
    OperationId id = OperationId.of(UUID.randomUUID());
    return new OperationPlan(
        id,
        TYPE,
        clock.instant(),
        ToolInfo.IDENTITY,
        doc.archiveId(),
        ImpactClass.SIDECAR_MUTATING,
        targets,
        observed(store, world, chunks, state, diff.added(), diff.removed()),
        new BackupRequirement.NotRequired(
            OperationRunner.text(
                "Only the sidecar changes, and every removed record is written to a report first.")),
        List.of(
            new StepDescriptor(
                ActionKey.of(REPORT_KEY),
                OperationRunner.text(
                    "Report the "
                        + diff.removed()
                        + " records about to be removed"
                        + (diff.bindsWorld() ? ", and bind the sidecar to this world" : "")),
                true,
                List.of(0)),
            new StepDescriptor(
                ActionKey.of(APPLY_KEY),
                OperationRunner.text(
                    "Record "
                        + diff.added()
                        + " gained chunks "
                        + gained
                        + "; remove "
                        + diff.removed()
                        + " records of lost chunks"
                        + protectedNote),
                true,
                List.of(0))),
        List.of(
            new ValidationDescriptor(
                ActionKey.of("mconeworld:index_matches_world"),
                OperationRunner.text("The index records exactly the world's chunks")),
            new ValidationDescriptor(
                ActionKey.of("mconeworld:index_verifies"),
                OperationRunner.text("The chunk index verifies")),
            new ValidationDescriptor(
                ActionKey.of("mconeworld:report_complete"),
                OperationRunner.text("The report holds every removed record"))),
        new RollbackDeclaration(
            RollbackStrategy.NOT_APPLICABLE,
            OperationRunner.text(
                "The world is untouched; removed records are kept in the report and can be re-added from it.")));
  }

  /**
   * The preconditions: the manifest, the world's headers and seed, the index state, and the diff's
   * size.
   */
  private static PreconditionSnapshot observed(
      SidecarStore store,
      WorldInspection world,
      WorldChunks chunks,
      ChunkIndexState state,
      long added,
      long removed)
      throws IOException {
    TreeMap<InputKey, Checksum> checksums = new TreeMap<>();
    checksums.put(INVENTORY, chunks.inventory().digest());
    if (world.level().seed().isPresent()) {
      checksums.put(SEED, WorldBinding.seedChecksum(world.level().seed().getAsLong()));
    }
    checksums.put(ChunkIndexState.INPUT_KEY, state.pin());
    TreeMap<InputKey, String> facts = new TreeMap<>();
    facts.put(ADDITIONS, String.valueOf(added));
    facts.put(REMOVALS, String.valueOf(removed));
    return new PreconditionSnapshot(manifestState(store), checksums, facts);
  }

  /** The manifest as it is on disk: its revision and the checksum of its exact bytes. */
  private static ExpectedManifest manifestState(SidecarStore store) throws IOException {
    byte[] bytes = store.readManifestBytes();
    return new ExpectedManifest.AtRevision(
        manifest(store).revision(),
        Checksums.sha256(new ByteArrayInputStream(bytes), bytes.length).checksum());
  }

  // ---- apply and resume ------------------------------------------------------------------------

  /**
   * Carries out {@code preview}'s plan.
   *
   * @throws ToolException {@code NOTHING_TO_DO}, {@code NOT_ALLOWED}, {@code CHANGED}, or {@code
   *     VALIDATION_FAILED}
   * @throws IOException if interrupted; {@link #resume} continues it
   */
  public ReconcileDiff apply(ReconcilePreview preview) throws IOException {
    if (preview.diff().nothingToDo()) {
      throw new ToolException(
          ReconcileIssueCodes.NOTHING_TO_DO, "The index already matches the world");
    }
    SessionStatus status = requireAllowed(preview.locations());
    SidecarStore store = SidecarStore.open(SidecarRoot.open(preview.locations().sidecarDir()));
    try (SidecarWriter writer =
        store.openWriter(ToolInfo.LOCK_OWNER, clock, StaleLockPolicy.REFUSE)) {
      OperationJournal journal = OperationJournals.of(store, clock).create(writer, preview.plan());
      hook.at("recorded");
      run(writer, journal, status.world());
    }
    return preview.diff();
  }

  /**
   * Continues the interrupted reconcile {@code operation}.
   *
   * @throws ToolException {@code NOT_RESUMABLE}, or as {@link #apply}
   */
  public void resume(ToolLocations locations, OperationId operation) throws IOException {
    WorldInspection world = WorldInspector.inspect(locations.worldDir());
    if (!world.lock().allowsWrites()) {
      throw new ToolException(
          ReconcileIssueCodes.NOT_ALLOWED, "Minecraft has the world open; stop the server first");
    }
    SidecarStore store = SidecarStore.open(SidecarRoot.open(locations.sidecarDir()));
    try (SidecarWriter writer =
        store.openWriter(ToolInfo.LOCK_OWNER, clock, StaleLockPolicy.REFUSE)) {
      OperationJournal journal = OperationJournals.of(store, clock).open(writer, operation);
      if (!journal.progress().plan().type().equals(TYPE)) {
        throw new ToolException(
            ReconcileIssueCodes.NOT_RESUMABLE, "Operation " + operation + " is not a reconcile");
      }
      run(writer, journal, world);
    }
  }

  private void run(SidecarWriter writer, OperationJournal journal, WorldInspection world)
      throws IOException {
    OperationPlan plan = journal.progress().plan();
    SidecarStore store = writer.store();
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
                WorldChunks chunks = WorldChunks.collect(world, progress);
                IndexComparison.Counter counter = new IndexComparison.Counter();
                ChunkIndexState state;
                try (ChunkIndexSnapshot reader = ChunkIndexStore.of(store).openReader()) {
                  state = reader.state();
                  IndexComparison.compare(chunks, reader, counter);
                }
                return observed(store, world, chunks, state, counter.added(), counter.removed());
              }

              @Override
              public void perform(int step) throws IOException {
                switch (plan.steps().get(step).action().value()) {
                  case REPORT_KEY -> writeReport(plan, writer, world);
                  case APPLY_KEY -> applyChanges(plan, writer, world);
                  default -> throw new IllegalStateException("Unknown step");
                }
              }

              @Override
              public @Nullable String check(int check) throws IOException {
                return Reconciler.this.check(plan, store, world, check);
              }

              @Override
              public boolean isTerminal(IssueCode code) {
                return code.equals(ReconcileIssueCodes.CHANGED);
              }
            },
            ReconcileIssueCodes.CHANGED,
            ReconcileIssueCodes.VALIDATION_FAILED,
            ReconcileIssueCodes.NOT_RESUMABLE);
  }

  // ---- steps -----------------------------------------------------------------------------------

  private void writeReport(OperationPlan plan, SidecarWriter writer, WorldInspection world)
      throws IOException {
    SidecarStore store = writer.store();
    ManifestDocument doc = manifest(store);
    SidecarPath path = reportPath(plan.id());
    if (doc.files().stream().anyMatch(f -> f.path().equals(path))) {
      return; // written by an earlier attempt
    }
    WorldChunks chunks = worldUnchanged(plan, world);
    Path temp = Files.createTempFile("mconeworld-reconcile-", ".jsonl");
    try {
      long[] removed = {0};
      // Records go to a temporary file first: the header carries their count.
      try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp))) {
        try (ChunkIndexSnapshot reader = ChunkIndexStore.of(store).openReader()) {
          IndexComparison.compare(
              chunks,
              reader,
              new IndexComparison.Listener() {
                @Override
                public void added(ChunkPosition chunk) {}

                @Override
                public void removed(ChunkProvenance record) throws IOException {
                  out.write(ChunkIndexExport.recordLine(record));
                  out.write('\n');
                  removed[0]++;
                }
              });
        }
      }
      if (removed[0] != Long.parseLong(plan.preconditions().facts().getOrDefault(REMOVALS, "0"))) {
        throw new ToolException(ReconcileIssueCodes.CHANGED, "The removals differ from the plan");
      }
      byte[] header =
          ("{\"format\":\""
                  + REPORT_FORMAT
                  + "\",\"operation\":\""
                  + plan.id()
                  + "\",\"removed\":"
                  + removed[0]
                  + ",\"version\":\"1.0\"}\n")
              .getBytes(StandardCharsets.UTF_8);
      SidecarTransaction tx = writer.begin(doc.revision());
      StagedFile staged;
      try (InputStream in =
          new SequenceInputStream(new ByteArrayInputStream(header), Files.newInputStream(temp))) {
        staged = tx.stage(path, in, header.length + Files.size(temp));
      }
      ManifestDocument next =
          doc.withFile(
              new FileReference(
                  "reconcile-report",
                  null,
                  staged.target(),
                  staged.checksum(),
                  staged.sizeBytes()));
      if (next.state().worldBinding() == null) {
        next = next.withState(next.state().withWorldBinding(binding(world, plan)));
      }
      tx.commit(next.nextRevision());
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  private void applyChanges(OperationPlan plan, SidecarWriter writer, WorldInspection world)
      throws IOException {
    SidecarStore store = writer.store();
    WorldChunks chunks = worldUnchanged(plan, world);
    SeasonId active = manifest(store).state().activeSeasonId();
    FirstObservation first =
        active == null
            ? new FirstObservation.Legacy(plan.createdAt(), SCANNED)
            : new FirstObservation.InSeason(active, plan.createdAt(), SCANNED);
    try (SidecarChunkIndex index = ChunkIndexStore.of(store).openWriter(writer)) {
      ChunkIndexBatch[] batch = {index.begin()};
      int[] pending = {0};
      try (ChunkIndexSnapshot reader = index.reader()) {
        IndexComparison.compare(
            chunks,
            reader,
            new IndexComparison.Listener() {
              @Override
              public void added(ChunkPosition chunk) throws IOException {
                mutate(new ChunkMutation.ObserveFirst(chunk, first));
              }

              @Override
              public void removed(ChunkProvenance record) throws IOException {
                mutate(new ChunkMutation.Remove(record.chunk()));
              }

              private void mutate(ChunkMutation mutation) throws IOException {
                batch[0].apply(mutation);
                if (++pending[0] >= batchSize) {
                  batch[0].commit();
                  batch[0].close();
                  hook.at("index-batch");
                  batch[0] = index.begin();
                  pending[0] = 0;
                }
              }
            });
        batch[0].commit();
      } finally {
        batch[0].close();
      }
      index.checkpoint();
    }
  }

  // ---- checks ----------------------------------------------------------------------------------

  private @Nullable String check(
      OperationPlan plan, SidecarStore store, WorldInspection world, int check) throws IOException {
    return switch (plan.validations().get(check).check().value()) {
      case "mconeworld:index_matches_world" -> {
        WorldChunks chunks = WorldChunks.collect(world, progress);
        if (!chunks.inventory().digest().equals(plan.preconditions().checksums().get(INVENTORY))) {
          yield "The world changed during the reconcile";
        }
        IndexComparison.Counter counter = new IndexComparison.Counter();
        try (ChunkIndexSnapshot reader = ChunkIndexStore.of(store).openReader()) {
          IndexComparison.compare(chunks, reader, counter);
        }
        yield counter.same()
            ? null
            : "The index still differs from the world: "
                + counter.added()
                + " missing, "
                + counter.removed()
                + " extra";
      }
      case "mconeworld:index_verifies" -> {
        ValidationResult result = ChunkIndexStore.of(store).verify();
        yield result.isValid()
            ? null
            : "The chunk index does not verify: " + result.issues().getFirst();
      }
      case "mconeworld:report_complete" -> {
        SidecarPath path = reportPath(plan.id());
        ManifestDocument doc = manifest(store);
        if (doc.files().stream().noneMatch(f -> f.path().equals(path))) {
          yield "The manifest does not reference the report";
        }
        long lines;
        try (var reader =
            Files.newBufferedReader(store.root().resolve(path, JdkStorageFileSystem.INSTANCE))) {
          lines = reader.lines().count();
        }
        long planned = Long.parseLong(plan.preconditions().facts().getOrDefault(REMOVALS, "0"));
        yield lines == planned + 1
            ? null
            : "The report holds " + (lines - 1) + " records, not " + planned;
      }
      default -> throw new IllegalStateException("Unknown check");
    };
  }

  // ---- helpers ---------------------------------------------------------------------------------

  /** The world's chunks, refusing (terminally) if its headers changed since planning. */
  private WorldChunks worldUnchanged(OperationPlan plan, WorldInspection world) throws IOException {
    WorldChunks chunks = WorldChunks.collect(world, progress);
    if (!chunks.inventory().digest().equals(plan.preconditions().checksums().get(INVENTORY))) {
      throw new ToolException(
          ReconcileIssueCodes.CHANGED, "The world's region files changed since planning");
    }
    return chunks;
  }

  private static WorldBinding binding(WorldInspection world, OperationPlan plan)
      throws ToolException {
    if (world.level().seed().isEmpty()) {
      throw new ToolException(ReconcileIssueCodes.CHANGED, "The world's seed is unknown");
    }
    return new WorldBinding(
        WorldBinding.seedChecksum(world.level().seed().getAsLong()),
        WorldBinding.storableLevelName(world.level().levelName().orElse(null)),
        world.level().dataVersion().orElse(0),
        plan.createdAt());
  }

  private SessionStatus requireAllowed(ToolLocations locations) throws IOException {
    SessionStatus status = ToolSession.inspect(locations, clock);
    ActionAvailability action = status.action(SessionAction.RECONCILE);
    if (!action.available()) {
      throw new ToolException(
          ReconcileIssueCodes.NOT_ALLOWED,
          "The index cannot be reconciled now",
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
          ReconcileIssueCodes.CHANGED, "The manifest cannot be read", e.issues(), e);
    }
  }

  /** Builds the preview's diff while the comparison streams. */
  private static final class DiffCollector implements IndexComparison.Listener {
    private final List<ProtectedRegion> regions;
    private final Map<DimensionKey, long[]> perDimension = new LinkedHashMap<>();
    private final Map<String, Long> bySeason = new TreeMap<>();
    private final List<ChunkPosition> protectedSample = new ArrayList<>();

    DiffCollector(List<ProtectedRegion> regions) {
      this.regions = regions;
    }

    @Override
    public void added(ChunkPosition chunk) {
      counts(chunk.dimension())[0]++;
    }

    @Override
    public void removed(ChunkProvenance record) {
      long[] c = counts(record.chunk().dimension());
      c[1]++;
      String season =
          record.firstObservation() instanceof FirstObservation.InSeason s
              ? s.season().toString()
              : "legacy";
      bySeason.merge(season, 1L, Long::sum);
      boolean isProtected =
          record.protection() == ChunkProtection.PROTECTED
              || regions.stream().anyMatch(r -> r.protects(record.chunk()));
      if (isProtected) {
        c[2]++;
        if (protectedSample.size() < ReconcileDiff.SAMPLE) {
          protectedSample.add(record.chunk());
        }
      }
    }

    private long[] counts(DimensionKey dimension) {
      return perDimension.computeIfAbsent(dimension, d -> new long[3]);
    }

    ReconcileDiff result(boolean bindsWorld) {
      List<DimensionChanges> dimensions = new ArrayList<>();
      perDimension.forEach((d, c) -> dimensions.add(new DimensionChanges(d, c[0], c[1], c[2])));
      return new ReconcileDiff(dimensions, new TreeMap<>(bySeason), protectedSample, bindsWorld);
    }
  }
}
