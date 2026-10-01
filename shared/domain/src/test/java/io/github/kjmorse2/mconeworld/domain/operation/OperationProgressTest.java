// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.operation;

import static io.github.kjmorse2.mconeworld.domain.operation.ProgressStates.PLAN;
import static io.github.kjmorse2.mconeworld.domain.operation.ProgressStates.apply;
import static io.github.kjmorse2.mconeworld.domain.operation.ProgressStates.planned;
import static io.github.kjmorse2.mconeworld.domain.operation.ProgressStates.reach;
import static io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.instant;
import static io.github.kjmorse2.mconeworld.fixtures.OperationFixtures.BACKUP;
import static io.github.kjmorse2.mconeworld.fixtures.OperationFixtures.DIGEST;
import static io.github.kjmorse2.mconeworld.fixtures.OperationFixtures.checksum;
import static io.github.kjmorse2.mconeworld.fixtures.OperationFixtures.failure;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationException;
import io.github.kjmorse2.mconeworld.fixtures.DomainFixtures;
import io.github.kjmorse2.mconeworld.fixtures.OperationFixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The guards {@link OperationProgress} adds on top of the transition table. */
class OperationProgressTest {

  private static List<JournalEntry> entries(OperationPlan plan, List<JournalEvent> events) {
    List<JournalEntry> entries = new ArrayList<>();
    OperationProgress progress = OperationProgress.planned(plan, DIGEST);
    entries.add(
        new JournalEntry(
            0, instant(0), OperationProgress.firstEvent(plan, DIGEST), OperationState.PLANNED));
    for (JournalEvent event : events) {
      JournalEntry entry = progress.nextEntry(event, instant(entries.size()));
      entries.add(entry);
      progress = progress.apply(entry);
    }
    return entries;
  }

  private static IssueCode rejection(OperationProgress progress, JournalEvent event) {
    var result = progress.check(event);
    assertThat(result.isValid()).as("%s should be rejected", event).isFalse();
    assertThat(result.issues()).hasSize(1);
    return result.issues().get(0).code();
  }

  @Nested
  class FirstEntry {
    @Test
    void mustRecordThisPlanAtSequenceZero() {
      JournalEntry good =
          new JournalEntry(
              0, instant(0), OperationProgress.firstEvent(PLAN, DIGEST), OperationState.PLANNED);
      assertThat(OperationProgress.checkFirst(PLAN, DIGEST, good).isValid()).isTrue();

      JournalEntry wrongDigest =
          new JournalEntry(
              0,
              instant(0),
              OperationProgress.firstEvent(PLAN, checksum('e')),
              OperationState.PLANNED);
      assertThat(OperationProgress.checkFirst(PLAN, DIGEST, wrongDigest).issues().get(0).code())
          .isEqualTo(OperationIssueCodes.PLAN_MISMATCH);

      JournalEntry notFirst = new JournalEntry(1, instant(0), good.event(), OperationState.PLANNED);
      assertThat(OperationProgress.checkFirst(PLAN, DIGEST, notFirst).issues().get(0).code())
          .isEqualTo(OperationIssueCodes.SEQUENCE_MISMATCH);

      JournalEntry otherEvent =
          new JournalEntry(
              0, instant(0), new JournalEvent.ValidationStarted(), OperationState.PLANNED);
      assertThat(OperationProgress.checkFirst(PLAN, DIGEST, otherEvent).issues().get(0).code())
          .isEqualTo(OperationIssueCodes.TRANSITION_ILLEGAL);

      JournalEntry wrongState =
          new JournalEntry(0, instant(0), good.event(), OperationState.BACKED_UP);
      assertThat(OperationProgress.checkFirst(PLAN, DIGEST, wrongState).issues().get(0).code())
          .isEqualTo(OperationIssueCodes.STATE_MISMATCH);
    }

    @Test
    void aSecondPlanRecordedIsIllegal() {
      assertThat(rejection(planned(), OperationProgress.firstEvent(PLAN, DIGEST)))
          .isEqualTo(OperationIssueCodes.TRANSITION_ILLEGAL);
    }
  }

  @Nested
  class Sequences {
    @Test
    void replayOfAWholeJournalReachesItsState() {
      List<JournalEntry> journal = entries(PLAN, OperationFixtures.toCompleted(PLAN, DIGEST));
      OperationProgress progress = OperationProgress.replay(PLAN, DIGEST, journal);
      assertThat(progress.state()).isEqualTo(OperationState.COMPLETED);
      assertThat(progress.nextSequence()).isEqualTo(journal.size());
    }

    @Test
    void duplicateEntryIsRejectedDeterministically() {
      List<JournalEntry> journal = entries(PLAN, OperationFixtures.toApplying(PLAN, DIGEST));
      List<JournalEntry> duplicated = new ArrayList<>(journal);
      duplicated.add(journal.get(journal.size() - 1));
      assertThatThrownBy(() -> OperationProgress.replay(PLAN, DIGEST, duplicated))
          .isInstanceOfSatisfying(
              ValidationException.class,
              e -> assertThat(e.result().has(OperationIssueCodes.SEQUENCE_MISMATCH)).isTrue());
    }

    @Test
    void reorderedEntriesAreRejected() {
      List<JournalEntry> journal = entries(PLAN, OperationFixtures.toCompleted(PLAN, DIGEST));
      List<JournalEntry> swapped = new ArrayList<>(journal);
      swapped.set(3, journal.get(4));
      swapped.set(4, journal.get(3));
      assertThatThrownBy(() -> OperationProgress.replay(PLAN, DIGEST, swapped))
          .isInstanceOf(ValidationException.class);
    }

    @Test
    void missingEntryIsRejected() {
      List<JournalEntry> journal = entries(PLAN, OperationFixtures.toCompleted(PLAN, DIGEST));
      List<JournalEntry> gap = new ArrayList<>(journal);
      gap.remove(4);
      assertThatThrownBy(() -> OperationProgress.replay(PLAN, DIGEST, gap))
          .isInstanceOfSatisfying(
              ValidationException.class,
              e -> assertThat(e.result().has(OperationIssueCodes.SEQUENCE_MISMATCH)).isTrue());
    }

    @Test
    void recordedStateMustMatchTheEvent() {
      OperationProgress p = planned();
      JournalEntry lying =
          new JournalEntry(
              1, instant(1), OperationFixtures.backupConfirmedFor(PLAN), OperationState.APPLYING);
      assertThat(p.check(lying).issues().get(0).code())
          .isEqualTo(OperationIssueCodes.STATE_MISMATCH);
    }

    @Test
    void repeatedStepEventsAreRejectedNotReapplied() {
      OperationProgress p =
          apply(
              reach(OperationState.APPLYING),
              List.of(new JournalEvent.StepStarted(0, 1), new JournalEvent.StepCompleted(0)));
      assertThat(rejection(p, new JournalEvent.StepCompleted(0)))
          .isEqualTo(OperationIssueCodes.STEP_OUT_OF_ORDER);
      assertThat(rejection(p, new JournalEvent.StepStarted(0, 1)))
          .isEqualTo(OperationIssueCodes.STEP_OUT_OF_ORDER);
      assertThat(p.stepsCompleted()).isEqualTo(1);
    }
  }

  @Nested
  class BackupAndApproval {
    @Test
    void backupMustSatisfyTheRequirement() {
      OperationPlan existing = OperationFixtures.fullPlan();
      OperationProgress p = OperationProgress.planned(existing, DIGEST);
      assertThat(p.check(new JournalEvent.BackupConfirmed(BACKUP)).isValid()).isTrue();
      assertThat(
              rejection(
                  p,
                  new JournalEvent.BackupConfirmed(
                      new BackupReference(DomainFixtures.backupId(1), checksum('c')))))
          .isEqualTo(OperationIssueCodes.BACKUP_MISMATCH);
      assertThat(rejection(p, new JournalEvent.BackupConfirmed(null)))
          .isEqualTo(OperationIssueCodes.BACKUP_MISMATCH);
      assertThat(rejection(planned(), new JournalEvent.BackupConfirmed(null)))
          .isEqualTo(OperationIssueCodes.BACKUP_MISMATCH);
    }

    @Test
    void planChangedAfterApprovalIsRefused() {
      OperationProgress p = reach(OperationState.BACKED_UP);
      JournalEvent approvedOther =
          new JournalEvent.ApplyStarted(checksum('e'), PLAN.preconditions());
      assertThat(rejection(p, approvedOther)).isEqualTo(OperationIssueCodes.PLAN_CHANGED);
    }

    @Test
    void changedPreconditionsBetweenPlanningAndApplicationAreRefused() {
      OperationProgress p = reach(OperationState.BACKED_UP);
      PreconditionSnapshot planned = PLAN.preconditions();

      PreconditionSnapshot newerManifest =
          new PreconditionSnapshot(
              new ExpectedManifest.AtRevision(8, checksum('a')),
              planned.checksums(),
              planned.facts());
      PreconditionSnapshot sameRevisionOtherBytes =
          new PreconditionSnapshot(
              new ExpectedManifest.AtRevision(7, checksum('f')),
              planned.checksums(),
              planned.facts());
      TreeMap<InputKey, Checksum> editedRegion = new TreeMap<>(planned.checksums());
      editedRegion.put(InputKey.of("world:region/r.-1.0.mca"), checksum('9'));
      TreeMap<InputKey, String> upgraded = new TreeMap<>(planned.facts());
      upgraded.put(InputKey.of("minecraft:data_version"), "5023");
      TreeMap<InputKey, Checksum> extra = new TreeMap<>(planned.checksums());
      extra.put(InputKey.of("world:region/r.0.0.mca"), checksum('3'));
      TreeMap<InputKey, Checksum> missing = new TreeMap<>(planned.checksums());
      missing.remove(InputKey.of("world:level.dat"));

      for (PreconditionSnapshot observed :
          List.of(
              newerManifest,
              sameRevisionOtherBytes,
              new PreconditionSnapshot(planned.manifest(), editedRegion, planned.facts()),
              new PreconditionSnapshot(planned.manifest(), planned.checksums(), upgraded),
              new PreconditionSnapshot(planned.manifest(), extra, planned.facts()),
              new PreconditionSnapshot(planned.manifest(), missing, planned.facts()),
              PreconditionSnapshot.ofManifest(new ExpectedManifest.Absent()))) {
        assertThat(p.check(new JournalEvent.ApplyStarted(DIGEST, observed)).issues())
            .isNotEmpty()
            .allSatisfy(
                i -> assertThat(i.code()).isEqualTo(OperationIssueCodes.PRECONDITION_CHANGED));
      }
    }

    @Test
    void everyChangedPreconditionIsReportedAtItsLocation() {
      PreconditionSnapshot planned = PLAN.preconditions();
      TreeMap<InputKey, Checksum> edited = new TreeMap<>(planned.checksums());
      edited.put(InputKey.of("world:level.dat"), checksum('9'));
      var result =
          planned.differences(
              new PreconditionSnapshot(new ExpectedManifest.Absent(), edited, new TreeMap<>()));
      assertThat(result.issues())
          .extracting(i -> i.location().path())
          .containsExactlyInAnyOrder(
              "$.observed.manifest",
              "$.observed.checksums[world:level.dat]",
              "$.observed.facts[minecraft:data_version]");
      assertThat(planned.differences(planned).isValid()).isTrue();
    }

    @Test
    void cancellationIsPossibleOnlyBeforeApplying() {
      JournalEvent cancel = new JournalEvent.Cancelled(failure("precondition changed"));
      assertThat(reach(OperationState.PLANNED).apply(cancel).state())
          .isEqualTo(OperationState.CANCELLED);
      assertThat(reach(OperationState.BACKED_UP).apply(cancel).lastFailure())
          .isEqualTo(failure("precondition changed"));
      assertThat(rejection(reach(OperationState.APPLYING), cancel))
          .isEqualTo(OperationIssueCodes.TRANSITION_ILLEGAL);
    }
  }

  @Nested
  class Steps {
    @Test
    void stepsRunInOrder() {
      OperationProgress p = reach(OperationState.APPLYING);
      assertThat(rejection(p, new JournalEvent.StepStarted(1, 1)))
          .isEqualTo(OperationIssueCodes.STEP_OUT_OF_ORDER);
      assertThat(rejection(p, new JournalEvent.StepCompleted(0)))
          .isEqualTo(OperationIssueCodes.STEP_OUT_OF_ORDER);
      assertThat(rejection(p, new JournalEvent.StepStarted(0, 2)))
          .isEqualTo(OperationIssueCodes.ATTEMPT_MISMATCH);
      assertThat(rejection(p, new JournalEvent.ValidationStarted()))
          .isEqualTo(OperationIssueCodes.STEPS_INCOMPLETE);
      OperationProgress started = p.apply(new JournalEvent.StepStarted(0, 1));
      assertThat(started.stepInProgress()).isEqualTo(0);
      assertThat(started.anyStepStarted()).isTrue();
      assertThat(rejection(started, new JournalEvent.StepStarted(1, 1)))
          .isEqualTo(OperationIssueCodes.STEP_OUT_OF_ORDER);
      assertThat(rejection(started, new JournalEvent.StepCompleted(1)))
          .isEqualTo(OperationIssueCodes.STEP_OUT_OF_ORDER);
      assertThat(rejection(started, new JournalEvent.ValidationStarted()))
          .isEqualTo(OperationIssueCodes.STEPS_INCOMPLETE);
    }

    @Test
    void onlyRetrySafeStepsMayBeRestarted() {
      OperationProgress step0 =
          reach(OperationState.APPLYING).apply(new JournalEvent.StepStarted(0, 1));
      assertThat(rejection(step0, new JournalEvent.StepStarted(0, 2)))
          .isEqualTo(OperationIssueCodes.STEP_RETRY_NOT_ALLOWED);

      OperationProgress step1 =
          apply(
              step0,
              List.of(new JournalEvent.StepCompleted(0), new JournalEvent.StepStarted(1, 1)));
      assertThat(rejection(step1, new JournalEvent.StepStarted(1, 1)))
          .isEqualTo(OperationIssueCodes.ATTEMPT_MISMATCH);
      assertThat(rejection(step1, new JournalEvent.StepStarted(1, 3)))
          .isEqualTo(OperationIssueCodes.ATTEMPT_MISMATCH);
      OperationProgress retried = step1.apply(new JournalEvent.StepStarted(1, 2));
      assertThat(retried.stepAttempts()).isEqualTo(2);
    }

    @Test
    void retriesAreBounded() {
      OperationProgress p =
          apply(
              reach(OperationState.APPLYING),
              List.of(new JournalEvent.StepStarted(0, 1), new JournalEvent.StepCompleted(0)));
      for (int attempt = 1; attempt <= OperationProgress.MAX_STEP_ATTEMPTS; attempt++) {
        p = p.apply(new JournalEvent.StepStarted(1, attempt));
      }
      assertThat(
              rejection(
                  p, new JournalEvent.StepStarted(1, OperationProgress.MAX_STEP_ATTEMPTS + 1)))
          .isEqualTo(OperationIssueCodes.ATTEMPTS_EXHAUSTED);
    }

    @Test
    void failureMayNameOnlyTheStepInProgress() {
      OperationProgress p =
          reach(OperationState.APPLYING).apply(new JournalEvent.StepStarted(0, 1));
      assertThat(rejection(p, ProgressStates.failed(1)))
          .isEqualTo(OperationIssueCodes.STEP_OUT_OF_ORDER);
      assertThat(p.check(ProgressStates.failed(0)).isValid()).isTrue();
      assertThat(p.check(ProgressStates.failed(null)).isValid()).isTrue();
    }
  }

  @Nested
  class Checks {
    @Test
    void checksPassInOrderAndCompletionNeedsAll() {
      OperationProgress p = reach(OperationState.VALIDATING);
      assertThat(rejection(p, new JournalEvent.CheckPassed(1)))
          .isEqualTo(OperationIssueCodes.CHECK_OUT_OF_ORDER);
      assertThat(rejection(p, new JournalEvent.OperationCompleted()))
          .isEqualTo(OperationIssueCodes.CHECK_OUT_OF_ORDER);
      OperationProgress one = p.apply(new JournalEvent.CheckPassed(0));
      assertThat(rejection(one, new JournalEvent.CheckPassed(0)))
          .isEqualTo(OperationIssueCodes.CHECK_OUT_OF_ORDER);
      OperationProgress two = one.apply(new JournalEvent.CheckPassed(1));
      assertThat(rejection(two, new JournalEvent.CheckPassed(2)))
          .isEqualTo(OperationIssueCodes.CHECK_OUT_OF_ORDER);
      assertThat(two.apply(new JournalEvent.OperationCompleted()).state())
          .isEqualTo(OperationState.COMPLETED);
    }
  }

  @Nested
  class Rollback {
    @Test
    void rollbackSuccessAfterAFailedAttemptKeepsTheHistory() {
      List<JournalEvent> events = OperationFixtures.toRolledBack(PLAN, DIGEST);
      List<JournalEntry> journal = entries(PLAN, events);
      OperationProgress progress = OperationProgress.replay(PLAN, DIGEST, journal);
      assertThat(progress.state()).isEqualTo(OperationState.ROLLED_BACK);
      assertThat(progress.rollbackAttempts()).isEqualTo(2);
      assertThat(journal)
          .extracting(JournalEntry::stateAfter)
          .containsSubsequence(
              OperationState.FAILED,
              OperationState.ROLLING_BACK,
              OperationState.ROLLBACK_FAILED,
              OperationState.ROLLING_BACK,
              OperationState.ROLLED_BACK);
    }

    @Test
    void rollbackFailureIsRepresentedAndRetryable() {
      OperationProgress failed = reach(OperationState.ROLLBACK_FAILED);
      assertThat(failed.lastFailure()).isEqualTo(failure("rollback failed"));
      assertThat(failed.state().isTerminal()).isFalse();
      assertThat(rejection(failed, new JournalEvent.RollbackStarted(1)))
          .isEqualTo(OperationIssueCodes.ATTEMPT_MISMATCH);
      assertThat(failed.apply(new JournalEvent.RollbackStarted(2)).state())
          .isEqualTo(OperationState.ROLLING_BACK);
    }

    @Test
    void rollbackAttemptsAreBounded() {
      OperationProgress p = reach(OperationState.FAILED);
      for (int attempt = 1; attempt <= OperationProgress.MAX_ROLLBACK_ATTEMPTS; attempt++) {
        p = p.apply(new JournalEvent.RollbackStarted(attempt));
      }
      assertThat(
              rejection(
                  p, new JournalEvent.RollbackStarted(OperationProgress.MAX_ROLLBACK_ATTEMPTS + 1)))
          .isEqualTo(OperationIssueCodes.ATTEMPTS_EXHAUSTED);
      assertThat(p.recommend().action()).isEqualTo(RecoveryAction.MANUAL_INSPECTION);
    }
  }

  @Nested
  class Bounds {
    @Test
    void theWorstLegalHistoryReachesExactlyTheBound() {
      List<Boolean> retrySafe = new ArrayList<>();
      for (int i = 0; i < OperationPlan.MAX_STEPS; i++) {
        retrySafe.add(true);
      }
      OperationPlan plan = OperationFixtures.plan(retrySafe, OperationPlan.MAX_VALIDATIONS);
      OperationProgress p =
          apply(
              OperationProgress.planned(plan, DIGEST), OperationFixtures.toApplying(plan, DIGEST));
      for (int step = 0; step < plan.steps().size(); step++) {
        for (int attempt = 1; attempt <= OperationProgress.MAX_STEP_ATTEMPTS; attempt++) {
          p = p.apply(new JournalEvent.StepStarted(step, attempt));
        }
        p = p.apply(new JournalEvent.StepCompleted(step));
      }
      p = p.apply(new JournalEvent.ValidationStarted());
      for (int check = 0; check < plan.validations().size(); check++) {
        p = p.apply(new JournalEvent.CheckPassed(check));
      }
      p = p.apply(ProgressStates.failed(null));
      for (int attempt = 1; attempt <= OperationProgress.MAX_ROLLBACK_ATTEMPTS; attempt++) {
        p = p.apply(new JournalEvent.RollbackStarted(attempt));
        p = p.apply(new JournalEvent.RollbackFailed(failure("again")));
      }
      assertThat(p.nextSequence()).isEqualTo(OperationProgress.maxEntries(plan)).isEqualTo(2581);
      assertThat(p.recommend().action()).isEqualTo(RecoveryAction.MANUAL_INSPECTION);
      for (JournalEventType type : JournalEventType.values()) {
        assertThat(p.check(ProgressStates.satisfying(type, p)).isValid())
            .as("nothing more is legal: %s", type)
            .isFalse();
      }
    }
  }

  @Test
  void nextEntryCarriesSequenceAndResultingState() {
    OperationProgress p = planned();
    JournalEntry entry = p.nextEntry(OperationFixtures.backupConfirmedFor(PLAN), instant(5));
    assertThat(entry.sequence()).isEqualTo(1);
    assertThat(entry.stateAfter()).isEqualTo(OperationState.BACKED_UP);
    assertThatThrownBy(() -> p.nextEntry(new JournalEvent.ValidationStarted(), instant(5)))
        .isInstanceOf(ValidationException.class);
  }
}
