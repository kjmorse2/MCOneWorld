// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.operation;

import static io.github.kjmorse2.mconeworld.fixtures.OperationFixtures.BACKUP;
import static io.github.kjmorse2.mconeworld.fixtures.OperationFixtures.DIGEST;
import static io.github.kjmorse2.mconeworld.fixtures.OperationFixtures.failure;

import io.github.kjmorse2.mconeworld.fixtures.OperationFixtures;
import java.util.List;

/** Drives a two-step, two-check plan into any state, and builds events of any type. */
final class ProgressStates {
  /** Step 0 is not retry-safe; step 1 is. */
  static final OperationPlan PLAN = OperationFixtures.plan(List.of(false, true), 2);

  private ProgressStates() {}

  static OperationProgress planned() {
    return OperationProgress.planned(PLAN, DIGEST);
  }

  static OperationProgress apply(OperationProgress progress, List<JournalEvent> events) {
    for (JournalEvent event : events) {
      progress = progress.apply(event);
    }
    return progress;
  }

  /** A progress in {@code state}, reached by the shortest legal history. */
  static OperationProgress reach(OperationState state) {
    OperationProgress p = planned();
    return switch (state) {
      case PLANNED -> p;
      case BACKED_UP -> apply(p, List.of(OperationFixtures.backupConfirmedFor(PLAN)));
      case APPLYING -> apply(p, OperationFixtures.toApplying(PLAN, DIGEST));
      case VALIDATING ->
          apply(p, OperationFixtures.toAllStepsCompleted(PLAN, DIGEST))
              .apply(new JournalEvent.ValidationStarted());
      case COMPLETED -> apply(p, OperationFixtures.toCompleted(PLAN, DIGEST));
      case FAILED -> reach(OperationState.APPLYING).apply(failed(null));
      case ROLLING_BACK -> reach(OperationState.FAILED).apply(new JournalEvent.RollbackStarted(1));
      case ROLLED_BACK ->
          reach(OperationState.ROLLING_BACK).apply(new JournalEvent.RollbackCompleted());
      case ROLLBACK_FAILED ->
          reach(OperationState.ROLLING_BACK)
              .apply(new JournalEvent.RollbackFailed(failure("rollback failed")));
      case CANCELLED -> p.apply(new JournalEvent.Cancelled(failure("operator cancelled")));
    };
  }

  /**
   * A progress in {@code state} where the guards of {@code event} can hold: validation needs every
   * check to have passed before completion, and step completion needs a started step.
   */
  static OperationProgress reachAllowing(OperationState state, JournalEventType event) {
    OperationProgress p = reach(state);
    if (state == OperationState.APPLYING && event == JournalEventType.STEP_COMPLETED) {
      return p.apply(new JournalEvent.StepStarted(0, 1));
    }
    if (state == OperationState.APPLYING && event == JournalEventType.VALIDATION_STARTED) {
      return apply(planned(), OperationFixtures.toAllStepsCompleted(PLAN, DIGEST));
    }
    if (state == OperationState.VALIDATING && event == JournalEventType.OPERATION_COMPLETED) {
      return apply(p, List.of(new JournalEvent.CheckPassed(0), new JournalEvent.CheckPassed(1)));
    }
    return p;
  }

  /** An event of {@code type} whose guards hold in {@code progress}. */
  static JournalEvent satisfying(JournalEventType type, OperationProgress progress) {
    Integer inProgress = progress.stepInProgress();
    return switch (type) {
      case PLAN_RECORDED -> OperationProgress.firstEvent(PLAN, DIGEST);
      case BACKUP_CONFIRMED -> OperationFixtures.backupConfirmedFor(PLAN);
      case CANCELLED -> new JournalEvent.Cancelled(failure("cancelled"));
      case APPLY_STARTED -> OperationFixtures.applyStartedFor(PLAN, DIGEST);
      case STEP_STARTED -> new JournalEvent.StepStarted(progress.stepsCompleted(), 1);
      case STEP_COMPLETED -> new JournalEvent.StepCompleted(inProgress == null ? 0 : inProgress);
      case VALIDATION_STARTED -> new JournalEvent.ValidationStarted();
      case CHECK_PASSED -> new JournalEvent.CheckPassed(progress.checksPassed());
      case OPERATION_COMPLETED -> new JournalEvent.OperationCompleted();
      case OPERATION_FAILED -> failed(inProgress);
      case ROLLBACK_STARTED -> new JournalEvent.RollbackStarted(progress.rollbackAttempts() + 1);
      case ROLLBACK_COMPLETED -> new JournalEvent.RollbackCompleted();
      case ROLLBACK_FAILED -> new JournalEvent.RollbackFailed(failure("rollback failed"));
    };
  }

  /**
   * Several events of {@code type} with different payloads, including the one whose guards hold, so
   * an illegal type is rejected whatever its payload.
   */
  static List<JournalEvent> eventsOfType(JournalEventType type, OperationProgress progress) {
    JournalEvent good = satisfying(type, progress);
    return switch (type) {
      case STEP_STARTED ->
          List.of(good, new JournalEvent.StepStarted(0, 1), new JournalEvent.StepStarted(1, 2));
      case BACKUP_CONFIRMED -> List.of(good, new JournalEvent.BackupConfirmed(null));
      case CHECK_PASSED -> List.of(good, new JournalEvent.CheckPassed(0));
      case ROLLBACK_STARTED -> List.of(good, new JournalEvent.RollbackStarted(1));
      case OPERATION_FAILED -> List.of(good, failed(null));
      default -> List.of(good);
    };
  }

  static JournalEvent.OperationFailed failed(Integer step) {
    return new JournalEvent.OperationFailed(failure("failed"), step);
  }

  static JournalEvent.BackupConfirmed someBackup() {
    return new JournalEvent.BackupConfirmed(BACKUP);
  }
}
