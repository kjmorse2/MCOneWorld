// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.storage;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Structured recovery information for a sidecar: leftover transactions, manifest readability, and
 * the writer-lock status. Producing it never writes anything.
 *
 * @param findings leftovers, in staging-directory name order
 * @param lock the writer lock status, or {@code null} if there is no lock file
 */
public record RecoveryReport(List<RecoveryFinding> findings, @Nullable LockStatus lock) {
  public RecoveryReport {
    findings = List.copyOf(findings);
  }

  /** Returns whether nothing needs attention: no findings, and no stale lock. */
  public boolean isClean() {
    LockStatus status = lock;
    return findings.isEmpty() && (status == null || !status.provablyStale());
  }

  /** Returns whether any finding requires a person, rather than {@link SidecarWriter#recover()}. */
  public boolean needsManualAttention() {
    return findings.stream().anyMatch(f -> !f.automaticallyRecoverable());
  }
}
