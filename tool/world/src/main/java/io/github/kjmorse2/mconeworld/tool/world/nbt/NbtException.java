// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.world.nbt;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import java.io.IOException;
import java.io.Serial;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * An NBT input was rejected: {@code tool.world.nbt_malformed} or {@code tool.world.nbt_too_large}.
 * Plain I/O failures (a missing file, a read error) stay ordinary {@link IOException}s.
 */
public final class NbtException extends IOException {
  @Serial private static final long serialVersionUID = 1L;

  private final transient IssueCode code;

  public NbtException(IssueCode code, String message, @Nullable Throwable cause) {
    super(message, cause);
    this.code = Objects.requireNonNull(code, "code");
  }

  public NbtException(IssueCode code, String message) {
    this(code, message, null);
  }

  /** Branch on this, never on the message. */
  public IssueCode code() {
    return code;
  }
}
