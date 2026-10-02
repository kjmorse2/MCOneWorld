// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.world;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import java.io.IOException;
import java.io.Serial;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** A world directory could not be opened; {@link #code()} says why. */
public final class WorldException extends IOException {
  @Serial private static final long serialVersionUID = 1L;

  private final transient IssueCode code;

  public WorldException(IssueCode code, String message, @Nullable Throwable cause) {
    super(message, cause);
    this.code = Objects.requireNonNull(code, "code");
  }

  public WorldException(IssueCode code, String message) {
    this(code, message, null);
  }

  /** Branch on this, never on the message. */
  public IssueCode code() {
    return code;
  }
}
