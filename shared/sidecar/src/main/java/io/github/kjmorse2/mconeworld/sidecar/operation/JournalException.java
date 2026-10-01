// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.operation;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationResult;
import io.github.kjmorse2.mconeworld.sidecar.storage.StorageException;
import java.io.Serial;
import org.jspecify.annotations.Nullable;

/**
 * A journal operation was refused or failed. {@link #code()} is one of {@link JournalIssueCodes},
 * or a {@code sidecar.storage.*} code for storage failures. {@link #details()} carries the domain
 * rule violations for {@code EVENT_REJECTED}; {@link #status()} carries the journal's status when
 * it is not writable.
 */
public final class JournalException extends StorageException {
  @Serial private static final long serialVersionUID = 1L;

  private final transient ValidationResult details;
  private final transient @Nullable OperationStatus status;

  JournalException(IssueCode code, String message) {
    this(code, message, ValidationResult.ok(), null, null);
  }

  JournalException(
      IssueCode code,
      String message,
      ValidationResult details,
      @Nullable OperationStatus status,
      @Nullable Throwable cause) {
    super(code, message, cause);
    this.details = details;
    this.status = status;
  }

  /** The rule violations behind an {@code EVENT_REJECTED} refusal; otherwise empty. */
  public ValidationResult details() {
    return details;
  }

  /** The journal's status, when the refusal is about its state; otherwise {@code null}. */
  public @Nullable OperationStatus status() {
    return status;
  }
}
