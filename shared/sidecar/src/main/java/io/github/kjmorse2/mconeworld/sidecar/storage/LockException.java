// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.storage;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import java.io.Serial;
import org.jspecify.annotations.Nullable;

/** The writer lock is held ({@code LOCK_CONTENDED}) or stale ({@code LOCK_STALE}). */
public final class LockException extends StorageException {
  @Serial private static final long serialVersionUID = 1L;

  private final transient @Nullable LockInfo owner;

  public LockException(IssueCode code, String message, @Nullable LockInfo owner) {
    super(code, message);
    this.owner = owner;
  }

  /** The recorded owner of the existing lock, if its metadata could be read. */
  public @Nullable LockInfo owner() {
    return owner;
  }
}
