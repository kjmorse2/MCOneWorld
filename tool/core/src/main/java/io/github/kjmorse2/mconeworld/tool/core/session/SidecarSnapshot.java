// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.session;

import io.github.kjmorse2.mconeworld.domain.validation.ValidationResult;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexStatus;
import io.github.kjmorse2.mconeworld.sidecar.manifest.DecodedManifest;
import io.github.kjmorse2.mconeworld.sidecar.operation.JournalIntegrity;
import io.github.kjmorse2.mconeworld.sidecar.operation.OperationStatus;
import io.github.kjmorse2.mconeworld.sidecar.storage.RecoveryReport;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The sidecar as found, read-only.
 *
 * @param state what is at the location
 * @param manifest the manifest, when {@code state} is {@link SidecarState#PRESENT}
 * @param recovery leftovers of interrupted commits and the writer lock, when a directory exists
 * @param index the chunk index's status, when a manifest is present
 * @param operations every operation journal's status, in id order
 * @param findings the sidecar-level findings
 */
public record SidecarSnapshot(
    SidecarState state,
    Optional<DecodedManifest> manifest,
    Optional<RecoveryReport> recovery,
    Optional<ChunkIndexStatus> index,
    List<OperationStatus> operations,
    ValidationResult findings) {
  public SidecarSnapshot {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(manifest, "manifest");
    Objects.requireNonNull(recovery, "recovery");
    Objects.requireNonNull(index, "index");
    operations = List.copyOf(operations);
    Objects.requireNonNull(findings, "findings");
  }

  /** Whether another process holds the sidecar's writer lock right now. */
  public boolean lockedByAnotherProcess() {
    return recovery.map(r -> r.lock() != null && r.lock().held()).orElse(false);
  }

  /** Operations that are not finished or whose journal is damaged. */
  public List<OperationStatus> unfinishedOperations() {
    return operations.stream()
        .filter(
            op ->
                op.integrity() != JournalIntegrity.INTACT
                    || op.progress() == null
                    || !op.progress().state().isTerminal())
        .toList();
  }
}
