// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.operation;

/**
 * An action a {@link RecoveryRecommendation} may propose. Each names the journal events the caller
 * records if a person (or an explicitly configured policy) chooses it. Nothing is ever done
 * automatically.
 */
public enum RecoveryAction {
  /** The operation is finished; nothing to do. */
  NONE,
  /**
   * The plan was written but its journal never started, so nothing was applied: record the plan
   * again (the journal store's {@code create} is idempotent for the same plan), or leave it.
   */
  RECORD_PLAN,
  /** Create or re-verify the backup, then record {@code BACKUP_CONFIRMED}. */
  CONFIRM_BACKUP,
  /** Re-observe the preconditions, get approval, then record {@code APPLY_STARTED}. */
  START_APPLY,
  /** Continue with the next step: record {@code STEP_STARTED} for {@code nextStep}, attempt 1. */
  RESUME_APPLY,
  /**
   * Restart the interrupted, retry-safe step: record {@code STEP_STARTED} for {@code nextStep} with
   * the next attempt number.
   */
  RETRY_STEP,
  /** Every step completed: record {@code VALIDATION_STARTED}. */
  START_VALIDATION,
  /** Continue validation: run check {@code nextCheck}, then record {@code CHECK_PASSED}. */
  RESUME_VALIDATION,
  /** Every check passed: record {@code OPERATION_COMPLETED}. */
  RECORD_COMPLETION,
  /** Record {@code OPERATION_FAILED}, then start a rollback. */
  FAIL_AND_ROLLBACK,
  /** Record {@code ROLLBACK_STARTED} and roll back as the plan declares. */
  ROLLBACK,
  /** Record {@code ROLLBACK_STARTED} with the next attempt number and roll back again. */
  RETRY_ROLLBACK,
  /** Record {@code CANCELLED}. Only offered while nothing has been applied. */
  CANCEL,
  /**
   * The durable state cannot safely be continued by software: a person must inspect the world, the
   * sidecar, and the backup.
   */
  MANUAL_INSPECTION
}
