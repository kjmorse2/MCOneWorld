// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.backup;

import io.github.kjmorse2.mconeworld.domain.BackupId;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationResult;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The outcome of verifying one backup.
 *
 * @param id the backup verified
 * @param manifest its manifest, or {@code null} if it could not be read
 * @param issues every problem found; valid means every listed file is present and identical, and
 *     nothing unlisted is there
 */
public record BackupVerification(
    BackupId id, @Nullable BackupManifest manifest, ValidationResult issues) {
  public BackupVerification {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(issues, "issues");
  }

  /** Whether the backup is complete and intact. */
  public boolean verified() {
    return manifest != null && issues.isValid();
  }
}
