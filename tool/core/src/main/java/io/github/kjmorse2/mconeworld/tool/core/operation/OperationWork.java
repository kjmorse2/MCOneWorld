// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.operation;

import io.github.kjmorse2.mconeworld.domain.operation.BackupReference;
import io.github.kjmorse2.mconeworld.domain.operation.PreconditionSnapshot;
import java.io.IOException;
import org.jspecify.annotations.Nullable;

/** What one operation type does at each point of {@link OperationRunner}. */
public interface OperationWork {
  /** The backup to confirm, or {@code null} when the plan requires none. */
  @Nullable BackupReference confirmedBackup() throws IOException;

  /** The preconditions observed now, compared with the plan's before applying starts. */
  PreconditionSnapshot observe() throws IOException;

  /**
   * Performs step {@code step}. Every step must be safe to retry. Throw a {@link
   * io.github.kjmorse2.mconeworld.tool.core.ToolException} whose code {@link #isTerminal} accepts
   * to mark the operation failed; any other exception leaves the step in progress, to be resumed.
   */
  void perform(int step) throws IOException;

  /** Runs check {@code check}; returns {@code null} if it passed, else why it failed. */
  @Nullable String check(int check) throws IOException;

  /** Whether {@code code} means the operation must stop for good rather than be retried. */
  boolean isTerminal(io.github.kjmorse2.mconeworld.domain.validation.IssueCode code);
}
