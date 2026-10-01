// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.operation;

/**
 * How far an operation reaches (root {@code AGENTS.md} §18). It decides which backup and rollback
 * declarations a plan may use; see {@link OperationPlan}.
 */
public enum ImpactClass {
  /**
   * Changes only the sidecar, through sidecar transactions, whose single atomic commit point
   * already guarantees all-or-nothing. It alone may declare no backup and no rollback.
   */
  SIDECAR_MUTATING,
  /** Changes world or player state in a way the declared inverse steps can undo. */
  REVERSIBLE,
  /** Changes world or player state; undoing it needs the backup. */
  WORLD_MUTATING,
  /** Deletes or overwrites world or player data. Undoing it needs the backup. */
  DESTRUCTIVE;

  /** Returns whether world or player state may change, so a verified backup is mandatory. */
  public boolean requiresBackup() {
    return this != SIDECAR_MUTATING;
  }
}
