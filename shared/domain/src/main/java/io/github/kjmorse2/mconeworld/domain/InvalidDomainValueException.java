// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain;

import java.io.Serial;

/**
 * Thrown when a value cannot be represented as a valid domain value. This is the documented failure
 * of every checked factory and constructor in {@code shared:domain}.
 *
 * <p>Callers branch on {@link #code()}, never on the message text. It extends {@link
 * IllegalArgumentException} because every such failure is caused by an argument the caller
 * supplied.
 */
public final class InvalidDomainValueException extends IllegalArgumentException {
  @Serial private static final long serialVersionUID = 1L;

  private final DomainErrorCode code;

  /**
   * @param code stable machine-readable reason
   * @param message human-readable explanation, including the offending value where it is safe
   */
  public InvalidDomainValueException(DomainErrorCode code, String message) {
    super(message);
    this.code = code;
  }

  /** Returns the stable reason for the rejection. */
  public DomainErrorCode code() {
    return code;
  }
}
