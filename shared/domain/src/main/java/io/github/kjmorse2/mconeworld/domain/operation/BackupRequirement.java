// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.operation;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What backup must be confirmed, by the {@code BACKUP_CONFIRMED} journal event, before an operation
 * may start applying.
 */
public sealed interface BackupRequirement {

  /**
   * Returns whether {@code confirmed}, the backup named by a {@code BACKUP_CONFIRMED} event, or
   * {@code null} for none, satisfies this requirement.
   */
  boolean acceptsConfirmation(@Nullable BackupReference confirmed);

  /** A new backup must be created and verified after planning; any verified backup satisfies it. */
  record NewBackup() implements BackupRequirement {
    @Override
    public boolean acceptsConfirmation(@Nullable BackupReference confirmed) {
      return confirmed != null;
    }
  }

  /**
   * A specific, already verified backup must be re-verified; only exactly it satisfies the
   * requirement.
   *
   * @param backup the backup
   */
  record ExistingBackup(BackupReference backup) implements BackupRequirement {
    public ExistingBackup {
      Objects.requireNonNull(backup, "backup");
    }

    @Override
    public boolean acceptsConfirmation(@Nullable BackupReference confirmed) {
      return backup.equals(confirmed);
    }
  }

  /**
   * No backup: allowed only for {@link ImpactClass#SIDECAR_MUTATING} plans. The confirmation must
   * name no backup.
   *
   * @param justification why none is needed, for the audit record
   */
  record NotRequired(OperationText justification) implements BackupRequirement {
    public NotRequired {
      Objects.requireNonNull(justification, "justification");
    }

    @Override
    public boolean acceptsConfirmation(@Nullable BackupReference confirmed) {
      return confirmed == null;
    }
  }
}
