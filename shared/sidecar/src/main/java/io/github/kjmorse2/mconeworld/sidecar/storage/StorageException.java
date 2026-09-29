// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.storage;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import java.io.IOException;
import java.io.Serial;
import org.jspecify.annotations.Nullable;

/** A storage operation failed. Branch on {@link #code()} (see {@link StorageIssueCodes}). */
public class StorageException extends IOException {
  @Serial private static final long serialVersionUID = 1L;

  private final transient IssueCode code;

  public StorageException(IssueCode code, String message) {
    super(message);
    this.code = code;
  }

  public StorageException(IssueCode code, String message, @Nullable Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  public IssueCode code() {
    return code;
  }
}
