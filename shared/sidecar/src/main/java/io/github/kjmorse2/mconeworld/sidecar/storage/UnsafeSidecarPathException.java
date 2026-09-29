// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.storage;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import java.io.Serial;

/** A path is not a valid {@link SidecarPath}. The stable code is {@link #CODE}. */
public final class UnsafeSidecarPathException extends IllegalArgumentException {
  @Serial private static final long serialVersionUID = 1L;

  /** Stable issue code for unsafe or non-portable sidecar paths. */
  public static final IssueCode CODE = IssueCode.of("sidecar.path.unsafe");

  UnsafeSidecarPathException(String path, String reason) {
    super(
        "Unsafe sidecar path \""
            + (path.length() <= 80 ? path : path.substring(0, 80) + "…")
            + "\": "
            + reason);
  }
}
