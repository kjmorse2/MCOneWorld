// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.operation;

import io.github.kjmorse2.mconeworld.domain.OperationId;
import io.github.kjmorse2.mconeworld.domain.operation.OperationProgress;
import io.github.kjmorse2.mconeworld.domain.operation.RecoveryRecommendation;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationResult;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The read-only assessment of one operation: integrity, durable progress, and what to do next.
 * Producing it never writes anything.
 *
 * @param operation the operation
 * @param integrity how far the files can be trusted
 * @param plan the decoded plan, or {@code null} if it is unreadable
 * @param trustedEntries the number of journal entries that verified, from the start
 * @param completeLines the number of LF-terminated lines in the journal
 * @param progress the state after the trusted entries, or {@code null} if there are none
 * @param recommendation what to do; a recommendation only, never acted on automatically
 * @param issues every finding, most severe first
 * @param writable whether this reader may append now: the journal is intact, and neither the plan
 *     nor any entry comes from a newer minor version
 */
public record OperationStatus(
    OperationId operation,
    JournalIntegrity integrity,
    @Nullable DecodedPlan plan,
    long trustedEntries,
    long completeLines,
    @Nullable OperationProgress progress,
    RecoveryRecommendation recommendation,
    ValidationResult issues,
    boolean writable) {

  public OperationStatus {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(integrity, "integrity");
    Objects.requireNonNull(recommendation, "recommendation");
    Objects.requireNonNull(issues, "issues");
  }

  /**
   * Returns whether corruption was found before the last complete line, that is, valid-looking
   * entries follow a bad one. Distinguishes damage in the middle of the journal from damage to its
   * final entry.
   */
  public boolean corruptionInMiddle() {
    return integrity == JournalIntegrity.CORRUPT && trustedEntries + 1 < completeLines;
  }
}
