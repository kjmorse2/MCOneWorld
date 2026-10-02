// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.protocol;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import java.io.Serial;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A payload was rejected. Decoding throws only this (never another exception) for any input, so an
 * adapter can treat every malformed packet uniformly: log the code and ignore the packet.
 */
public final class ProtocolException extends Exception {
  @Serial private static final long serialVersionUID = 1L;

  private final transient IssueCode code;
  private final int offset;

  /**
   * @param offset the byte offset where the problem was found, or -1 if not tied to one
   */
  public ProtocolException(IssueCode code, String message, int offset, @Nullable Throwable cause) {
    super(offset < 0 ? message : message + " (at byte " + offset + ")", cause);
    this.code = Objects.requireNonNull(code, "code");
    this.offset = offset;
  }

  public ProtocolException(IssueCode code, String message, int offset) {
    this(code, message, offset, null);
  }

  /** Branch on this, never on the message. */
  public IssueCode code() {
    return code;
  }

  /** The byte offset of the problem, or -1. */
  public int offset() {
    return offset;
  }
}
