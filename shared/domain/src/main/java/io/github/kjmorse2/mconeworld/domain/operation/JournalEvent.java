// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.operation;

import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.domain.OperationId;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One journal event: a durable statement that something is about to happen or has happened.
 *
 * <p>The events that <em>start</em> a mutation ({@link StepStarted}, {@link RollbackStarted}) are
 * recorded before the mutation begins; the caller may begin it only after the journal has durably
 * acknowledged the event. The events that <em>report</em> an outcome are recorded after it. So
 * after a crash, the journal errs on the side of "may have started", never on "certainly not
 * started".
 *
 * <p>Step, check, and attempt numbers are 0-based indexes and 1-based attempt counters.
 */
public sealed interface JournalEvent {

  /** The event's kind. */
  JournalEventType type();

  /**
   * The first event of every journal: the plan with this digest is recorded.
   *
   * @param operation the plan's id
   * @param operationType the plan's type
   * @param planDigest the plan digest
   */
  record PlanRecorded(OperationId operation, OperationType operationType, Checksum planDigest)
      implements JournalEvent {
    public PlanRecorded {
      Objects.requireNonNull(operation, "operation");
      Objects.requireNonNull(operationType, "operationType");
      Objects.requireNonNull(planDigest, "planDigest");
    }

    @Override
    public JournalEventType type() {
      return JournalEventType.PLAN_RECORDED;
    }
  }

  /**
   * The backup requirement is satisfied.
   *
   * @param backup the verified backup, or {@code null} for a plan that requires none
   */
  record BackupConfirmed(@Nullable BackupReference backup) implements JournalEvent {
    @Override
    public JournalEventType type() {
      return JournalEventType.BACKUP_CONFIRMED;
    }
  }

  /**
   * The operation is abandoned before anything was applied, for example because a precondition
   * changed.
   *
   * @param reason why
   */
  record Cancelled(FailureReason reason) implements JournalEvent {
    public Cancelled {
      Objects.requireNonNull(reason, "reason");
    }

    @Override
    public JournalEventType type() {
      return JournalEventType.CANCELLED;
    }
  }

  /**
   * A person approved the plan with this digest, and the preconditions were just observed as
   * recorded here.
   *
   * @param approvedPlanDigest the digest the approver saw; it must equal the recorded digest
   * @param observed the state observed immediately before applying; it must equal the plan's
   *     preconditions
   */
  record ApplyStarted(Checksum approvedPlanDigest, PreconditionSnapshot observed)
      implements JournalEvent {
    public ApplyStarted {
      Objects.requireNonNull(approvedPlanDigest, "approvedPlanDigest");
      Objects.requireNonNull(observed, "observed");
    }

    @Override
    public JournalEventType type() {
      return JournalEventType.APPLY_STARTED;
    }
  }

  /**
   * Step {@code step} is about to start its {@code attempt}-th attempt.
   *
   * @param step 0-based step index
   * @param attempt 1-based attempt number
   */
  record StepStarted(int step, int attempt) implements JournalEvent {
    @Override
    public JournalEventType type() {
      return JournalEventType.STEP_STARTED;
    }
  }

  /**
   * Step {@code step} finished.
   *
   * @param step 0-based step index
   */
  record StepCompleted(int step) implements JournalEvent {
    @Override
    public JournalEventType type() {
      return JournalEventType.STEP_COMPLETED;
    }
  }

  /** Every step finished; validation checks are starting. */
  record ValidationStarted() implements JournalEvent {
    @Override
    public JournalEventType type() {
      return JournalEventType.VALIDATION_STARTED;
    }
  }

  /**
   * Check {@code check} passed.
   *
   * @param check 0-based check index
   */
  record CheckPassed(int check) implements JournalEvent {
    @Override
    public JournalEventType type() {
      return JournalEventType.CHECK_PASSED;
    }
  }

  /** Every check passed; the operation is complete. */
  record OperationCompleted() implements JournalEvent {
    @Override
    public JournalEventType type() {
      return JournalEventType.OPERATION_COMPLETED;
    }
  }

  /**
   * Applying or validating failed.
   *
   * @param reason why
   * @param step the step that was in progress, or {@code null} if none was
   */
  record OperationFailed(FailureReason reason, @Nullable Integer step) implements JournalEvent {
    public OperationFailed {
      Objects.requireNonNull(reason, "reason");
    }

    @Override
    public JournalEventType type() {
      return JournalEventType.OPERATION_FAILED;
    }
  }

  /**
   * A rollback attempt is about to start.
   *
   * @param attempt 1-based attempt number
   */
  record RollbackStarted(int attempt) implements JournalEvent {
    @Override
    public JournalEventType type() {
      return JournalEventType.ROLLBACK_STARTED;
    }
  }

  /** The rollback finished and was verified. */
  record RollbackCompleted() implements JournalEvent {
    @Override
    public JournalEventType type() {
      return JournalEventType.ROLLBACK_COMPLETED;
    }
  }

  /**
   * The rollback attempt failed.
   *
   * @param reason why
   */
  record RollbackFailed(FailureReason reason) implements JournalEvent {
    public RollbackFailed {
      Objects.requireNonNull(reason, "reason");
    }

    @Override
    public JournalEventType type() {
      return JournalEventType.ROLLBACK_FAILED;
    }
  }
}
