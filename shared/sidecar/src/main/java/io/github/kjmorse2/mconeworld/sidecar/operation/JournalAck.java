// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.operation;

import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.domain.OperationId;
import io.github.kjmorse2.mconeworld.domain.operation.OperationState;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The durable acknowledgement of one journal entry: returned only after the entry is on stable
 * storage. The caller may proceed to the next stage only after receiving it.
 *
 * @param operation the operation
 * @param sequence the entry's sequence number
 * @param stateAfter the operation state after the entry
 * @param entryChecksum the entry's checksum
 * @param alreadyRecorded {@code true} if this acknowledges an identical entry that was already
 *     recorded (an idempotent retry), rather than a new one
 * @param permit for a newly recorded {@code STEP_STARTED} or {@code ROLLBACK_STARTED}, the
 *     permission to perform that mutation; otherwise {@code null}
 */
public record JournalAck(
    OperationId operation,
    long sequence,
    OperationState stateAfter,
    Checksum entryChecksum,
    boolean alreadyRecorded,
    @Nullable MutationPermit permit) {
  public JournalAck {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(stateAfter, "stateAfter");
    Objects.requireNonNull(entryChecksum, "entryChecksum");
  }
}
