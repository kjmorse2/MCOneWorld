// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.operation;

import static io.github.kjmorse2.mconeworld.domain.operation.ProgressStates.failed;
import static io.github.kjmorse2.mconeworld.fixtures.OperationFixtures.failure;

import io.github.kjmorse2.mconeworld.fixtures.OperationFixtures;
import java.util.ArrayList;
import java.util.List;

/** Translates a recovery action into the journal events a caller would record for it. */
final class RecoveryActions {
  private RecoveryActions() {}

  /** The recommended action and its alternatives, excluding those that record nothing. */
  static List<RecoveryAction> offered(RecoveryRecommendation r) {
    List<RecoveryAction> all = new ArrayList<>();
    all.add(r.action());
    all.addAll(r.alternatives());
    all.removeIf(
        a ->
            a == RecoveryAction.NONE
                || a == RecoveryAction.MANUAL_INSPECTION
                || a == RecoveryAction.RECORD_PLAN);
    return all;
  }

  static List<JournalEvent> eventsFor(RecoveryAction action, OperationProgress p) {
    OperationPlan plan = p.plan();
    RecoveryRecommendation r = p.recommend();
    Integer nextStep = r.nextStep();
    Integer nextCheck = r.nextCheck();
    return switch (action) {
      case CONFIRM_BACKUP -> List.of(OperationFixtures.backupConfirmedFor(plan));
      case START_APPLY -> List.of(OperationFixtures.applyStartedFor(plan, p.planDigest()));
      case RESUME_APPLY ->
          List.of(new JournalEvent.StepStarted(nextStep == null ? -1 : nextStep, 1));
      case RETRY_STEP ->
          List.of(
              new JournalEvent.StepStarted(nextStep == null ? -1 : nextStep, p.stepAttempts() + 1));
      case START_VALIDATION -> List.of(new JournalEvent.ValidationStarted());
      case RESUME_VALIDATION ->
          List.of(new JournalEvent.CheckPassed(nextCheck == null ? -1 : nextCheck));
      case RECORD_COMPLETION -> List.of(new JournalEvent.OperationCompleted());
      case FAIL_AND_ROLLBACK ->
          List.of(
              failed(p.stepInProgress()),
              new JournalEvent.RollbackStarted(p.rollbackAttempts() + 1));
      case ROLLBACK, RETRY_ROLLBACK ->
          List.of(new JournalEvent.RollbackStarted(p.rollbackAttempts() + 1));
      case CANCEL -> List.of(new JournalEvent.Cancelled(failure("operator cancelled")));
      case NONE, MANUAL_INSPECTION, RECORD_PLAN -> List.of();
    };
  }
}
