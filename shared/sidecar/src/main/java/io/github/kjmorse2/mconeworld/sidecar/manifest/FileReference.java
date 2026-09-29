// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.manifest;

import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.DomainErrorCode;
import io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarPath;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A data file referenced by the manifest (for example a chunk index), identified by a safe relative
 * path and pinned by an algorithm-qualified checksum and size. Readers verify the file against both
 * before trusting it.
 *
 * @param role what the file is: {@code [a-z][a-z0-9-]{0,31}}, for example {@code chunk-index}.
 *     Unknown roles are preserved, not interpreted.
 * @param dimension the dimension the file covers, or {@code null} if not dimension-specific
 * @param path location relative to the sidecar root
 * @param checksum checksum of the file's exact bytes
 * @param sizeBytes size of the file in bytes; never negative
 */
public record FileReference(
    String role,
    @Nullable DimensionKey dimension,
    SidecarPath path,
    Checksum checksum,
    long sizeBytes) {
  private static final Pattern ROLE = Pattern.compile("[a-z][a-z0-9-]{0,31}");

  /**
   * @throws InvalidDomainValueException with {@code VALUE_INVALID} for a malformed role or a
   *     negative size
   */
  public FileReference {
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(checksum, "checksum");
    if (!ROLE.matcher(role).matches()) {
      throw new InvalidDomainValueException(DomainErrorCode.VALUE_INVALID, "Invalid file role");
    }
    if (sizeBytes < 0) {
      throw new InvalidDomainValueException(
          DomainErrorCode.VALUE_INVALID, "File size must not be negative");
    }
  }
}
