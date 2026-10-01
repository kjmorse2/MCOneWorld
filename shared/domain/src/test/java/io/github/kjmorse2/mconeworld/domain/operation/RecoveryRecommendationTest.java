// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.operation;

import static io.github.kjmorse2.mconeworld.domain.operation.ProgressStates.apply;
import static io.github.kjmorse2.mconeworld.domain.operation.ProgressStates.reach;
import static io.github.kjmorse2.mconeworld.domain.operation.RecoveryAction.CANCEL;
import static io.github.kjmorse2.mconeworld.domain.operation.RecoveryAction.CONFIRM_BACKUP;
import static io.github.kjmorse2.mconeworld.domain.operation.RecoveryAction.FAIL_AND_ROLLBACK;
import static io.github.kjmorse2.mconeworld.domain.operation.RecoveryAction.MANUAL_INSPECTION;
import static io.github.kjmorse2.mconeworld.domain.operation.RecoveryAction.NONE;
import static io.github.kjmorse2.mconeworld.domain.operation.RecoveryAction.RECORD_COMPLETION;
import static io.github.kjmorse2.mconeworld.domain.operation.RecoveryAction.RESUME_APPLY;
import static io.github.kjmorse2.mconeworld.domain.operation.RecoveryAction.RESUME_VALIDATION;
import static io.github.kjmorse2.mconeworld.domain.operation.RecoveryAction.RETRY_ROLLBACK;
import static io.github.kjmorse2.mconeworld.domain.operation.RecoveryAction.RETRY_STEP;
import static io.github.kjmorse2.mconeworld.domain.operation.RecoveryAction.ROLLBACK;
import static io.github.kjmorse2.mconeworld.domain.operation.RecoveryAction.START_APPLY;
import static io.github.kjmorse2.mconeworld.domain.operation.RecoveryAction.START_VALIDATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The resume decision at every durable state, including the sub-states of APPLYING and VALIDATING
 * that decide between resuming, retrying, and rolling back. Every recommended action is also
 * checked to be recordable: the event it names is accepted by the progress.
 */
class RecoveryRecommendationTest {

  record Case(
      String name,
      OperationProgress progress,
      RecoveryAction action,
      Set<RecoveryAction> alternatives,
      boolean partial,
      Integer nextStep,
      Integer nextCheck) {
    @Override
    public String toString() {
      return name;
    }
  }

  static Stream<Arguments> everyDurableState() {
    OperationProgress applying = reach(OperationState.APPLYING);
    OperationProgress step0 = applying.apply(new JournalEvent.StepStarted(0, 1));
    OperationProgress between = step0.apply(new JournalEvent.StepCompleted(0));
    OperationProgress step1 = between.apply(new JournalEvent.StepStarted(1, 1));
    OperationProgress step1Exhausted = step1;
    for (int a = 2; a <= OperationProgress.MAX_STEP_ATTEMPTS; a++) {
      step1Exhausted = step1Exhausted.apply(new JournalEvent.StepStarted(1, a));
    }
    OperationProgress allSteps = step1.apply(new JournalEvent.StepCompleted(1));
    OperationProgress validating = reach(OperationState.VALIDATING);
    OperationProgress oneCheck = validating.apply(new JournalEvent.CheckPassed(0));
    OperationProgress allChecks = oneCheck.apply(new JournalEvent.CheckPassed(1));
    OperationProgress failedBeforeSteps = applying.apply(ProgressStates.failed(null));
    OperationProgress failedInStep = step0.apply(ProgressStates.failed(0));
    OperationProgress rollbackExhausted = reach(OperationState.FAILED);
    for (int a = 1; a <= OperationProgress.MAX_ROLLBACK_ATTEMPTS; a++) {
      rollbackExhausted = rollbackExhausted.apply(new JournalEvent.RollbackStarted(a));
    }
    List<Case> cases =
        List.of(
            new Case(
                "planned",
                reach(OperationState.PLANNED),
                CONFIRM_BACKUP,
                Set.of(CANCEL),
                false,
                null,
                null),
            new Case(
                "backed up",
                reach(OperationState.BACKED_UP),
                START_APPLY,
                Set.of(CANCEL),
                false,
                null,
                null),
            new Case(
                "applying, nothing started",
                applying,
                RESUME_APPLY,
                Set.of(FAIL_AND_ROLLBACK),
                false,
                0,
                null),
            new Case(
                "step 0 interrupted, not retry-safe",
                step0,
                FAIL_AND_ROLLBACK,
                Set.of(MANUAL_INSPECTION),
                true,
                null,
                null),
            new Case(
                "between steps", between, RESUME_APPLY, Set.of(FAIL_AND_ROLLBACK), true, 1, null),
            new Case(
                "step 1 interrupted, retry-safe",
                step1,
                RETRY_STEP,
                Set.of(FAIL_AND_ROLLBACK),
                true,
                1,
                null),
            new Case(
                "step 1 attempts exhausted",
                step1Exhausted,
                FAIL_AND_ROLLBACK,
                Set.of(MANUAL_INSPECTION),
                true,
                null,
                null),
            new Case(
                "all steps completed",
                allSteps,
                START_VALIDATION,
                Set.of(FAIL_AND_ROLLBACK),
                false,
                null,
                null),
            new Case(
                "validating, no check",
                validating,
                RESUME_VALIDATION,
                Set.of(FAIL_AND_ROLLBACK),
                false,
                null,
                0),
            new Case(
                "validating, one check",
                oneCheck,
                RESUME_VALIDATION,
                Set.of(FAIL_AND_ROLLBACK),
                false,
                null,
                1),
            new Case(
                "validating, all checks",
                allChecks,
                RECORD_COMPLETION,
                Set.of(FAIL_AND_ROLLBACK),
                false,
                null,
                null),
            new Case(
                "failed before any step",
                failedBeforeSteps,
                ROLLBACK,
                Set.of(MANUAL_INSPECTION),
                false,
                null,
                null),
            new Case(
                "failed in a step",
                failedInStep,
                ROLLBACK,
                Set.of(MANUAL_INSPECTION),
                true,
                null,
                null),
            new Case(
                "rolling back (interrupted)",
                reach(OperationState.ROLLING_BACK),
                RETRY_ROLLBACK,
                Set.of(MANUAL_INSPECTION),
                true,
                null,
                null),
            new Case(
                "rollback failed",
                reach(OperationState.ROLLBACK_FAILED),
                RETRY_ROLLBACK,
                Set.of(MANUAL_INSPECTION),
                true,
                null,
                null),
            new Case(
                "rollback attempts exhausted",
                rollbackExhausted,
                MANUAL_INSPECTION,
                Set.of(),
                true,
                null,
                null),
            new Case(
                "completed", reach(OperationState.COMPLETED), NONE, Set.of(), false, null, null),
            new Case(
                "rolled back",
                reach(OperationState.ROLLED_BACK),
                NONE,
                Set.of(),
                false,
                null,
                null),
            new Case(
                "cancelled", reach(OperationState.CANCELLED), NONE, Set.of(), false, null, null));
    return cases.stream().map(Arguments::of);
  }

  @Test
  void everyStateHasACase() {
    assertThat(everyDurableState().map(a -> ((Case) a.get()[0]).progress().state()))
        .containsAll(List.of(OperationState.values()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("everyDurableState")
  void recommendationMatches(Case c) {
    RecoveryRecommendation r = c.progress().recommend();
    assertThat(r.lastDurableState()).isEqualTo(c.progress().state());
    assertThat(r.action()).isEqualTo(c.action());
    assertThat(r.alternatives()).isEqualTo(c.alternatives());
    assertThat(r.partialEffectsPossible()).isEqualTo(c.partial());
    assertThat(r.nextStep()).isEqualTo(c.nextStep());
    assertThat(r.nextCheck()).isEqualTo(c.nextCheck());
    assertThat(r.requiresConfirmation()).isEqualTo(c.action() != NONE);
    assertThat(r.explanation()).isNotBlank().doesNotContain("\n");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("everyDurableState")
  void everyOfferedActionCanBeRecorded(Case c) {
    RecoveryRecommendation r = c.progress().recommend();
    for (RecoveryAction action : RecoveryActions.offered(r)) {
      List<JournalEvent> events = RecoveryActions.eventsFor(action, c.progress());
      OperationProgress after = apply(c.progress(), events);
      assertThat(after.nextSequence())
          .as("%s from %s", action, c)
          .isEqualTo(c.progress().nextSequence() + events.size());
    }
  }

  @Test
  void recommendationInvariants() {
    assertThatThrownBy(
            () ->
                new RecoveryRecommendation(
                    OperationState.PLANNED, CANCEL, Set.of(CANCEL), false, true, null, null, "x"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new RecoveryRecommendation(
                    OperationState.PLANNED, NONE, Set.of(), false, true, null, null, "x"))
        .isInstanceOf(IllegalArgumentException.class);
    RecoveryRecommendation manual = RecoveryRecommendation.manualInspection(null, "corrupt");
    assertThat(manual.action()).isEqualTo(MANUAL_INSPECTION);
    assertThat(manual.partialEffectsPossible()).isTrue();
    assertThat(manual.requiresConfirmation()).isTrue();
  }
}
