// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.operation;

/** The kinds of journal event. The names are persisted verbatim. See {@link JournalEvent}. */
public enum JournalEventType {
  PLAN_RECORDED,
  BACKUP_CONFIRMED,
  CANCELLED,
  APPLY_STARTED,
  STEP_STARTED,
  STEP_COMPLETED,
  VALIDATION_STARTED,
  CHECK_PASSED,
  OPERATION_COMPLETED,
  OPERATION_FAILED,
  ROLLBACK_STARTED,
  ROLLBACK_COMPLETED,
  ROLLBACK_FAILED;

  /**
   * Returns whether, once this event is durably acknowledged, the caller may change world or player
   * state: only these events authorize a mutation.
   */
  public boolean authorizesMutation() {
    return this == STEP_STARTED || this == ROLLBACK_STARTED;
  }
}
