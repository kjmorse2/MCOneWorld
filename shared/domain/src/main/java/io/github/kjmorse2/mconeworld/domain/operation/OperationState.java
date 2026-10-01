// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.operation;

/**
 * The durable state of an operation, derived only from its journal. The names are persisted
 * verbatim. Legal transitions: {@link OperationTransitions}.
 *
 * <pre>
 * PLANNED ─▶ BACKED_UP ─▶ APPLYING ─▶ VALIDATING ─▶ COMPLETED
 *    │           │           │            │
 *    ▼           ▼           ▼            ▼
 * CANCELLED  CANCELLED     FAILED ◀───────┘
 *                            │
 *                            ▼
 *                       ROLLING_BACK ─▶ ROLLED_BACK
 *                         ▲     │
 *                         │     ▼
 *                        ROLLBACK_FAILED
 * </pre>
 */
public enum OperationState {
  /** The plan is recorded. Nothing has changed. */
  PLANNED,
  /** The plan's backup requirement is confirmed. Nothing has changed. */
  BACKED_UP,
  /** Preconditions were re-verified and steps are running. Steps may have changed state. */
  APPLYING,
  /** Every step completed; read-only checks are running. */
  VALIDATING,
  /** Every check passed. Terminal. */
  COMPLETED,
  /** Applying or validating failed. The effects of started steps may be present. */
  FAILED,
  /** A rollback attempt is running. */
  ROLLING_BACK,
  /** The rollback completed. Terminal. */
  ROLLED_BACK,
  /** A rollback attempt failed. Another attempt, or a person, is needed. */
  ROLLBACK_FAILED,
  /** The operation was abandoned before anything was applied. Terminal. */
  CANCELLED;

  /** Returns whether no further event is legal. */
  public boolean isTerminal() {
    return this == COMPLETED || this == ROLLED_BACK || this == CANCELLED;
  }
}
