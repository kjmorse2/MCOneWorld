// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.init;

import io.github.kjmorse2.mconeworld.domain.ArchiveId;
import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.OperationId;
import io.github.kjmorse2.mconeworld.domain.archive.ArchiveState;
import io.github.kjmorse2.mconeworld.domain.archive.WorldBinding;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkRectangle;
import io.github.kjmorse2.mconeworld.domain.operation.ActionKey;
import io.github.kjmorse2.mconeworld.domain.operation.BackupReference;
import io.github.kjmorse2.mconeworld.domain.operation.BackupRequirement;
import io.github.kjmorse2.mconeworld.domain.operation.ExpectedManifest;
import io.github.kjmorse2.mconeworld.domain.operation.ImpactClass;
import io.github.kjmorse2.mconeworld.domain.operation.InputKey;
import io.github.kjmorse2.mconeworld.domain.operation.OperationPlan;
import io.github.kjmorse2.mconeworld.domain.operation.OperationTarget;
import io.github.kjmorse2.mconeworld.domain.operation.OperationText;
import io.github.kjmorse2.mconeworld.domain.operation.OperationType;
import io.github.kjmorse2.mconeworld.domain.operation.PreconditionSnapshot;
import io.github.kjmorse2.mconeworld.domain.operation.RollbackDeclaration;
import io.github.kjmorse2.mconeworld.domain.operation.RollbackStrategy;
import io.github.kjmorse2.mconeworld.domain.operation.StepDescriptor;
import io.github.kjmorse2.mconeworld.domain.operation.ValidationDescriptor;
import io.github.kjmorse2.mconeworld.domain.provenance.Confidence;
import io.github.kjmorse2.mconeworld.domain.provenance.EvidenceSource;
import io.github.kjmorse2.mconeworld.domain.provenance.FirstObservation;
import io.github.kjmorse2.mconeworld.domain.provenance.ProvenanceEvidence;
import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationResult;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexBatch;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexSnapshot;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexState;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexStore;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkMutation;
import io.github.kjmorse2.mconeworld.sidecar.index.SidecarChunkIndex;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestDocument;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestException;
import io.github.kjmorse2.mconeworld.sidecar.operation.OperationJournal;
import io.github.kjmorse2.mconeworld.sidecar.operation.OperationJournals;
import io.github.kjmorse2.mconeworld.sidecar.storage.Checksums;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarRoot;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarStore;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarWriter;
import io.github.kjmorse2.mconeworld.sidecar.storage.StaleLockPolicy;
import io.github.kjmorse2.mconeworld.tool.core.ProgressListener;
import io.github.kjmorse2.mconeworld.tool.core.ToolException;
import io.github.kjmorse2.mconeworld.tool.core.ToolInfo;
import io.github.kjmorse2.mconeworld.tool.core.inventory.IndexComparison;
import io.github.kjmorse2.mconeworld.tool.core.inventory.InventoryScanner;
import io.github.kjmorse2.mconeworld.tool.core.inventory.WorldChunks;
import io.github.kjmorse2.mconeworld.tool.core.inventory.WorldInventory;
import io.github.kjmorse2.mconeworld.tool.core.operation.OperationRunner;
import io.github.kjmorse2.mconeworld.tool.core.operation.OperationWork;
import io.github.kjmorse2.mconeworld.tool.core.session.ActionAvailability;
import io.github.kjmorse2.mconeworld.tool.core.session.SessionAction;
import io.github.kjmorse2.mconeworld.tool.core.session.SessionStatus;
import io.github.kjmorse2.mconeworld.tool.core.session.ToolLocations;
import io.github.kjmorse2.mconeworld.tool.core.session.ToolSession;
import io.github.kjmorse2.mconeworld.tool.world.WorldInspection;
import io.github.kjmorse2.mconeworld.tool.world.WorldInspector;
import io.github.kjmorse2.mconeworld.tool.world.region.ChunkEntry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
 * Creates a sidecar for an existing world: {@link #preview} plans and shows it without writing
 * anything; {@link #apply} records the plan in a journal and carries it out; {@link #resume}
 * continues an interrupted one. The world is never written.
 *
 * <p>Steps, each safe to retry:
 *
 * <ol>
 *   <li>{@value #CREATE_MANIFEST_KEY}: the first manifest, revision 0, bound to the world's seed
 *   <li>{@value #CREATE_INDEX_KEY}: an empty chunk index, whose id is the operation's id
 *   <li>{@value #IMPORT_KEY}: every chunk present in a region file, recorded as legacy (first
 *       observation wins, so a retry changes nothing)
 * </ol>
 *
 * Checks: the index holds exactly the world's chunks, the index verifies, and the manifest is bound
 * to this world. The world's region headers are pinned by a digest: if they change between planning
 * and the end, the operation stops.
 */
public final class SidecarInitializer {
  /** The operation type. */
  public static final OperationType TYPE = OperationType.of("mconeworld:sidecar_init");

  static final String CREATE_MANIFEST_KEY = "mconeworld:create_manifest";
  static final String CREATE_INDEX_KEY = "mconeworld:create_chunk_index";
  static final String IMPORT_KEY = "mconeworld:import_legacy_chunks";

  static final InputKey SEED = InputKey.of("world:seed");
  static final InputKey INVENTORY = InputKey.of("world:region_inventory");
  static final InputKey DATA_VERSION = InputKey.of("minecraft:data_version");

  /** Mutations per chunk-index commit during the import. */
  static final int BATCH = 32_768;

  private static final ProvenanceEvidence IMPORTED =
      new ProvenanceEvidence(EvidenceSource.WORLD_IMPORT, Confidence.HIGH);

  private final Clock clock;
  private final ProgressListener progress;
  private final OperationRunner.Hook hook;
  private final int batchSize;

  public SidecarInitializer(Clock clock, ProgressListener progress) {
    this(clock, progress, OperationRunner.Hook.NONE, BATCH);
  }

  SidecarInitializer(
      Clock clock, ProgressListener progress, OperationRunner.Hook hook, int batchSize) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.progress = Objects.requireNonNull(progress, "progress");
    this.hook = Objects.requireNonNull(hook, "hook");
    this.batchSize = batchSize;
  }

  // ---- preview ---------------------------------------------------------------------------------

  /**
   * Scans the world and plans the initialization. Writes nothing.
   *
   * @throws ToolException {@code NOT_ALLOWED} with every reason when the session forbids it, or
   *     {@code SEED_UNKNOWN}
   */
  public InitPreview preview(ToolLocations locations) throws IOException {
    SessionStatus status = requireAllowed(locations);
    WorldInspection world = status.world();
    if (world.level().seed().isEmpty()) {
      throw new ToolException(
          InitIssueCodes.SEED_UNKNOWN,
          "The world's seed is unknown, so a sidecar cannot be bound to it");
    }
    Map<DimensionKey, int[]> bounds = new LinkedHashMap<>();
    WorldInventory inventory =
        InventoryScanner.scan(
            world,
            (dimension, chunks) -> {
              int[] b =
                  bounds.computeIfAbsent(
                      dimension,
                      d ->
                          new int[] {
                            Integer.MAX_VALUE,
                            Integer.MAX_VALUE,
                            Integer.MIN_VALUE,
                            Integer.MIN_VALUE
                          });
              for (ChunkEntry e : chunks) {
                b[0] = Math.min(b[0], e.chunk().x());
                b[1] = Math.min(b[1], e.chunk().z());
                b[2] = Math.max(b[2], e.chunk().x());
                b[3] = Math.max(b[3], e.chunk().z());
              }
            },
            progress);
    return new InitPreview(locations, plan(world, inventory, bounds), inventory);
  }

  private OperationPlan plan(
      WorldInspection world, WorldInventory inventory, Map<DimensionKey, int[]> bounds) {
    List<OperationTarget> targets = new ArrayList<>();
    targets.add(new OperationTarget.SidecarManifest());
    List<Integer> areas = new ArrayList<>();
    for (Map.Entry<DimensionKey, int[]> e : bounds.entrySet()) {
      int[] b = e.getValue();
      areas.add(targets.size());
      targets.add(
          new OperationTarget.ChunkArea(new ChunkRectangle(e.getKey(), b[0], b[1], b[2], b[3])));
    }
    TreeMap<InputKey, Checksum> checksums = new TreeMap<>();
    checksums.put(SEED, WorldBinding.seedChecksum(world.level().seed().getAsLong()));
    checksums.put(INVENTORY, inventory.digest());
    TreeMap<InputKey, String> facts = new TreeMap<>();
    facts.put(DATA_VERSION, dataVersionFact(world));
    OperationId id = OperationId.of(UUID.randomUUID());
    return new OperationPlan(
        id,
        TYPE,
        clock.instant(),
        ToolInfo.IDENTITY,
        ArchiveId.of(UUID.randomUUID()),
        ImpactClass.SIDECAR_MUTATING,
        targets,
        new PreconditionSnapshot(new ExpectedManifest.Absent(), checksums, facts),
        new BackupRequirement.NotRequired(
            text("Creates a new sidecar; the world is only read, so there is nothing to back up.")),
        List.of(
            new StepDescriptor(
                ActionKey.of(CREATE_MANIFEST_KEY),
                text("Create the first manifest, bound to this world's seed"),
                true,
                List.of(0)),
            new StepDescriptor(
                ActionKey.of(CREATE_INDEX_KEY),
                text("Create an empty chunk index"),
                true,
                List.of(0)),
            new StepDescriptor(
                ActionKey.of(IMPORT_KEY),
                text(
                    "Record "
                        + inventory.totalRegionChunks()
                        + " existing chunks as legacy (their season is unknown)"),
                true,
                areas)),
        List.of(
            new ValidationDescriptor(
                ActionKey.of("mconeworld:index_matches_world"),
                text("The index holds exactly the chunks of every dimension")),
            new ValidationDescriptor(
                ActionKey.of("mconeworld:index_verifies"), text("The chunk index verifies")),
            new ValidationDescriptor(
                ActionKey.of("mconeworld:manifest_binds_world"),
                text("The manifest is bound to this world's seed"))),
        new RollbackDeclaration(
            RollbackStrategy.NOT_APPLICABLE,
            text("The world is untouched. To start over, delete the new sidecar directory.")));
  }

  // ---- apply and resume ------------------------------------------------------------------------

  /**
   * Carries out {@code preview}'s plan: creates the sidecar directory if needed, records the plan,
   * and runs every step and check.
   *
   * @throws ToolException {@code NOT_ALLOWED} if the session no longer allows it, {@code
   *     WORLD_CHANGED}, {@code SIDECAR_CONFLICT}, or {@code VALIDATION_FAILED}
   * @throws IOException if the work is interrupted or fails; then {@link #resume} continues it
   */
  public InitResult apply(InitPreview preview) throws IOException {
    SessionStatus status = requireAllowed(preview.locations());
    Path dir = preview.locations().sidecarDir();
    if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
      Files.createDirectory(dir);
    }
    SidecarStore store = SidecarStore.open(SidecarRoot.open(dir));
    try (SidecarWriter writer =
        store.openWriter(ToolInfo.LOCK_OWNER, clock, StaleLockPolicy.REFUSE)) {
      OperationJournal journal = OperationJournals.of(store, clock).create(writer, preview.plan());
      hook.at("recorded");
      return run(writer, journal, status.world());
    }
  }

  /**
   * Continues the interrupted initialization {@code operation} from where its journal stops.
   *
   * @throws ToolException {@code NOT_RESUMABLE} if it is not an initialization this Tool can
   *     continue, or as {@link #apply}
   */
  public InitResult resume(ToolLocations locations, OperationId operation) throws IOException {
    WorldInspection world = WorldInspector.inspect(locations.worldDir());
    if (!world.lock().allowsWrites()) {
      throw new ToolException(
          InitIssueCodes.NOT_ALLOWED, "Minecraft has the world open; stop the server first");
    }
    SidecarStore store = SidecarStore.open(SidecarRoot.open(locations.sidecarDir()));
    try (SidecarWriter writer =
        store.openWriter(ToolInfo.LOCK_OWNER, clock, StaleLockPolicy.REFUSE)) {
      OperationJournal journal = OperationJournals.of(store, clock).open(writer, operation);
      if (!journal.progress().plan().type().equals(TYPE)) {
        throw new ToolException(
            InitIssueCodes.NOT_RESUMABLE, "Operation " + operation + " is not an initialization");
      }
      return run(writer, journal, world);
    }
  }

  private InitResult run(SidecarWriter writer, OperationJournal journal, WorldInspection world)
      throws IOException {
    OperationPlan plan = journal.progress().plan();
    WorldInventory[] checked = {null};
    new OperationRunner(hook)
        .run(
            journal,
            new OperationWork() {
              @Override
              public @Nullable BackupReference confirmedBackup() {
                return null; // the plan requires none: nothing in the world is written
              }

              @Override
              public PreconditionSnapshot observe() throws IOException {
                return SidecarInitializer.this.observe(writer.store(), world);
              }

              @Override
              public void perform(int step) throws IOException {
                SidecarInitializer.this.perform(step, plan, writer, world);
              }

              @Override
              public @Nullable String check(int check) throws IOException {
                Checked result = SidecarInitializer.this.check(check, plan, writer.store(), world);
                if (result.inventory() != null) {
                  checked[0] = result.inventory();
                }
                return result.failure();
              }

              @Override
              public boolean isTerminal(IssueCode code) {
                return code.equals(InitIssueCodes.WORLD_CHANGED)
                    || code.equals(InitIssueCodes.SIDECAR_CONFLICT);
              }
            },
            InitIssueCodes.WORLD_CHANGED,
            InitIssueCodes.VALIDATION_FAILED,
            InitIssueCodes.NOT_RESUMABLE);
    WorldInventory inventory = checked[0];
    if (inventory == null) {
      inventory = InventoryScanner.scan(world, InventoryScanner.RegionSink.NONE, progress);
    }
    return new InitResult(plan.id(), plan.archive(), inventory);
  }

  // ---- steps -----------------------------------------------------------------------------------

  private void perform(int step, OperationPlan plan, SidecarWriter writer, WorldInspection world)
      throws IOException {
    SidecarStore store = writer.store();
    switch (plan.steps().get(step).action().value()) {
      case CREATE_MANIFEST_KEY -> {
        if (store.hasManifest()) {
          if (!manifest(store).archiveId().equals(plan.archive())) {
            throw new ToolException(
                InitIssueCodes.SIDECAR_CONFLICT,
                "The sidecar holds a manifest this operation did not create");
          }
          return; // created by an earlier attempt
        }
        WorldBinding binding =
            new WorldBinding(
                Objects.requireNonNull(plan.preconditions().checksums().get(SEED)),
                WorldBinding.storableLevelName(world.level().levelName().orElse(null)),
                world.level().dataVersion().orElse(0),
                plan.createdAt());
        ManifestDocument doc =
            ManifestDocument.create(plan.archive(), plan.createdAt())
                .withState(ArchiveState.empty().withWorldBinding(binding));
        writer.begin(null).commit(doc);
      }
      case CREATE_INDEX_KEY -> {
        ChunkIndexState state = ChunkIndexStore.of(store).inspect().state();
        if (state != null) {
          if (!state.indexId().equals(plan.id().value())) {
            throw new ToolException(
                InitIssueCodes.SIDECAR_CONFLICT,
                "The sidecar holds a chunk index this operation did not create");
          }
          return;
        }
        ChunkIndexStore.of(store).create(writer, plan.id().value()).close();
      }
      case IMPORT_KEY -> importChunks(plan, writer, world);
      default -> throw new IllegalStateException("Unknown step " + plan.steps().get(step).action());
    }
  }

  private void importChunks(OperationPlan plan, SidecarWriter writer, WorldInspection world)
      throws IOException {
    FirstObservation legacy = new FirstObservation.Legacy(plan.createdAt(), IMPORTED);
    try (SidecarChunkIndex index = ChunkIndexStore.of(writer.store()).openWriter(writer)) {
      ChunkIndexBatch[] batch = {index.begin()};
      int[] pending = {0};
      try {
        WorldInventory inventory =
            InventoryScanner.scan(
                world,
                (dimension, chunks) -> {
                  for (ChunkEntry e : chunks) {
                    batch[0].apply(new ChunkMutation.ObserveFirst(e.chunk(), legacy));
                    if (++pending[0] >= batchSize) {
                      batch[0].commit();
                      batch[0].close();
                      hook.at("import-batch");
                      batch[0] = index.begin();
                      pending[0] = 0;
                    }
                  }
                },
                progress);
        batch[0].commit();
        if (!inventory.digest().equals(plan.preconditions().checksums().get(INVENTORY))) {
          throw new ToolException(
              InitIssueCodes.WORLD_CHANGED, "The world's region files changed during the import");
        }
      } finally {
        batch[0].close();
      }
      index.checkpoint();
    }
  }

  // ---- checks ----------------------------------------------------------------------------------

  /** A check's outcome: {@code failure} is null when it passed. */
  private record Checked(@Nullable String failure, @Nullable WorldInventory inventory) {}

  private Checked check(int check, OperationPlan plan, SidecarStore store, WorldInspection world)
      throws IOException {
    return switch (plan.validations().get(check).check().value()) {
      case "mconeworld:index_matches_world" -> {
        WorldChunks chunks = WorldChunks.collect(world, progress);
        if (!chunks.inventory().digest().equals(plan.preconditions().checksums().get(INVENTORY))) {
          yield new Checked("The world changed after the import", chunks.inventory());
        }
        IndexComparison.Counter counter = new IndexComparison.Counter();
        try (ChunkIndexSnapshot reader = ChunkIndexStore.of(store).openReader()) {
          IndexComparison.compare(chunks, reader, counter);
        }
        yield new Checked(
            counter.same()
                ? null
                : "The index differs from the world: "
                    + counter.added()
                    + " chunks missing, "
                    + counter.removed()
                    + " extra",
            chunks.inventory());
      }
      case "mconeworld:index_verifies" -> {
        ValidationResult result = ChunkIndexStore.of(store).verify();
        yield new Checked(
            result.isValid()
                ? null
                : "The chunk index does not verify: " + result.issues().getFirst(),
            null);
      }
      case "mconeworld:manifest_binds_world" -> {
        ManifestDocument doc = manifest(store);
        WorldBinding binding = doc.state().worldBinding();
        boolean ok =
            doc.archiveId().equals(plan.archive())
                && binding != null
                && world.level().seed().isPresent()
                && binding.matchesSeed(world.level().seed().getAsLong());
        yield new Checked(ok ? null : "The manifest is not bound to this world", null);
      }
      default ->
          throw new IllegalStateException("Unknown check " + plan.validations().get(check).check());
    };
  }

  // ---- helpers ---------------------------------------------------------------------------------

  private SessionStatus requireAllowed(ToolLocations locations) throws IOException {
    SessionStatus status = ToolSession.inspect(locations, clock);
    ActionAvailability init = status.action(SessionAction.INITIALIZE_SIDECAR);
    if (!init.available()) {
      throw new ToolException(
          InitIssueCodes.NOT_ALLOWED,
          "The sidecar cannot be initialized now",
          ValidationResult.of(init.blockers()),
          null);
    }
    return status;
  }

  /** The state to compare with the plan's preconditions, observed now. */
  private PreconditionSnapshot observe(SidecarStore store, WorldInspection world)
      throws IOException {
    ExpectedManifest manifest = new ExpectedManifest.Absent();
    if (store.hasManifest()) {
      byte[] bytes = store.readManifestBytes();
      manifest =
          new ExpectedManifest.AtRevision(
              manifest(store).revision(),
              Checksums.sha256(new ByteArrayInputStream(bytes), bytes.length).checksum());
    }
    TreeMap<InputKey, Checksum> checksums = new TreeMap<>();
    if (world.level().seed().isPresent()) {
      checksums.put(SEED, WorldBinding.seedChecksum(world.level().seed().getAsLong()));
    }
    checksums.put(
        INVENTORY,
        InventoryScanner.scan(world, InventoryScanner.RegionSink.NONE, progress).digest());
    TreeMap<InputKey, String> facts = new TreeMap<>();
    facts.put(DATA_VERSION, dataVersionFact(world));
    return new PreconditionSnapshot(manifest, checksums, facts);
  }

  private static ManifestDocument manifest(SidecarStore store) throws IOException {
    try {
      return store.readManifest().document();
    } catch (ManifestException e) {
      throw new ToolException(
          InitIssueCodes.SIDECAR_CONFLICT, "The sidecar's manifest cannot be read", e.issues(), e);
    }
  }

  private static String dataVersionFact(WorldInspection world) {
    return world.level().dataVersion().isPresent()
        ? String.valueOf(world.level().dataVersion().getAsInt())
        : "unknown";
  }

  static OperationText text(String message) {
    return OperationRunner.text(message);
  }
}
