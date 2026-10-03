// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.backup;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import java.io.IOException;
import java.io.Serial;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** A {@code backup.json} was rejected; {@link #code()} is one of {@link BackupIssueCodes}. */
public final class BackupFormatException extends IOException {
  @Serial private static final long serialVersionUID = 1L;

  private final transient IssueCode code;

  public BackupFormatException(IssueCode code, String message, @Nullable Throwable cause) {
    super(message, cause);
    this.code = Objects.requireNonNull(code, "code");
  }

  public BackupFormatException(IssueCode code, String message) {
    this(code, message, null);
  }

  public IssueCode code() {
    return code;
  }
}
