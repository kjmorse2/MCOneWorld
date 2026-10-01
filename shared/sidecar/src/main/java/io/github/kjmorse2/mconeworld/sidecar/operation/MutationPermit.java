// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.operation;

import io.github.kjmorse2.mconeworld.domain.OperationId;
import org.jspecify.annotations.Nullable;

/**
 * Proof that the event starting a mutation is durably journaled. Only {@link OperationJournal}
 * creates permits, and only after the {@code STEP_STARTED} or {@code ROLLBACK_STARTED} entry is on
 * stable storage. Platform code that changes world or player state should require one, which
 * enforces "journal first, then mutate" in the type system.
 *
 * <p>An idempotent re-acknowledgement of an already recorded start event carries no permit: the
 * earlier acknowledgement may already have been acted on.
 */
public final class MutationPermit {
  /** What the permit authorizes. */
  public enum Kind {
    /** Running one attempt of one step. */
    STEP,
    /** Running one rollback attempt. */
    ROLLBACK
  }

  private final OperationId operation;
  private final long sequence;
  private final Kind kind;
  private final @Nullable Integer step;
  private final int attempt;

  MutationPermit(
      OperationId operation, long sequence, Kind kind, @Nullable Integer step, int attempt) {
    this.operation = operation;
    this.sequence = sequence;
    this.kind = kind;
    this.step = step;
    this.attempt = attempt;
  }

  public OperationId operation() {
    return operation;
  }

  /** The sequence number of the journal entry that granted this permit. */
  public long sequence() {
    return sequence;
  }

  public Kind kind() {
    return kind;
  }

  /** The step to run, for {@link Kind#STEP}; otherwise {@code null}. */
  public @Nullable Integer step() {
    return step;
  }

  /** The 1-based attempt number. */
  public int attempt() {
    return attempt;
  }

  @Override
  public String toString() {
    return "MutationPermit["
        + operation
        + " #"
        + sequence
        + " "
        + kind
        + (step == null ? "" : " step " + step)
        + " attempt "
        + attempt
        + "]";
  }
}
