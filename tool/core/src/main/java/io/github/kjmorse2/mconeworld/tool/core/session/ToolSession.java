// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.session;

import io.github.kjmorse2.mconeworld.domain.OperationId;
import io.github.kjmorse2.mconeworld.domain.archive.WorldBinding;
import io.github.kjmorse2.mconeworld.domain.operation.OperationType;
import io.github.kjmorse2.mconeworld.domain.validation.IssueLocation;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationIssue;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationResult;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexStatus;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexStore;
import io.github.kjmorse2.mconeworld.sidecar.manifest.DecodedManifest;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestCodec;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestException;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestIssueCodes;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestLimits;
import io.github.kjmorse2.mconeworld.sidecar.migration.MigrationException;
import io.github.kjmorse2.mconeworld.sidecar.migration.MigrationPlanner;
import io.github.kjmorse2.mconeworld.sidecar.migration.MigrationRegistry;
import io.github.kjmorse2.mconeworld.sidecar.operation.JournalIntegrity;
import io.github.kjmorse2.mconeworld.sidecar.operation.OperationJournals;
import io.github.kjmorse2.mconeworld.sidecar.operation.OperationStatus;
import io.github.kjmorse2.mconeworld.sidecar.storage.RecoveryFinding;
import io.github.kjmorse2.mconeworld.sidecar.storage.RecoveryReport;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarRoot;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarStore;
import io.github.kjmorse2.mconeworld.tool.world.WorldInspection;
import io.github.kjmorse2.mconeworld.tool.world.WorldInspector;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Inspects a world and its sidecar together, read-only, and decides which {@link SessionAction}s
 * can start. Every blocker is a {@link ValidationIssue} with remediation, so the UI and the command
 * line explain a disabled action the same way.
 */
public final class ToolSession {
  private ToolSession() {}

  /**
   * Inspects {@code locations}. Nothing is written, locked, recovered, or migrated.
   *
   * @throws io.github.kjmorse2.mconeworld.tool.world.WorldException if the world directory is not a
   *     readable world
   * @throws IOException if a directory cannot be read
   */
  public static SessionStatus inspect(ToolLocations locations, Clock clock) throws IOException {
    WorldInspection world = WorldInspector.inspect(locations.worldDir());
    Optional<ValidationIssue> overlap = locations.overlap();
    SidecarSnapshot sidecar =
        overlap.isPresent()
            ? new SidecarSnapshot(
                SidecarState.UNREADABLE,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                ValidationResult.of(List.of(overlap.get())))
            : sidecar(locations.sidecarDir(), clock);
    List<ValidationIssue> all = new ArrayList<>(world.findings().issues());
    all.addAll(sidecar.findings().issues());
    binding(world, sidecar).ifPresent(all::add);
    return new SessionStatus(
        locations, world, sidecar, actions(world, sidecar), ValidationResult.of(all));
  }

  private static SidecarSnapshot sidecar(Path dir, Clock clock) throws IOException {
    if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
      return empty(SidecarState.ABSENT, List.of());
    }
    if (Files.isSymbolicLink(dir) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
      return empty(
          SidecarState.UNREADABLE,
          List.of(
              ValidationIssue.error(
                  SessionIssueCodes.SIDECAR_UNREADABLE,
                  IssueLocation.root(),
                  "The sidecar location is not a plain directory")));
    }
    SidecarStore store = SidecarStore.open(SidecarRoot.open(dir));
    RecoveryReport recovery = store.inspect();
    List<ValidationIssue> findings = new ArrayList<>();
    for (RecoveryFinding finding : recovery.findings()) {
      findings.add(
          ValidationIssue.warning(
                  finding.code(),
                  IssueLocation.root().key(finding.stagingEntry()),
                  finding.message())
              .withRemediation(
                  finding.automaticallyRecoverable()
                      ? "Run Recover to finish or undo the interrupted commit."
                      : "This needs a person: see the sidecar storage documentation."));
    }
    if (!store.hasManifest()) {
      boolean empty;
      try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
        empty = !entries.iterator().hasNext();
      }
      if (empty) {
        return new SidecarSnapshot(
            SidecarState.EMPTY,
            Optional.empty(),
            Optional.of(recovery),
            Optional.empty(),
            List.of(),
            ValidationResult.of(findings));
      }
      // An interrupted initialization leaves journals before the first manifest exists.
      List<OperationStatus> operations = operations(store, clock);
      boolean initializing = operations.stream().anyMatch(op -> resumable(op));
      findings.add(
          ValidationIssue.error(
                  SessionIssueCodes.SIDECAR_INCOMPLETE,
                  IssueLocation.root().key("manifest.json"),
                  "The sidecar directory has files but no manifest")
              .withRemediation(
                  initializing
                      ? "An initialization was interrupted: resume it."
                      : "The Tool never creates a sidecar over existing files. Restore the manifest"
                          + " from a backup, or choose an empty directory."));
      return new SidecarSnapshot(
          SidecarState.INCOMPLETE,
          Optional.empty(),
          Optional.of(recovery),
          Optional.empty(),
          operations,
          ValidationResult.of(findings));
    }

    DecodedManifest manifest;
    try {
      manifest = store.readManifest();
    } catch (ManifestException e) {
      SidecarState state =
          migratable(store) ? SidecarState.NEEDS_MIGRATION : SidecarState.UNREADABLE;
      findings.add(
          ValidationIssue.error(
                  state == SidecarState.NEEDS_MIGRATION
                      ? SessionIssueCodes.MIGRATION_NEEDED
                      : SessionIssueCodes.SIDECAR_UNREADABLE,
                  IssueLocation.root().key("manifest.json"),
                  "The manifest cannot be used as is: " + e.getMessage())
              .withRemediation(
                  state == SidecarState.NEEDS_MIGRATION
                      ? "Run Migrate: it backs up the manifest, then converts it explicitly."
                      : "Use a version of the Tool that reads this manifest, or restore a backup.")
              .withCause(e));
      return new SidecarSnapshot(
          state,
          Optional.empty(),
          Optional.of(recovery),
          Optional.empty(),
          List.of(),
          ValidationResult.of(findings));
    }
    findings.addAll(manifest.warnings().issues());

    ChunkIndexStatus index = ChunkIndexStore.of(store).inspect();
    findings.addAll(index.findings().issues());

    List<OperationStatus> operations = operations(store, clock);
    return new SidecarSnapshot(
        SidecarState.PRESENT,
        Optional.of(manifest),
        Optional.of(recovery),
        Optional.of(index),
        operations,
        ValidationResult.of(findings));
  }

  private static List<OperationStatus> operations(SidecarStore store, Clock clock)
      throws IOException {
    OperationJournals journals = OperationJournals.of(store, clock);
    List<OperationStatus> operations = new ArrayList<>();
    for (OperationId id : journals.list()) {
      operations.add(journals.inspect(id));
    }
    return operations;
  }

  /** The operation types this Tool can continue after an interruption. */
  public static final List<OperationType> RESUMABLE_TYPES =
      List.of(
          OperationType.of("mconeworld:sidecar_init"),
          OperationType.of("mconeworld:reconcile"),
          OperationType.of("mconeworld:backup"),
          OperationType.of("mconeworld:season_transition"));

  /**
   * Whether this Tool can continue {@code op}: an intact journal of a type it knows, not finished,
   * and not failed (a failed operation is never retried automatically).
   */
  static boolean resumable(OperationStatus op) {
    var progress = op.progress();
    var plan = op.plan();
    return op.integrity() == JournalIntegrity.INTACT
        && op.writable()
        && plan != null
        && RESUMABLE_TYPES.contains(plan.plan().type())
        && progress != null
        && switch (progress.state()) {
          case PLANNED, BACKED_UP, APPLYING, VALIDATING -> true;
          default -> false;
        };
  }

  /** Whether an explicit migration path to the current schema exists (read-only dry run). */
  private static boolean migratable(SidecarStore store) throws IOException {
    try {
      new MigrationPlanner(MigrationRegistry.builtIn(), ManifestLimits.DEFAULT)
          .plan(store, ManifestCodec.CURRENT);
      return true;
    } catch (MigrationException e) {
      return false;
    }
  }

  private static SidecarSnapshot empty(SidecarState state, List<ValidationIssue> findings) {
    return new SidecarSnapshot(
        state,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        List.of(),
        ValidationResult.of(findings));
  }

  // ---- action rules ---------------------------------------------------------------------------

  private static List<ActionAvailability> actions(WorldInspection world, SidecarSnapshot sidecar) {
    List<ActionAvailability> out = new ArrayList<>();
    for (SessionAction action : SessionAction.values()) {
      out.add(new ActionAvailability(action, blockers(action, world, sidecar)));
    }
    return out;
  }

  private static List<ValidationIssue> blockers(
      SessionAction action, WorldInspection world, SidecarSnapshot sidecar) {
    // Every action writes the sidecar, so every action needs its writer lock to be free.
    List<ValidationIssue> b = new ArrayList<>();
    switch (action) {
      case INITIALIZE_SIDECAR -> {
        if (sidecar.state() != SidecarState.ABSENT && sidecar.state() != SidecarState.EMPTY) {
          b.add(
              block(
                  SessionIssueCodes.SIDECAR_INCOMPLETE,
                  "A sidecar (or other files) already exists here"));
        }
        serverStopped(world, b);
      }
      case RESUME_OPERATION -> {
        if (sidecar.operations().stream().noneMatch(ToolSession::resumable)) {
          b.add(
              block(
                  SessionIssueCodes.OPERATION_UNFINISHED,
                  "No interrupted operation this Tool can resume"));
        }
        if (sidecar.recovery().map(r -> !r.findings().isEmpty()).orElse(false)) {
          b.add(
              block(
                      SessionIssueCodes.RECOVERY_NEEDED,
                      "An interrupted commit must be recovered first")
                  .withRemediation("Run Recover."));
        }
        serverStopped(world, b);
      }
      case RECOVER_SIDECAR -> {
        if (sidecar.recovery().map(r -> r.findings().isEmpty()).orElse(true)) {
          b.add(block(SessionIssueCodes.RECOVERY_NEEDED, "There is nothing to recover"));
        }
      }
      case MIGRATE_MANIFEST -> {
        if (sidecar.state() != SidecarState.NEEDS_MIGRATION) {
          b.add(block(SessionIssueCodes.MIGRATION_NEEDED, "The manifest needs no migration"));
        }
      }
      case RECONCILE, BACKUP, NEW_SEASON -> {
        usableSidecar(world, sidecar, b);
        serverStopped(world, b);
        if (action == SessionAction.NEW_SEASON) {
          b.addAll(world.support().reasons());
          boolean pending =
              sidecar.manifest().map(m -> m.document().state().isActivationPending()).orElse(false);
          if (pending) {
            b.add(
                block(
                        SessionIssueCodes.ACTIVATION_PENDING,
                        "The current season's activation has not been applied by the server yet")
                    .withRemediation(
                        "Start the server with the MCOneWorld plugin once, so it applies the"
                            + " activation, then stop it."));
          }
        }
      }
    }
    if (sidecar.lockedByAnotherProcess()) {
      b.add(
          block(SessionIssueCodes.SIDECAR_LOCKED, "Another process holds the sidecar writer lock")
              .withRemediation(
                  "Stop the Minecraft server (the plugin holds the lock while it runs)."));
    }
    return b;
  }

  /**
   * Whether the sidecar belongs to this world: an error if its binding names another seed, a
   * warning if it has no binding (or the world's seed is unknown), nothing if it matches.
   */
  private static Optional<ValidationIssue> binding(WorldInspection world, SidecarSnapshot sidecar) {
    if (sidecar.manifest().isEmpty()) {
      return Optional.empty();
    }
    WorldBinding binding = sidecar.manifest().get().document().state().worldBinding();
    IssueLocation at = IssueLocation.root().field("world");
    if (binding == null) {
      return Optional.of(
          ValidationIssue.warning(
                  SessionIssueCodes.WORLD_UNBOUND,
                  at,
                  "The sidecar does not record which world it belongs to")
              .withRemediation("Run Reconcile once: it records this world's binding."));
    }
    if (world.level().seed().isEmpty()) {
      return Optional.of(
          ValidationIssue.warning(
              SessionIssueCodes.WORLD_UNBOUND,
              at,
              "The world's seed is unknown, so the sidecar's binding cannot be checked"));
    }
    if (!binding.matchesSeed(world.level().seed().getAsLong())) {
      String name = binding.levelName();
      return Optional.of(
          ValidationIssue.error(
                  SessionIssueCodes.WORLD_MISMATCH,
                  at,
                  "This sidecar belongs to a world with a different seed"
                      + (name == null ? "" : " (\"" + name + "\")"))
              .withRemediation(
                  "Open the sidecar that belongs to this world, or this sidecar's own world."));
    }
    return Optional.empty();
  }

  private static void usableSidecar(
      WorldInspection world, SidecarSnapshot sidecar, List<ValidationIssue> b) {
    if (sidecar.state() != SidecarState.PRESENT) {
      b.add(
          block(
              SessionIssueCodes.SIDECAR_UNREADABLE,
              "No usable sidecar (state " + sidecar.state() + ")"));
      return;
    }
    boolean newer =
        sidecar
            .manifest()
            .map(m -> m.warnings().has(ManifestIssueCodes.NEWER_MINOR_VERSION))
            .orElse(false);
    if (newer) {
      b.add(
          block(
                  SessionIssueCodes.SIDECAR_NEWER,
                  "The sidecar was written by a newer MCOneWorld; it is read-only here")
              .withRemediation("Upgrade the Tool to change this sidecar."));
    }
    binding(world, sidecar)
        .filter(i -> i.code().equals(SessionIssueCodes.WORLD_MISMATCH))
        .ifPresent(b::add);
    if (sidecar.recovery().map(r -> !r.findings().isEmpty()).orElse(false)) {
      b.add(
          block(SessionIssueCodes.RECOVERY_NEEDED, "An interrupted commit must be recovered first")
              .withRemediation("Run Recover."));
    }
    if (!sidecar.index().map(ChunkIndexStatus::usable).orElse(false)) {
      b.add(block(SessionIssueCodes.INDEX_UNUSABLE, "The chunk index is missing or damaged"));
    }
    for (OperationStatus op : sidecar.unfinishedOperations()) {
      b.add(
          block(
                  SessionIssueCodes.OPERATION_UNFINISHED,
                  "Operation " + op.operation() + " is unfinished (" + op.integrity() + ")")
              .withRemediation(op.recommendation().explanation()));
    }
  }

  private static void serverStopped(WorldInspection world, List<ValidationIssue> b) {
    if (!world.lock().allowsWrites()) {
      b.add(
          block(
                  SessionIssueCodes.SERVER_RUNNING,
                  "Minecraft has the world open (session.lock is " + world.lock() + ")")
              .withRemediation("Stop the server or close the game, then refresh."));
    }
  }

  private static ValidationIssue block(
      io.github.kjmorse2.mconeworld.domain.validation.IssueCode code, String message) {
    return ValidationIssue.error(code, IssueLocation.root(), message);
  }
}
