// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.operation;

import java.time.Instant;
import java.util.Objects;

/**
 * One durable journal entry. The persisted form adds integrity data (a per-entry checksum and a
 * link to the previous entry); see {@code operation-journal-v1.md}.
 *
 * @param sequence 0-based position in the journal; entry {@code n} is always the {@code n}-th
 * @param recordedAt when the event was recorded (UTC), for audit only: ordering uses {@code
 *     sequence}, never clocks
 * @param event what happened
 * @param stateAfter the state after this event. It is redundant with replay and checked against it,
 *     so a reader can see the state without replaying, and a mismatch reveals corruption.
 */
public record JournalEntry(
    long sequence, Instant recordedAt, JournalEvent event, OperationState stateAfter) {
  /**
   * @throws IllegalArgumentException if {@code sequence} is negative
   */
  public JournalEntry {
    Objects.requireNonNull(recordedAt, "recordedAt");
    Objects.requireNonNull(event, "event");
    Objects.requireNonNull(stateAfter, "stateAfter");
    if (sequence < 0) {
      throw new IllegalArgumentException("Journal sequence must not be negative");
    }
  }
}
