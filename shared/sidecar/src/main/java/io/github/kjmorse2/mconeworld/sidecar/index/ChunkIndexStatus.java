// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import io.github.kjmorse2.mconeworld.domain.validation.Severity;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationResult;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What {@link ChunkIndexStore#inspect()} found, without reading any block: the committed state (if
 * the index loads) and findings about every file in {@code chunk-index/}. Inspection never writes.
 *
 * <p>Findings use {@link ChunkIndexIssueCodes}: errors such as a corrupt catalog or an unexplained
 * file; warnings such as a torn log tail or a newer minor version; information such as orphan
 * segments or stale logs, which are harmless and removable only by explicit maintenance.
 *
 * @param state the committed state, or {@code null} if there is no index or it cannot be loaded
 * @param findings everything worth reporting, sorted
 */
public record ChunkIndexStatus(@Nullable ChunkIndexState state, ValidationResult findings) {
  public ChunkIndexStatus {
    Objects.requireNonNull(findings, "findings");
  }

  /** Whether an index exists, loads, and has no error findings. */
  public boolean usable() {
    return state != null && findings.withSeverity(Severity.ERROR).isEmpty();
  }
}
