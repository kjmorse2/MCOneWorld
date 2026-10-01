// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.operation;

/** How a failed operation is undone. The names are persisted verbatim. */
public enum RollbackStrategy {
  /** Restore the confirmed backup. Requires a plan with a backup. */
  RESTORE_BACKUP,
  /** Run the inverse of each completed step, newest first. */
  INVERSE_STEPS,
  /** A person follows the declared instructions, then records the outcome. */
  MANUAL,
  /**
   * Nothing to undo: only for {@link ImpactClass#SIDECAR_MUTATING} plans, whose sidecar commit is
   * atomic. The rollback events are still recorded, so the journal ends in a clear state.
   */
  NOT_APPLICABLE
}
