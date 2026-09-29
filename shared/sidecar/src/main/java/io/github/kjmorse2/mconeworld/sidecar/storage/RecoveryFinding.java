// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.storage;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import java.util.List;
import java.util.Objects;

/**
 * One thing an interruption left behind, as classified by {@link SidecarStore#inspect()}.
 *
 * @param code stable classification: one of the {@code sidecar.recovery.*} codes in {@link
 *     StorageIssueCodes}
 * @param stagingEntry the staging directory's name (the transaction id if well-formed), or empty
 *     for manifest-level findings
 * @param message human-readable explanation
 * @param orphanedFiles files that the interrupted transaction moved into place, that the current
 *     manifest does not reference, and whose content still matches the transaction record. They are
 *     provably that transaction's own, and are removed by recovery.
 * @param automaticallyRecoverable whether {@link SidecarWriter#recover()} will resolve this finding
 *     by itself. Unexplained or ambiguous findings always need a person.
 */
public record RecoveryFinding(
    IssueCode code,
    String stagingEntry,
    String message,
    List<SidecarPath> orphanedFiles,
    boolean automaticallyRecoverable) {
  public RecoveryFinding {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(stagingEntry, "stagingEntry");
    Objects.requireNonNull(message, "message");
    orphanedFiles = List.copyOf(orphanedFiles);
  }
}
