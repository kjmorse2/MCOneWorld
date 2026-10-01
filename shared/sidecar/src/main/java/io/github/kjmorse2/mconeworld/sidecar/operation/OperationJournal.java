// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.operation;

import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.domain.OperationId;
import io.github.kjmorse2.mconeworld.domain.operation.JournalEntry;
import io.github.kjmorse2.mconeworld.domain.operation.JournalEvent;
import io.github.kjmorse2.mconeworld.domain.operation.OperationProgress;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationResult;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarPath;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarWriter;
import io.github.kjmorse2.mconeworld.sidecar.storage.StorageException;
import io.github.kjmorse2.mconeworld.sidecar.storage.StorageIssueCodes;
import java.io.IOException;
import java.time.Clock;
import org.jspecify.annotations.Nullable;

/**
 * An open, appendable journal, bound to a {@link SidecarWriter} and its lock. Not thread-safe.
 *
 * <p><b>Appending</b> ({@link #append}) is the only way to advance an operation:
 *
 * <ol>
 *   <li>The writer lock is verified, and {@code expectedSequence} must be the next sequence number.
 *       This rejects reordered, missing, and stale appends deterministically ({@code
 *       SEQUENCE_CONFLICT}).
 *   <li>Re-sending exactly the last recorded event with its sequence number is idempotent: it
 *       returns an acknowledgement marked {@code alreadyRecorded}, without writing and without a
 *       {@link MutationPermit}. Any other repeat is rejected.
 *   <li>The event must pass every rule of {@link OperationProgress} ({@code EVENT_REJECTED}, with
 *       the domain issues as details). A rejected event changes nothing.
 *   <li>The entry is appended and synced. Only then is a {@link JournalAck} returned: the durable
 *       acknowledgement the caller must have before the next mutating stage.
 * </ol>
 *
 * <p>If writing fails, the entry may or may not be on disk. The handle is then broken ({@code
 * HANDLE_BROKEN}): reopen the journal, which re-reads the durable state, before trying again.
 */
public final class OperationJournal {
  private final SidecarWriter writer;
  private final JournalEntryCodec codec;
  private final Clock clock;
  private final SidecarPath path;
  private OperationProgress progress;
  private long size;
  private JournalEntry last;
  private Checksum lastChecksum;
  private boolean broken;

  OperationJournal(
      SidecarWriter writer,
      JournalEntryCodec codec,
      Clock clock,
      SidecarPath path,
      OperationProgress progress,
      long size,
      JournalEntry last,
      Checksum lastChecksum) {
    this.writer = writer;
    this.codec = codec;
    this.clock = clock;
    this.path = path;
    this.progress = progress;
    this.size = size;
    this.last = last;
    this.lastChecksum = lastChecksum;
  }

  public OperationId operation() {
    return progress.plan().id();
  }

  /** The progress after every acknowledged entry. */
  public OperationProgress progress() {
    return progress;
  }

  /**
   * Appends {@code event} as entry {@code expectedSequence} and returns once it is durable.
   *
   * @throws JournalException {@code SEQUENCE_CONFLICT}, {@code EVENT_REJECTED}, {@code
   *     HANDLE_STALE}, {@code HANDLE_BROKEN}, or {@code TOO_LARGE}
   * @throws StorageException {@code LOCK_LOST} or an I/O failure (the handle is then broken)
   */
  public JournalAck append(long expectedSequence, JournalEvent event) throws IOException {
    if (broken) {
      throw new JournalException(
          JournalIssueCodes.HANDLE_BROKEN,
          "An earlier append through this handle failed; reopen the journal");
    }
    writer.verifyLockHeld();
    long next = progress.nextSequence();
    if (expectedSequence == next - 1 && last.event().equals(event)) {
      return new JournalAck(
          operation(), last.sequence(), last.stateAfter(), lastChecksum, true, null);
    }
    if (expectedSequence != next) {
      throw new JournalException(
          JournalIssueCodes.SEQUENCE_CONFLICT,
          "The next entry is " + next + ", not " + expectedSequence);
    }
    ValidationResult check = progress.check(event);
    if (!check.isValid()) {
      throw new JournalException(
          JournalIssueCodes.EVENT_REJECTED,
          event.type() + " was rejected: " + check.issues().get(0).message(),
          check,
          null,
          null);
    }
    JournalEntry entry = progress.nextEntry(event, clock.instant());
    JournalEntryCodec.Encoded encoded = codec.encode(entry, operation(), lastChecksum);
    byte[] line = encoded.line();
    if (line.length > OperationPlanLimits.MAX_ENTRY_BYTES
        || size + line.length > OperationPlanLimits.MAX_JOURNAL_BYTES) {
      throw new JournalException(
          JournalIssueCodes.TOO_LARGE, "The entry would exceed the journal size limits");
    }
    try {
      writer.operationFiles().append(path, size, line);
    } catch (StorageException e) {
      broken = true;
      if (e.code().equals(StorageIssueCodes.BASE_CHANGED)) {
        throw new JournalException(
            JournalIssueCodes.HANDLE_STALE,
            "The journal changed on disk since this handle read it; reopen it",
            ValidationResult.ok(),
            null,
            e);
      }
      throw e;
    } catch (IOException | RuntimeException e) {
      broken = true;
      throw e;
    }
    progress = progress.apply(entry);
    size += line.length;
    last = entry;
    lastChecksum = encoded.checksum();
    return new JournalAck(
        operation(), entry.sequence(), entry.stateAfter(), lastChecksum, false, permitFor(entry));
  }

  private @Nullable MutationPermit permitFor(JournalEntry entry) {
    return switch (entry.event()) {
      case JournalEvent.StepStarted s ->
          new MutationPermit(
              operation(), entry.sequence(), MutationPermit.Kind.STEP, s.step(), s.attempt());
      case JournalEvent.RollbackStarted r ->
          new MutationPermit(
              operation(), entry.sequence(), MutationPermit.Kind.ROLLBACK, null, r.attempt());
      default -> null;
    };
  }
}
