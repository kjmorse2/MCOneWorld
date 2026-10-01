// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.operation;

import io.github.kjmorse2.mconeworld.domain.ArchiveId;
import io.github.kjmorse2.mconeworld.domain.DomainErrorCode;
import io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException;
import io.github.kjmorse2.mconeworld.domain.OperationId;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * An immutable operation plan: the exact, reviewable description of one mutation, created before
 * anything changes (root {@code AGENTS.md} §10, steps 1–3).
 *
 * <p>Once recorded, a plan never changes. Its persisted form is digested (see {@code
 * operation-journal-v1.md} §"Plan digest"); the digest is recorded in the journal, and the person
 * approving the plan approves that digest. A plan whose digest no longer matches is refused.
 *
 * <p>The constructor enforces every rule, so an {@code OperationPlan} instance is always valid:
 *
 * <ul>
 *   <li>1 to {@value #MAX_STEPS} steps and 1 to {@value #MAX_VALIDATIONS} validation checks. At
 *       least one check is required, because a finished command is never proof of success.
 *   <li>at most {@value #MAX_TARGETS} targets, with no duplicates; every step's target index exists
 *   <li>{@link BackupRequirement.NotRequired} and {@link RollbackStrategy#NOT_APPLICABLE} only for
 *       {@link ImpactClass#SIDECAR_MUTATING}
 *   <li>{@link RollbackStrategy#RESTORE_BACKUP} only with a backup
 * </ul>
 *
 * @param id the stable operation identity, shared with the journal
 * @param type what kind of operation this is
 * @param createdAt when the plan was created (UTC)
 * @param createdBy the program that created it
 * @param archive the sidecar history this plan belongs to (the manifest's archive id)
 * @param impact how far the operation reaches
 * @param targets exact logical targets, in a stable order that step indexes refer to
 * @param preconditions the state the plan was made against
 * @param backup the backup that must be confirmed before applying
 * @param steps ordered steps
 * @param validations ordered post-apply checks
 * @param rollback the declared rollback strategy
 */
public record OperationPlan(
    OperationId id,
    OperationType type,
    Instant createdAt,
    ToolIdentity createdBy,
    ArchiveId archive,
    ImpactClass impact,
    List<OperationTarget> targets,
    PreconditionSnapshot preconditions,
    BackupRequirement backup,
    List<StepDescriptor> steps,
    List<ValidationDescriptor> validations,
    RollbackDeclaration rollback) {

  /** Maximum number of targets. */
  public static final int MAX_TARGETS = 4096;

  /** Maximum number of steps. */
  public static final int MAX_STEPS = 256;

  /** Maximum number of validation checks. */
  public static final int MAX_VALIDATIONS = 256;

  /**
   * @throws InvalidDomainValueException with {@link DomainErrorCode#OPERATION_PLAN_INVALID} if a
   *     rule above is broken
   */
  public OperationPlan {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(createdBy, "createdBy");
    Objects.requireNonNull(archive, "archive");
    Objects.requireNonNull(impact, "impact");
    Objects.requireNonNull(preconditions, "preconditions");
    Objects.requireNonNull(backup, "backup");
    Objects.requireNonNull(rollback, "rollback");
    targets = List.copyOf(targets);
    steps = List.copyOf(steps);
    validations = List.copyOf(validations);

    require(!steps.isEmpty() && steps.size() <= MAX_STEPS, "1 to " + MAX_STEPS + " steps");
    require(
        !validations.isEmpty() && validations.size() <= MAX_VALIDATIONS,
        "1 to " + MAX_VALIDATIONS + " validation checks");
    require(targets.size() <= MAX_TARGETS, "at most " + MAX_TARGETS + " targets");
    require(new HashSet<>(targets).size() == targets.size(), "no duplicate targets");
    for (StepDescriptor step : steps) {
      for (int index : step.targets()) {
        require(index < targets.size(), "step target indexes refer to existing targets");
      }
    }
    boolean sidecarOnly = impact == ImpactClass.SIDECAR_MUTATING;
    require(
        sidecarOnly || !(backup instanceof BackupRequirement.NotRequired),
        "a backup for " + impact + " operations");
    require(
        sidecarOnly || rollback.strategy() != RollbackStrategy.NOT_APPLICABLE,
        "a rollback strategy other than NOT_APPLICABLE for " + impact + " operations");
    require(
        rollback.strategy() != RollbackStrategy.RESTORE_BACKUP
            || !(backup instanceof BackupRequirement.NotRequired),
        "a backup when the rollback strategy is RESTORE_BACKUP");
  }

  private static void require(boolean condition, String rule) {
    if (!condition) {
      throw new InvalidDomainValueException(
          DomainErrorCode.OPERATION_PLAN_INVALID, "An operation plan requires " + rule);
    }
  }
}
