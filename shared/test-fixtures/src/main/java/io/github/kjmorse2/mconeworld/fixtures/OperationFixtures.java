// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.fixtures;

import static io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.archiveId;
import static io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.backupId;
import static io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.instant;
import static io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.operationId;
import static io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.playerId;
import static io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.seasonId;

import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkRectangle;
import io.github.kjmorse2.mconeworld.domain.operation.ActionKey;
import io.github.kjmorse2.mconeworld.domain.operation.BackupReference;
import io.github.kjmorse2.mconeworld.domain.operation.BackupRequirement;
import io.github.kjmorse2.mconeworld.domain.operation.ExpectedManifest;
import io.github.kjmorse2.mconeworld.domain.operation.FailureReason;
import io.github.kjmorse2.mconeworld.domain.operation.ImpactClass;
import io.github.kjmorse2.mconeworld.domain.operation.InputKey;
import io.github.kjmorse2.mconeworld.domain.operation.JournalEvent;
import io.github.kjmorse2.mconeworld.domain.operation.OperationPlan;
import io.github.kjmorse2.mconeworld.domain.operation.OperationTarget;
import io.github.kjmorse2.mconeworld.domain.operation.OperationText;
import io.github.kjmorse2.mconeworld.domain.operation.OperationType;
import io.github.kjmorse2.mconeworld.domain.operation.PreconditionSnapshot;
import io.github.kjmorse2.mconeworld.domain.operation.RollbackDeclaration;
import io.github.kjmorse2.mconeworld.domain.operation.RollbackStrategy;
import io.github.kjmorse2.mconeworld.domain.operation.StepDescriptor;
import io.github.kjmorse2.mconeworld.domain.operation.ToolIdentity;
import io.github.kjmorse2.mconeworld.domain.operation.ValidationDescriptor;
import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Deterministic operation plans and journal event sequences for tests. Nothing here reads the clock
 * or a random source.
 *
 * <p>Golden files: {@value #GOLDEN_PLAN_V1} is the encoding of {@link #fullPlan()}, and {@value
 * #GOLDEN_JOURNAL_V1} is the journal of {@link #goldenJournalEvents} for that plan, entry {@code n}
 * recorded at {@code instant(100 + n)}. Each has a {@code .meta} file with its format, schema
 * version, and SHA-256. To change one intentionally, regenerate it, review the diff, update the
 * {@code .meta}, and record the change in {@code docs/formats/operation-journal-v1.md}.
 */
public final class OperationFixtures {
  /** Classpath resource of the canonical v1 golden plan. */
  public static final String GOLDEN_PLAN_V1 =
      "/io/github/kjmorse2/mconeworld/fixtures/operation/v1/plan-full.json";

  /** Classpath resource of the canonical v1 golden journal. */
  public static final String GOLDEN_JOURNAL_V1 =
      "/io/github/kjmorse2/mconeworld/fixtures/operation/v1/journal-rolled-back.jsonl";

  /** A syntactically valid checksum whose hex is {@code n} repeated, for "some other digest". */
  public static Checksum checksum(char n) {
    return new Checksum(Checksum.Algorithm.SHA_256, String.valueOf(n).repeat(64));
  }

  /** A stand-in plan digest for domain tests; sidecar tests use the real digest. */
  public static final Checksum DIGEST = checksum('d');

  /** The backup the {@linkplain #fullPlan() full plan} requires. */
  public static final BackupReference BACKUP = new BackupReference(backupId(1), checksum('b'));

  private OperationFixtures() {}

  public static OperationText text(String value) {
    return OperationText.of(value);
  }

  /** The manifest state the fixture plans expect: revision 7 with checksum {@code 'a'…}. */
  public static ExpectedManifest manifestAt7() {
    return new ExpectedManifest.AtRevision(7, checksum('a'));
  }

  /** Preconditions with a manifest state, two input checksums, and a data-version fact. */
  public static PreconditionSnapshot preconditions() {
    TreeMap<InputKey, Checksum> checksums = new TreeMap<>();
    checksums.put(InputKey.of("world:level.dat"), checksum('1'));
    checksums.put(InputKey.of("world:region/r.-1.0.mca"), checksum('2'));
    TreeMap<InputKey, String> facts = new TreeMap<>();
    facts.put(InputKey.of("minecraft:data_version"), "4903");
    return new PreconditionSnapshot(manifestAt7(), checksums, facts);
  }

  /**
   * A destructive plan using every target kind, three steps (only step 1 retry-safe), two checks,
   * an existing backup, and the restore-backup rollback strategy.
   */
  public static OperationPlan fullPlan() {
    DimensionKey overworld = DimensionKey.parse("minecraft:overworld");
    List<OperationTarget> targets =
        List.of(
            new OperationTarget.ChunkArea(new ChunkRectangle(overworld, -32, 0, -1, 31)),
            new OperationTarget.Player(playerId(1)),
            new OperationTarget.Season(seasonId(1)),
            new OperationTarget.SidecarManifest(),
            new OperationTarget.Extension(ActionKey.of("example-plugin:warp"), "spawn-hub"));
    List<StepDescriptor> steps =
        List.of(
            new StepDescriptor(
                ActionKey.of("mconeworld:delete_chunks"),
                text("Delete the selected chunks and their entity and POI data"),
                false,
                List.of(0)),
            new StepDescriptor(
                ActionKey.of("mconeworld:reset_player_progress"),
                text("Reset the player's season progression"),
                true,
                List.of(1, 2)),
            new StepDescriptor(
                ActionKey.of("mconeworld:commit_sidecar"),
                text("Record the deletion in the sidecar"),
                false,
                List.of(2, 3, 4)));
    List<ValidationDescriptor> checks =
        List.of(
            new ValidationDescriptor(
                ActionKey.of("mconeworld:verify_regions"), text("Reopen every touched region")),
            new ValidationDescriptor(
                ActionKey.of("mconeworld:verify_manifest"), text("Re-read the manifest")));
    return new OperationPlan(
        operationId(1),
        OperationType.of("mconeworld:chunk_deletion"),
        instant(60),
        ToolIdentity.of("mconeworld-tool", "0.1.0"),
        archiveId(1),
        ImpactClass.DESTRUCTIVE,
        targets,
        preconditions(),
        new BackupRequirement.ExistingBackup(BACKUP),
        steps,
        checks,
        new RollbackDeclaration(
            RollbackStrategy.RESTORE_BACKUP, text("Restore backup 1 over the world directory")));
  }

  /**
   * A small plan with the given step retry-safety flags and {@code checks} checks, requiring a new
   * backup and no targets.
   */
  public static OperationPlan plan(List<Boolean> retrySafe, int checks) {
    List<StepDescriptor> steps = new ArrayList<>();
    for (int i = 0; i < retrySafe.size(); i++) {
      steps.add(
          new StepDescriptor(
              ActionKey.of("test:step_" + i), text("Step " + i), retrySafe.get(i), List.of()));
    }
    List<ValidationDescriptor> validations = new ArrayList<>();
    for (int i = 0; i < checks; i++) {
      validations.add(
          new ValidationDescriptor(ActionKey.of("test:check_" + i), text("Check " + i)));
    }
    return new OperationPlan(
        operationId(2),
        OperationType.of("test:operation"),
        instant(0),
        ToolIdentity.of("test", "1"),
        archiveId(1),
        ImpactClass.WORLD_MUTATING,
        List.of(),
        preconditions(),
        new BackupRequirement.NewBackup(),
        steps,
        validations,
        new RollbackDeclaration(RollbackStrategy.RESTORE_BACKUP, text("Restore the backup")));
  }

  /** A failure reason with code {@code test.failure}. */
  public static FailureReason failure(String message) {
    return new FailureReason(IssueCode.of("test.failure"), text(message));
  }

  /** The {@code BACKUP_CONFIRMED} event that satisfies {@code plan}. */
  public static JournalEvent backupConfirmedFor(OperationPlan plan) {
    return new JournalEvent.BackupConfirmed(
        switch (plan.backup()) {
          case BackupRequirement.NewBackup n -> BACKUP;
          case BackupRequirement.ExistingBackup e -> e.backup();
          case BackupRequirement.NotRequired n -> null;
        });
  }

  /** The {@code APPLY_STARTED} event that satisfies {@code plan} with digest {@code digest}. */
  public static JournalEvent applyStartedFor(OperationPlan plan, Checksum digest) {
    return new JournalEvent.ApplyStarted(digest, plan.preconditions());
  }

  /** Events after the first that take a plan to APPLYING with no step started. */
  public static List<JournalEvent> toApplying(OperationPlan plan, Checksum digest) {
    return List.of(backupConfirmedFor(plan), applyStartedFor(plan, digest));
  }

  /** Events after the first that complete every step, each on its first attempt. */
  public static List<JournalEvent> toAllStepsCompleted(OperationPlan plan, Checksum digest) {
    List<JournalEvent> events = new ArrayList<>(toApplying(plan, digest));
    for (int i = 0; i < plan.steps().size(); i++) {
      events.add(new JournalEvent.StepStarted(i, 1));
      events.add(new JournalEvent.StepCompleted(i));
    }
    return events;
  }

  /** Events after the first that complete the whole operation. */
  public static List<JournalEvent> toCompleted(OperationPlan plan, Checksum digest) {
    List<JournalEvent> events = new ArrayList<>(toAllStepsCompleted(plan, digest));
    events.add(new JournalEvent.ValidationStarted());
    for (int i = 0; i < plan.validations().size(); i++) {
      events.add(new JournalEvent.CheckPassed(i));
    }
    events.add(new JournalEvent.OperationCompleted());
    return events;
  }

  /**
   * The events of the golden journal for {@link #fullPlan()}: backup and apply, step 0, an
   * interrupted step 1 retried once, a failure, a failed rollback attempt, and a successful one.
   */
  public static List<JournalEvent> goldenJournalEvents(Checksum digest) {
    OperationPlan plan = fullPlan();
    return List.of(
        backupConfirmedFor(plan),
        applyStartedFor(plan, digest),
        new JournalEvent.StepStarted(0, 1),
        new JournalEvent.StepCompleted(0),
        new JournalEvent.StepStarted(1, 1),
        new JournalEvent.StepStarted(1, 2),
        new JournalEvent.OperationFailed(failure("Region file r.-1.0.mca is unreadable"), 1),
        new JournalEvent.RollbackStarted(1),
        new JournalEvent.RollbackFailed(failure("Backup volume offline")),
        new JournalEvent.RollbackStarted(2),
        new JournalEvent.RollbackCompleted());
  }

  /**
   * Events after the first that fail step 0 and roll back successfully on the second attempt, so
   * the failed attempt stays in the history.
   */
  public static List<JournalEvent> toRolledBack(OperationPlan plan, Checksum digest) {
    List<JournalEvent> events = new ArrayList<>(toApplying(plan, digest));
    events.add(new JournalEvent.StepStarted(0, 1));
    events.add(new JournalEvent.OperationFailed(failure("Region file unreadable"), 0));
    events.add(new JournalEvent.RollbackStarted(1));
    events.add(new JournalEvent.RollbackFailed(failure("Backup volume offline")));
    events.add(new JournalEvent.RollbackStarted(2));
    events.add(new JournalEvent.RollbackCompleted());
    return events;
  }
}
