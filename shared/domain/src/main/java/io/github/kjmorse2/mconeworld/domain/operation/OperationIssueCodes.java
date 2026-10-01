// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.operation;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;

/**
 * Stable codes for rejected journal events and changed preconditions. They are a public contract,
 * pinned by tests: never rename or repurpose one.
 */
public final class OperationIssueCodes {
  /** The event is not legal in the current state ({@link OperationTransitions}). */
  public static final IssueCode TRANSITION_ILLEGAL = IssueCode.of("operation.transition.illegal");

  /** The entry's sequence number is not the next one: a duplicate, reordered, or missing entry. */
  public static final IssueCode SEQUENCE_MISMATCH = IssueCode.of("operation.sequence.mismatch");

  /** The entry's recorded state differs from the state its event leads to. */
  public static final IssueCode STATE_MISMATCH = IssueCode.of("operation.state.mismatch");

  /** The plan-recorded event names a different operation, type, or digest than the plan. */
  public static final IssueCode PLAN_MISMATCH = IssueCode.of("operation.plan.mismatch");

  /** The approved digest differs from the recorded plan digest: the plan changed after approval. */
  public static final IssueCode PLAN_CHANGED = IssueCode.of("operation.plan.changed");

  /** An observed precondition differs from the plan. */
  public static final IssueCode PRECONDITION_CHANGED =
      IssueCode.of("operation.precondition.changed");

  /** The confirmed backup does not satisfy the plan's backup requirement. */
  public static final IssueCode BACKUP_MISMATCH = IssueCode.of("operation.backup.mismatch");

  /** A step event names a step other than the one that is next or in progress. */
  public static final IssueCode STEP_OUT_OF_ORDER = IssueCode.of("operation.step.out_of_order");

  /** A step was restarted although the plan does not declare it retry-safe. */
  public static final IssueCode STEP_RETRY_NOT_ALLOWED =
      IssueCode.of("operation.step.retry_not_allowed");

  /** An attempt number is not exactly one more than the previous attempt. */
  public static final IssueCode ATTEMPT_MISMATCH = IssueCode.of("operation.attempt.mismatch");

  /** A step or rollback has used up its attempts. */
  public static final IssueCode ATTEMPTS_EXHAUSTED = IssueCode.of("operation.attempts.exhausted");

  /** Validation was started, or a failure named a step, while steps are incomplete. */
  public static final IssueCode STEPS_INCOMPLETE = IssueCode.of("operation.step.incomplete");

  /** A check was reported out of order, or completion was recorded before every check passed. */
  public static final IssueCode CHECK_OUT_OF_ORDER = IssueCode.of("operation.check.out_of_order");

  private OperationIssueCodes() {}
}
