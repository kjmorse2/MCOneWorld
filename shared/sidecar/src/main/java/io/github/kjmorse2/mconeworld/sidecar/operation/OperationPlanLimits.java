// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.operation;

import io.github.kjmorse2.mconeworld.domain.operation.OperationPlan;

/** Byte and size limits of plan and journal files (operation-journal-v1.md §5). */
public final class OperationPlanLimits {
  /** Largest plan file. The largest legal plan is well below 2 MiB. */
  public static final long MAX_PLAN_BYTES = 4L * 1024 * 1024;

  /**
   * Largest single journal entry, including its newline. Only {@code APPLY_STARTED}, which carries
   * a full precondition snapshot, can come near it.
   */
  public static final long MAX_ENTRY_BYTES = 1024L * 1024;

  /**
   * Largest journal file. A legal journal holds at most 2 581 entries (see {@code
   * OperationProgress.maxEntries}), of which one may be large; this leaves ample margin.
   */
  public static final long MAX_JOURNAL_BYTES = 16L * 1024 * 1024;

  /** Largest JSON array: the target list. */
  static final int MAX_ARRAY = OperationPlan.MAX_TARGETS;

  private OperationPlanLimits() {}
}
