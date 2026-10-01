// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.operation;

import static io.github.kjmorse2.mconeworld.fixtures.OperationFixtures.BACKUP;
import static io.github.kjmorse2.mconeworld.fixtures.OperationFixtures.DIGEST;
import static io.github.kjmorse2.mconeworld.fixtures.OperationFixtures.checksum;
import static io.github.kjmorse2.mconeworld.fixtures.OperationFixtures.failure;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.kjmorse2.mconeworld.fixtures.OperationFixtures;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.statistics.Statistics;

/**
 * Model-based test of the operation state machine. A reference model, written independently and as
 * plainly as possible below, decides which events are legal. Random plans receive random event
 * sequences (a mix of events built to satisfy the current guards and arbitrary ones), and after
 * every candidate:
 *
 * <ul>
 *   <li>{@link OperationProgress} accepts the candidate exactly when the model does
 *   <li>their states and counters agree
 *   <li>the journal never exceeds {@link OperationProgress#maxEntries(OperationPlan)}
 *   <li>the recommendation is consistent: confirmation for every non-terminal state, no partial
 *       effects where none can exist, and every offered action is recordable
 * </ul>
 */
class OperationStateMachineProperties {

  /** One generated candidate: an event type plus raw payload choices. */
  record Candidate(JournalEventType type, boolean satisfying, int a, int b, int variant) {}

  @Provide
  Arbitrary<OperationPlan> plans() {
    return Arbitraries.of(true, false)
        .list()
        .ofMinSize(1)
        .ofMaxSize(4)
        .flatMap(
            retrySafe ->
                Arbitraries.integers()
                    .between(1, 3)
                    .map(checks -> OperationFixtures.plan(retrySafe, checks)));
  }

  @Provide
  Arbitrary<List<Candidate>> candidates() {
    Arbitrary<Candidate> one =
        Combinators.combine(
                Arbitraries.of(JournalEventType.class),
                Arbitraries.frequency(
                    net.jqwik.api.Tuple.of(3, true), net.jqwik.api.Tuple.of(1, false)),
                Arbitraries.integers().between(-1, 9),
                Arbitraries.integers().between(-1, 9),
                Arbitraries.integers().between(0, 3))
            .as(Candidate::new);
    return one.list().ofMaxSize(150);
  }

  @Property(tries = 400)
  void progressAgreesWithTheReferenceModel(
      @ForAll("plans") OperationPlan plan, @ForAll("candidates") List<Candidate> candidates) {
    OperationProgress progress = OperationProgress.planned(plan, DIGEST);
    Model model = new Model(plan);
    for (Candidate candidate : candidates) {
      JournalEvent event = build(candidate, progress, plan);
      boolean expected = model.accepts(event);
      assertThat(progress.check(event).isValid())
          .as("%s in %s (model says %s)", event, progress, expected)
          .isEqualTo(expected);
      if (expected) {
        progress = progress.apply(event);
        model.apply(event);
      }
      assertAgree(progress, model);
      assertRecommendationConsistent(progress);
    }
    // Reported with the test output, to show the walk reaches every state.
    Statistics.label("final state").collect(progress.state());
  }

  private static void assertAgree(OperationProgress progress, Model model) {
    assertThat(progress.state()).isEqualTo(model.state);
    assertThat(progress.stepsCompleted()).isEqualTo(model.stepsDone);
    assertThat(progress.stepInProgress()).isEqualTo(model.inProgress < 0 ? null : model.inProgress);
    assertThat(progress.checksPassed()).isEqualTo(model.checks);
    assertThat(progress.rollbackAttempts()).isEqualTo(model.rollbacks);
    assertThat(progress.nextSequence())
        .isEqualTo(model.entries)
        .isLessThanOrEqualTo(OperationProgress.maxEntries(progress.plan()));
  }

  private static final Set<OperationState> NEVER_PARTIAL =
      EnumSet.of(
          OperationState.PLANNED,
          OperationState.BACKED_UP,
          OperationState.VALIDATING,
          OperationState.COMPLETED,
          OperationState.ROLLED_BACK,
          OperationState.CANCELLED);

  private static void assertRecommendationConsistent(OperationProgress progress) {
    RecoveryRecommendation r = progress.recommend();
    assertThat(r.lastDurableState()).isEqualTo(progress.state());
    assertThat(r.requiresConfirmation()).isEqualTo(!progress.state().isTerminal());
    assertThat(r.action() == RecoveryAction.NONE).isEqualTo(progress.state().isTerminal());
    if (NEVER_PARTIAL.contains(progress.state())) {
      assertThat(r.partialEffectsPossible()).isFalse();
    }
    // Effects come only from steps and rollback attempts: a restore that was interrupted can
    // leave partial effects even when no step ever ran.
    if (!progress.anyStepStarted() && progress.rollbackAttempts() == 0) {
      assertThat(r.partialEffectsPossible()).isFalse();
    }
    for (RecoveryAction action : RecoveryActions.offered(r)) {
      OperationProgress after = progress;
      for (JournalEvent event : RecoveryActions.eventsFor(action, progress)) {
        assertThat(after.check(event).issues()).as("%s offers %s", progress, action).isEmpty();
        after = after.apply(event);
      }
    }
  }

  /** Builds an event: either one meant to satisfy the guards, or one from raw payload choices. */
  private static JournalEvent build(Candidate c, OperationProgress p, OperationPlan plan) {
    if (c.satisfying()) {
      // Steer towards progress: pick one of the currently legal types (the model still decides
      // independently whether the built event is legal). Cancelling and failing end or divert the
      // walk, so they are picked only a quarter of the time.
      List<JournalEventType> legal =
          new ArrayList<>(OperationTransitions.legalEvents(p.state()).keySet());
      if (c.variant() != 0 && legal.size() > 1) {
        legal.removeAll(List.of(JournalEventType.CANCELLED, JournalEventType.OPERATION_FAILED));
      }
      if (legal.isEmpty()) {
        return raw(c, plan);
      }
      JournalEventType type = legal.get(Math.floorMod(c.b(), legal.size()));
      Integer inProgress = p.stepInProgress();
      return switch (type) {
        case BACKUP_CONFIRMED -> OperationFixtures.backupConfirmedFor(plan);
        case APPLY_STARTED -> OperationFixtures.applyStartedFor(plan, DIGEST);
        case STEP_STARTED ->
            inProgress == null
                ? new JournalEvent.StepStarted(p.stepsCompleted(), 1)
                : new JournalEvent.StepStarted(inProgress, p.stepAttempts() + 1);
        case STEP_COMPLETED -> new JournalEvent.StepCompleted(inProgress == null ? 0 : inProgress);
        case CHECK_PASSED -> new JournalEvent.CheckPassed(p.checksPassed());
        case OPERATION_FAILED -> new JournalEvent.OperationFailed(failure("f"), inProgress);
        case ROLLBACK_STARTED -> new JournalEvent.RollbackStarted(p.rollbackAttempts() + 1);
        default -> raw(new Candidate(type, false, c.a(), c.b(), 0), plan);
      };
    }
    return raw(c, plan);
  }

  private static JournalEvent raw(Candidate c, OperationPlan plan) {
    return switch (c.type()) {
      case PLAN_RECORDED ->
          OperationProgress.firstEvent(plan, c.variant() == 0 ? DIGEST : checksum('e'));
      case BACKUP_CONFIRMED ->
          new JournalEvent.BackupConfirmed(
              switch (c.variant()) {
                case 0 -> BACKUP;
                case 1 -> new BackupReference(BACKUP.id(), checksum('c'));
                default -> null;
              });
      case CANCELLED -> new JournalEvent.Cancelled(failure("c"));
      case APPLY_STARTED ->
          new JournalEvent.ApplyStarted(
              c.variant() == 1 ? checksum('e') : DIGEST,
              c.variant() == 2
                  ? PreconditionSnapshot.ofManifest(new ExpectedManifest.Absent())
                  : plan.preconditions());
      case STEP_STARTED -> new JournalEvent.StepStarted(c.a(), c.b());
      case STEP_COMPLETED -> new JournalEvent.StepCompleted(c.a());
      case VALIDATION_STARTED -> new JournalEvent.ValidationStarted();
      case CHECK_PASSED -> new JournalEvent.CheckPassed(c.a());
      case OPERATION_COMPLETED -> new JournalEvent.OperationCompleted();
      case OPERATION_FAILED ->
          new JournalEvent.OperationFailed(failure("f"), c.variant() == 0 ? null : c.a());
      case ROLLBACK_STARTED -> new JournalEvent.RollbackStarted(c.a());
      case ROLLBACK_COMPLETED -> new JournalEvent.RollbackCompleted();
      case ROLLBACK_FAILED -> new JournalEvent.RollbackFailed(failure("r"));
    };
  }

  /** The reference model: the documented rules, restated directly. */
  private static final class Model {
    final OperationPlan plan;
    OperationState state = OperationState.PLANNED;
    int entries = 1;
    int stepsDone;
    int inProgress = -1;
    int attempts;
    int checks;
    int rollbacks;

    Model(OperationPlan plan) {
      this.plan = plan;
    }

    boolean in(OperationState... states) {
      return List.of(states).contains(state);
    }

    boolean accepts(JournalEvent event) {
      int steps = plan.steps().size();
      int maxChecks = plan.validations().size();
      return switch (event) {
        case JournalEvent.PlanRecorded e -> false;
        case JournalEvent.BackupConfirmed e ->
            in(OperationState.PLANNED) && e.backup() != null; // NewBackup: any backup
        case JournalEvent.Cancelled e -> in(OperationState.PLANNED, OperationState.BACKED_UP);
        case JournalEvent.ApplyStarted e ->
            in(OperationState.BACKED_UP)
                && e.approvedPlanDigest().equals(DIGEST)
                && e.observed().equals(plan.preconditions());
        case JournalEvent.StepStarted e ->
            in(OperationState.APPLYING)
                && (inProgress < 0
                    ? e.step() == stepsDone && e.step() < steps && e.attempt() == 1
                    : e.step() == inProgress
                        && plan.steps().get(inProgress).retrySafe()
                        && attempts < OperationProgress.MAX_STEP_ATTEMPTS
                        && e.attempt() == attempts + 1);
        case JournalEvent.StepCompleted e ->
            in(OperationState.APPLYING) && inProgress >= 0 && e.step() == inProgress;
        case JournalEvent.ValidationStarted e ->
            in(OperationState.APPLYING) && inProgress < 0 && stepsDone == steps;
        case JournalEvent.CheckPassed e ->
            in(OperationState.VALIDATING) && e.check() == checks && checks < maxChecks;
        case JournalEvent.OperationCompleted e ->
            in(OperationState.VALIDATING) && checks == maxChecks;
        case JournalEvent.OperationFailed e ->
            in(OperationState.APPLYING, OperationState.VALIDATING)
                && (e.step() == null || (inProgress >= 0 && e.step() == inProgress));
        case JournalEvent.RollbackStarted e ->
            in(OperationState.FAILED, OperationState.ROLLING_BACK, OperationState.ROLLBACK_FAILED)
                && rollbacks < OperationProgress.MAX_ROLLBACK_ATTEMPTS
                && e.attempt() == rollbacks + 1;
        case JournalEvent.RollbackCompleted e -> in(OperationState.ROLLING_BACK);
        case JournalEvent.RollbackFailed e -> in(OperationState.ROLLING_BACK);
      };
    }

    void apply(JournalEvent event) {
      entries++;
      switch (event) {
        case JournalEvent.BackupConfirmed e -> state = OperationState.BACKED_UP;
        case JournalEvent.Cancelled e -> state = OperationState.CANCELLED;
        case JournalEvent.ApplyStarted e -> state = OperationState.APPLYING;
        case JournalEvent.StepStarted e -> {
          inProgress = e.step();
          attempts = e.attempt();
        }
        case JournalEvent.StepCompleted e -> {
          stepsDone++;
          inProgress = -1;
          attempts = 0;
        }
        case JournalEvent.ValidationStarted e -> state = OperationState.VALIDATING;
        case JournalEvent.CheckPassed e -> checks++;
        case JournalEvent.OperationCompleted e -> state = OperationState.COMPLETED;
        case JournalEvent.OperationFailed e -> state = OperationState.FAILED;
        case JournalEvent.RollbackStarted e -> {
          state = OperationState.ROLLING_BACK;
          rollbacks = e.attempt();
        }
        case JournalEvent.RollbackCompleted e -> state = OperationState.ROLLED_BACK;
        case JournalEvent.RollbackFailed e -> state = OperationState.ROLLBACK_FAILED;
        case JournalEvent.PlanRecorded e -> throw new AssertionError();
      }
    }
  }
}
