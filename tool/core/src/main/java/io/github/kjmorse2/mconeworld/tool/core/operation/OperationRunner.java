// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.operation;

import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.domain.operation.FailureReason;
import io.github.kjmorse2.mconeworld.domain.operation.JournalEvent;
import io.github.kjmorse2.mconeworld.domain.operation.OperationPlan;
import io.github.kjmorse2.mconeworld.domain.operation.OperationProgress;
import io.github.kjmorse2.mconeworld.domain.operation.OperationState;
import io.github.kjmorse2.mconeworld.domain.operation.OperationText;
import io.github.kjmorse2.mconeworld.domain.operation.PreconditionSnapshot;
import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationResult;
import io.github.kjmorse2.mconeworld.sidecar.operation.OperationJournal;
import io.github.kjmorse2.mconeworld.tool.core.ToolException;
import java.io.IOException;
import java.util.Objects;

/**
 * Drives one journaled operation from whatever state its journal is in to {@code COMPLETED}:
 *
 * <ol>
 *   <li>{@code PLANNED}: confirm the backup
 *   <li>{@code BACKED_UP}: observe the preconditions; if they differ from the plan's, cancel
 *       (nothing has been changed yet); else record {@code APPLY_STARTED}
 *   <li>{@code APPLYING}: every remaining step; a step left in progress is retried with the next
 *       attempt number
 *   <li>{@code VALIDATING}: every remaining check; a failed check fails the operation
 * </ol>
 *
 * A terminal failure in a step is recorded as {@code OPERATION_FAILED}; any other exception leaves
 * the journal where it is, so {@link #run} can be called again to resume. Nothing is retried
 * automatically.
 */
public final class OperationRunner {
  /** Test seam: called at named points, to simulate a crash there. */
  @FunctionalInterface
  public interface Hook {
    Hook NONE = point -> {};

    void at(String point) throws IOException;
  }

  private final Hook hook;

  public OperationRunner(Hook hook) {
    this.hook = Objects.requireNonNull(hook, "hook");
  }

  /**
   * Runs {@code journal}'s operation to completion.
   *
   * @param changed the code for preconditions that no longer hold
   * @param failed the code for a failed check
   * @param notResumable the code for a journal in a state that cannot continue
   * @throws ToolException with one of those codes, or a terminal code from {@code work}
   * @throws IOException if the work is interrupted; calling again resumes it
   */
  public void run(
      OperationJournal journal,
      OperationWork work,
      IssueCode changed,
      IssueCode failed,
      IssueCode notResumable)
      throws IOException {
    OperationPlan plan = journal.progress().plan();
    Checksum digest = journal.progress().planDigest();
    if (journal.progress().state() == OperationState.PLANNED) {
      append(journal, new JournalEvent.BackupConfirmed(work.confirmedBackup()));
      hook.at("backup-confirmed");
    }
    if (journal.progress().state() == OperationState.BACKED_UP) {
      PreconditionSnapshot observed = work.observe();
      ValidationResult differences = plan.preconditions().differences(observed);
      if (!differences.isValid()) {
        append(
            journal,
            new JournalEvent.Cancelled(
                reason(changed, "The world or sidecar changed since planning")));
        throw new ToolException(
            changed, "The world or sidecar changed since planning; plan again", differences, null);
      }
      append(journal, new JournalEvent.ApplyStarted(digest, observed));
      hook.at("apply-started");
    }
    if (journal.progress().state() == OperationState.APPLYING) {
      for (int step = journal.progress().stepsCompleted(); step < plan.steps().size(); step++) {
        OperationProgress p = journal.progress();
        Integer inProgress = p.stepInProgress();
        int attempt = inProgress != null && inProgress == step ? p.stepAttempts() + 1 : 1;
        append(journal, new JournalEvent.StepStarted(step, attempt));
        hook.at("step-" + step + "-started");
        try {
          work.perform(step);
        } catch (ToolException e) {
          if (work.isTerminal(e.code())) {
            append(
                journal,
                new JournalEvent.OperationFailed(
                    reason(e.code(), String.valueOf(e.getMessage())), step));
          }
          throw e;
        }
        append(journal, new JournalEvent.StepCompleted(step));
        hook.at("step-" + step + "-completed");
      }
      append(journal, new JournalEvent.ValidationStarted());
      hook.at("validation-started");
    }
    if (journal.progress().state() == OperationState.VALIDATING) {
      for (int check = journal.progress().checksPassed();
          check < plan.validations().size();
          check++) {
        String failure = work.check(check);
        if (failure != null) {
          append(journal, new JournalEvent.OperationFailed(reason(failed, failure), null));
          throw new ToolException(failed, failure);
        }
        append(journal, new JournalEvent.CheckPassed(check));
        hook.at("check-" + check + "-passed");
      }
      append(journal, new JournalEvent.OperationCompleted());
    }
    if (journal.progress().state() != OperationState.COMPLETED) {
      throw new ToolException(
          notResumable,
          "Operation "
              + plan.id()
              + " is "
              + journal.progress().state()
              + ": "
              + journal.progress().recommend().explanation());
    }
  }

  private static void append(OperationJournal journal, JournalEvent event) throws IOException {
    journal.append(journal.progress().nextSequence(), event);
  }

  private static FailureReason reason(IssueCode code, String message) {
    return new FailureReason(code, text(message));
  }

  /** {@code message} made acceptable as an {@link OperationText}: one bounded, clean line. */
  public static OperationText text(String message) {
    String clean = message.replaceAll("\\p{Cntrl}", " ").strip();
    if (clean.isEmpty()) {
      clean = "-";
    }
    if (clean.codePointCount(0, clean.length()) > OperationText.MAX_CODE_POINTS) {
      clean =
          clean.substring(0, clean.offsetByCodePoints(0, OperationText.MAX_CODE_POINTS - 1)) + "…";
    }
    return OperationText.of(clean);
  }
}
