// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.operation;

import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import io.github.kjmorse2.mconeworld.domain.validation.IssueLocation;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationIssue;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationResult;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The state of one operation after replaying its journal against its plan. It is immutable: {@link
 * #apply} returns a new instance. It enforces the {@link OperationTransitions} table plus every
 * guard that depends on the plan or the history:
 *
 * <ul>
 *   <li><b>Sequence:</b> entry {@code n} must have sequence {@code n}. A duplicate, reordered, or
 *       missing entry is rejected ({@code operation.sequence.mismatch}). Identical re-sends are
 *       made idempotent by the journal store, which compares them with the entry already written.
 *   <li><b>Plan:</b> the first entry records this plan's id, type, and digest. {@code
 *       APPLY_STARTED} carries the approved digest, which must equal it: a plan changed after
 *       approval is refused. Its observed preconditions must equal the plan's.
 *   <li><b>Backup:</b> {@code BACKUP_CONFIRMED} must satisfy the plan's {@link BackupRequirement}.
 *   <li><b>Steps:</b> steps start in order, each completes before the next starts, and a step is
 *       restarted (attempt {@code n + 1}) only if the plan declares it retry-safe, at most {@value
 *       #MAX_STEP_ATTEMPTS} attempts in all. Validation starts only after every step completed.
 *   <li><b>Checks:</b> checks pass in order; completion requires every check.
 *   <li><b>Rollback:</b> attempts are numbered 1, 2, …, at most {@value #MAX_ROLLBACK_ATTEMPTS}.
 * </ul>
 *
 * <p><b>Size.</b> Because every step and rollback has bounded attempts, and no other event repeats,
 * a legal journal can never hold more than {@link #maxEntries(OperationPlan)} entries: at most 2
 * 581 for the largest plan. No separate cap is needed; the sidecar store also bounds bytes.
 *
 * <p>Every rejection is a {@link ValidationResult} with a code from {@link OperationIssueCodes}, so
 * rejections are deterministic and machine-readable. History is never rewritten: failures and
 * rollbacks are further entries.
 */
public final class OperationProgress {
  /** Maximum attempts of one step. */
  public static final int MAX_STEP_ATTEMPTS = 8;

  /** Maximum rollback attempts. */
  public static final int MAX_ROLLBACK_ATTEMPTS = 8;

  private static final IssueLocation EVENT = IssueLocation.root().field("event");

  private final OperationPlan plan;
  private final Checksum planDigest;
  private final OperationState state;
  private final long nextSequence;
  private final int stepsCompleted;
  private final @Nullable Integer stepInProgress;
  private final int stepAttempts;
  private final boolean anyStepStarted;
  private final int checksPassed;
  private final int rollbackAttempts;
  private final @Nullable FailureReason lastFailure;

  private OperationProgress(
      OperationPlan plan,
      Checksum planDigest,
      OperationState state,
      long nextSequence,
      int stepsCompleted,
      @Nullable Integer stepInProgress,
      int stepAttempts,
      boolean anyStepStarted,
      int checksPassed,
      int rollbackAttempts,
      @Nullable FailureReason lastFailure) {
    this.plan = plan;
    this.planDigest = planDigest;
    this.state = state;
    this.nextSequence = nextSequence;
    this.stepsCompleted = stepsCompleted;
    this.stepInProgress = stepInProgress;
    this.stepAttempts = stepAttempts;
    this.anyStepStarted = anyStepStarted;
    this.checksPassed = checksPassed;
    this.rollbackAttempts = rollbackAttempts;
    this.lastFailure = lastFailure;
  }

  /** The first event of the journal for {@code plan}. */
  public static JournalEvent.PlanRecorded firstEvent(OperationPlan plan, Checksum planDigest) {
    return new JournalEvent.PlanRecorded(plan.id(), plan.type(), planDigest);
  }

  /**
   * Checks that {@code first} is a valid first entry for {@code plan}: sequence 0, a {@code
   * PLAN_RECORDED} event naming this plan and digest, and state {@code PLANNED}.
   */
  public static ValidationResult checkFirst(
      OperationPlan plan, Checksum planDigest, JournalEntry first) {
    if (first.sequence() != 0) {
      return reject(
          OperationIssueCodes.SEQUENCE_MISMATCH,
          IssueLocation.root().field("sequence"),
          "The first entry must have sequence 0, found " + first.sequence());
    }
    if (!first.event().equals(firstEvent(plan, planDigest))) {
      return reject(
          first.event() instanceof JournalEvent.PlanRecorded
              ? OperationIssueCodes.PLAN_MISMATCH
              : OperationIssueCodes.TRANSITION_ILLEGAL,
          EVENT,
          "The first entry must record this plan's id, type, and digest");
    }
    if (first.stateAfter() != OperationTransitions.initialState()) {
      return reject(
          OperationIssueCodes.STATE_MISMATCH,
          IssueLocation.root().field("stateAfter"),
          "The first entry must leave the operation PLANNED");
    }
    return ValidationResult.ok();
  }

  /**
   * The most entries any legal journal for {@code plan} can hold: the plan, backup, and apply
   * entries; every step started {@value #MAX_STEP_ATTEMPTS} times and completed once; validation
   * started and every check passed; one failure; and {@value #MAX_ROLLBACK_ATTEMPTS} rollback
   * attempts, each with an outcome. (A completed operation needs fewer: it has no failure or
   * rollback, but one completion entry.)
   */
  public static int maxEntries(OperationPlan plan) {
    return 3
        + plan.steps().size() * (MAX_STEP_ATTEMPTS + 1)
        + 1
        + plan.validations().size()
        + 1
        + 2 * MAX_ROLLBACK_ATTEMPTS;
  }

  /** The progress right after the first entry. */
  public static OperationProgress planned(OperationPlan plan, Checksum planDigest) {
    return new OperationProgress(
        Objects.requireNonNull(plan, "plan"),
        Objects.requireNonNull(planDigest, "planDigest"),
        OperationTransitions.initialState(),
        1,
        0,
        null,
        0,
        false,
        0,
        0,
        null);
  }

  /**
   * Replays a whole journal.
   *
   * @throws io.github.kjmorse2.mconeworld.domain.validation.ValidationException at the first
   *     invalid entry
   * @throws IllegalArgumentException if {@code entries} is empty
   */
  public static OperationProgress replay(
      OperationPlan plan, Checksum planDigest, List<JournalEntry> entries) {
    if (entries.isEmpty()) {
      throw new IllegalArgumentException("A journal has at least its first entry");
    }
    checkFirst(plan, planDigest, entries.get(0)).requireValid();
    OperationProgress progress = planned(plan, planDigest);
    for (JournalEntry entry : entries.subList(1, entries.size())) {
      progress = progress.apply(entry);
    }
    return progress;
  }

  /**
   * Checks whether {@code event} may be appended next. Returns every reason it may not, or an empty
   * result.
   */
  public ValidationResult check(JournalEvent event) {
    Objects.requireNonNull(event, "event");
    if (OperationTransitions.next(state, event.type()).isEmpty()) {
      return reject(
          OperationIssueCodes.TRANSITION_ILLEGAL,
          EVENT,
          event.type() + " is not legal in state " + state);
    }
    return switch (event) {
      case JournalEvent.PlanRecorded e -> throw new AssertionError("illegal in every state");
      case JournalEvent.BackupConfirmed e ->
          plan.backup().acceptsConfirmation(e.backup())
              ? ValidationResult.ok()
              : reject(
                  OperationIssueCodes.BACKUP_MISMATCH,
                  EVENT.field("backup"),
                  "The confirmed backup does not satisfy the plan's backup requirement");
      case JournalEvent.ApplyStarted e -> checkApplyStarted(e);
      case JournalEvent.StepStarted e -> checkStepStarted(e);
      case JournalEvent.StepCompleted e ->
          Objects.equals(stepInProgress, e.step())
              ? ValidationResult.ok()
              : reject(
                  OperationIssueCodes.STEP_OUT_OF_ORDER,
                  EVENT.field("step"),
                  "Step " + e.step() + " is not in progress");
      case JournalEvent.ValidationStarted e ->
          stepInProgress == null && stepsCompleted == plan.steps().size()
              ? ValidationResult.ok()
              : reject(
                  OperationIssueCodes.STEPS_INCOMPLETE,
                  EVENT,
                  stepsCompleted + " of " + plan.steps().size() + " steps are complete");
      case JournalEvent.CheckPassed e ->
          e.check() == checksPassed && e.check() < plan.validations().size()
              ? ValidationResult.ok()
              : reject(
                  OperationIssueCodes.CHECK_OUT_OF_ORDER,
                  EVENT.field("check"),
                  "The next check is " + checksPassed + ", not " + e.check());
      case JournalEvent.OperationCompleted e ->
          checksPassed == plan.validations().size()
              ? ValidationResult.ok()
              : reject(
                  OperationIssueCodes.CHECK_OUT_OF_ORDER,
                  EVENT,
                  checksPassed + " of " + plan.validations().size() + " checks passed");
      case JournalEvent.OperationFailed e ->
          e.step() == null || Objects.equals(e.step(), stepInProgress)
              ? ValidationResult.ok()
              : reject(
                  OperationIssueCodes.STEP_OUT_OF_ORDER,
                  EVENT.field("step"),
                  "The failure names step " + e.step() + ", which is not in progress");
      case JournalEvent.RollbackStarted e -> checkAttempt(e.attempt(), rollbackAttempts, true);
      case JournalEvent.Cancelled e -> ValidationResult.ok();
      case JournalEvent.RollbackCompleted e -> ValidationResult.ok();
      case JournalEvent.RollbackFailed e -> ValidationResult.ok();
    };
  }

  /**
   * Checks a whole entry: its sequence, its event ({@link #check(JournalEvent)}), and its recorded
   * state.
   */
  public ValidationResult check(JournalEntry entry) {
    if (entry.sequence() != nextSequence) {
      return reject(
          OperationIssueCodes.SEQUENCE_MISMATCH,
          IssueLocation.root().field("sequence"),
          "Expected sequence " + nextSequence + ", found " + entry.sequence());
    }
    ValidationResult eventCheck = check(entry.event());
    if (!eventCheck.isValid()) {
      return eventCheck;
    }
    OperationState expected = stateAfter(entry.event());
    if (entry.stateAfter() != expected) {
      return reject(
          OperationIssueCodes.STATE_MISMATCH,
          IssueLocation.root().field("stateAfter"),
          "The entry records " + entry.stateAfter() + " but its event leads to " + expected);
    }
    return ValidationResult.ok();
  }

  /**
   * Returns the progress after {@code event}.
   *
   * @throws io.github.kjmorse2.mconeworld.domain.validation.ValidationException if {@link
   *     #check(JournalEvent)} rejects it
   */
  public OperationProgress apply(JournalEvent event) {
    check(event).requireValid();
    return advance(event);
  }

  /**
   * Returns the progress after {@code entry}.
   *
   * @throws io.github.kjmorse2.mconeworld.domain.validation.ValidationException if {@link
   *     #check(JournalEntry)} rejects it
   */
  public OperationProgress apply(JournalEntry entry) {
    check(entry).requireValid();
    return advance(entry.event());
  }

  /**
   * Builds the next entry for {@code event}, with the next sequence number and the resulting state.
   *
   * @throws io.github.kjmorse2.mconeworld.domain.validation.ValidationException if {@link
   *     #check(JournalEvent)} rejects it
   */
  public JournalEntry nextEntry(JournalEvent event, java.time.Instant recordedAt) {
    check(event).requireValid();
    return new JournalEntry(nextSequence, recordedAt, event, stateAfter(event));
  }

  /** Derives the recommendation from this durable state. See {@link RecoveryRecommendation}. */
  public RecoveryRecommendation recommend() {
    return switch (state) {
      case PLANNED ->
          recommend(
              RecoveryAction.CONFIRM_BACKUP,
              Set.of(RecoveryAction.CANCEL),
              false,
              null,
              null,
              "The plan is recorded; nothing was applied. Confirm the backup, or cancel.");
      case BACKED_UP ->
          recommend(
              RecoveryAction.START_APPLY,
              Set.of(RecoveryAction.CANCEL),
              false,
              null,
              null,
              "The backup is confirmed; nothing was applied. Re-check the preconditions and get"
                  + " approval before applying, or cancel.");
      case APPLYING -> recommendApplying();
      case VALIDATING ->
          checksPassed == plan.validations().size()
              ? recommend(
                  RecoveryAction.RECORD_COMPLETION,
                  Set.of(RecoveryAction.FAIL_AND_ROLLBACK),
                  false,
                  null,
                  null,
                  "Every check passed; completion was not recorded.")
              : recommend(
                  RecoveryAction.RESUME_VALIDATION,
                  Set.of(RecoveryAction.FAIL_AND_ROLLBACK),
                  false,
                  null,
                  checksPassed,
                  "Every step completed. Checks are read-only, so validation can continue at check "
                      + checksPassed
                      + ".");
      case FAILED ->
          recommend(
              RecoveryAction.ROLLBACK,
              Set.of(RecoveryAction.MANUAL_INSPECTION),
              anyStepStarted,
              null,
              null,
              "The operation failed"
                  + (lastFailure == null ? "" : " (" + lastFailure.code() + ")")
                  + ". Roll back using the declared "
                  + plan.rollback().strategy()
                  + " strategy.");
      case ROLLING_BACK, ROLLBACK_FAILED ->
          rollbackAttempts < MAX_ROLLBACK_ATTEMPTS
              ? recommend(
                  RecoveryAction.RETRY_ROLLBACK,
                  Set.of(RecoveryAction.MANUAL_INSPECTION),
                  true,
                  null,
                  null,
                  "Rollback attempt "
                      + rollbackAttempts
                      + (state == OperationState.ROLLBACK_FAILED
                          ? " failed."
                          : " was interrupted before recording its outcome.")
                      + " Retry the rollback, or inspect manually.")
              : RecoveryRecommendation.manualInspection(
                  state,
                  "All "
                      + MAX_ROLLBACK_ATTEMPTS
                      + " rollback attempts are used; inspect manually.");
      case COMPLETED, ROLLED_BACK, CANCELLED ->
          new RecoveryRecommendation(
              state,
              RecoveryAction.NONE,
              Set.of(),
              false,
              false,
              null,
              null,
              "The operation is finished (" + state + ").");
    };
  }

  private RecoveryRecommendation recommendApplying() {
    Integer inProgress = stepInProgress;
    if (inProgress == null) {
      if (stepsCompleted == plan.steps().size()) {
        return recommend(
            RecoveryAction.START_VALIDATION,
            Set.of(RecoveryAction.FAIL_AND_ROLLBACK),
            false,
            null,
            null,
            "Every step completed; validation has not started.");
      }
      return recommend(
          RecoveryAction.RESUME_APPLY,
          Set.of(RecoveryAction.FAIL_AND_ROLLBACK),
          anyStepStarted,
          stepsCompleted,
          null,
          "Steps 0.."
              + (stepsCompleted - 1)
              + " completed; step "
              + stepsCompleted
              + " has not started.");
    }
    boolean retrySafe = plan.steps().get(inProgress).retrySafe();
    if (retrySafe && stepAttempts < MAX_STEP_ATTEMPTS) {
      return recommend(
          RecoveryAction.RETRY_STEP,
          Set.of(RecoveryAction.FAIL_AND_ROLLBACK),
          true,
          inProgress,
          null,
          "Step "
              + inProgress
              + " started but its outcome is unknown. It is declared retry-safe, so it may be"
              + " restarted.");
    }
    return recommend(
        RecoveryAction.FAIL_AND_ROLLBACK,
        Set.of(RecoveryAction.MANUAL_INSPECTION),
        true,
        null,
        null,
        "Step "
            + inProgress
            + " started but its outcome is unknown, and it "
            + (retrySafe ? "has used all its attempts." : "is not retry-safe.")
            + " Record the failure and roll back.");
  }

  private RecoveryRecommendation recommend(
      RecoveryAction action,
      Set<RecoveryAction> alternatives,
      boolean partial,
      @Nullable Integer nextStep,
      @Nullable Integer nextCheck,
      String explanation) {
    return new RecoveryRecommendation(
        state, action, alternatives, partial, true, nextStep, nextCheck, explanation);
  }

  private ValidationResult checkApplyStarted(JournalEvent.ApplyStarted e) {
    if (!e.approvedPlanDigest().equals(planDigest)) {
      return reject(
          OperationIssueCodes.PLAN_CHANGED,
          EVENT.field("approvedPlanDigest"),
          "The approved plan digest differs from the recorded plan: the plan changed after"
              + " approval");
    }
    return plan.preconditions().differences(e.observed());
  }

  private ValidationResult checkStepStarted(JournalEvent.StepStarted e) {
    Integer inProgress = stepInProgress;
    if (inProgress == null) {
      if (e.step() != stepsCompleted || e.step() >= plan.steps().size()) {
        return reject(
            OperationIssueCodes.STEP_OUT_OF_ORDER,
            EVENT.field("step"),
            "The next step is " + stepsCompleted + ", not " + e.step());
      }
      return checkAttempt(e.attempt(), 0, false);
    }
    if (e.step() != inProgress) {
      return reject(
          OperationIssueCodes.STEP_OUT_OF_ORDER,
          EVENT.field("step"),
          "Step " + inProgress + " is still in progress");
    }
    if (!plan.steps().get(inProgress).retrySafe()) {
      return reject(
          OperationIssueCodes.STEP_RETRY_NOT_ALLOWED,
          EVENT.field("step"),
          "Step " + inProgress + " is not declared retry-safe and cannot be restarted");
    }
    return checkAttempt(e.attempt(), stepAttempts, false);
  }

  private static ValidationResult checkAttempt(int attempt, int previous, boolean rollback) {
    int max = rollback ? MAX_ROLLBACK_ATTEMPTS : MAX_STEP_ATTEMPTS;
    if (previous >= max) {
      return reject(
          OperationIssueCodes.ATTEMPTS_EXHAUSTED,
          EVENT.field("attempt"),
          "All " + max + " attempts are used");
    }
    if (attempt != previous + 1) {
      return reject(
          OperationIssueCodes.ATTEMPT_MISMATCH,
          EVENT.field("attempt"),
          "The next attempt is " + (previous + 1) + ", not " + attempt);
    }
    return ValidationResult.ok();
  }

  private OperationState stateAfter(JournalEvent event) {
    Optional<OperationState> next = OperationTransitions.next(state, event.type());
    return next.orElseThrow(() -> new IllegalStateException("checked before"));
  }

  /** Applies a checked event. */
  private OperationProgress advance(JournalEvent event) {
    OperationState after = stateAfter(event);
    int completed = stepsCompleted;
    Integer inProgress = stepInProgress;
    int attempts = stepAttempts;
    boolean started = anyStepStarted;
    int checks = checksPassed;
    int rollbacks = rollbackAttempts;
    FailureReason failure = lastFailure;
    switch (event) {
      case JournalEvent.StepStarted e -> {
        inProgress = e.step();
        attempts = e.attempt();
        started = true;
      }
      case JournalEvent.StepCompleted e -> {
        completed++;
        inProgress = null;
        attempts = 0;
      }
      case JournalEvent.CheckPassed e -> checks++;
      case JournalEvent.OperationFailed e -> failure = e.reason();
      case JournalEvent.RollbackStarted e -> rollbacks = e.attempt();
      case JournalEvent.RollbackFailed e -> failure = e.reason();
      case JournalEvent.Cancelled e -> failure = e.reason();
      default -> {}
    }
    return new OperationProgress(
        plan,
        planDigest,
        after,
        nextSequence + 1,
        completed,
        inProgress,
        attempts,
        started,
        checks,
        rollbacks,
        failure);
  }

  private static ValidationResult reject(IssueCode code, IssueLocation at, String message) {
    return ValidationResult.of(List.of(ValidationIssue.error(code, at, message)));
  }

  public OperationPlan plan() {
    return plan;
  }

  public Checksum planDigest() {
    return planDigest;
  }

  /** The current durable state. */
  public OperationState state() {
    return state;
  }

  /** The sequence number the next entry must have; equals the number of entries so far. */
  public long nextSequence() {
    return nextSequence;
  }

  /** The number of steps that completed; they are always steps {@code 0..n-1}. */
  public int stepsCompleted() {
    return stepsCompleted;
  }

  /** The step that started and has not completed, or {@code null}. */
  public @Nullable Integer stepInProgress() {
    return stepInProgress;
  }

  /** The attempts made of {@link #stepInProgress()}, or 0 if none is in progress. */
  public int stepAttempts() {
    return stepAttempts;
  }

  /** Whether any step ever started, so world or player state may have changed. */
  public boolean anyStepStarted() {
    return anyStepStarted;
  }

  /** The number of checks that passed; they are always checks {@code 0..n-1}. */
  public int checksPassed() {
    return checksPassed;
  }

  /** The number of rollback attempts started. */
  public int rollbackAttempts() {
    return rollbackAttempts;
  }

  /** The most recent failure, rollback failure, or cancellation reason, or {@code null}. */
  public @Nullable FailureReason lastFailure() {
    return lastFailure;
  }

  @Override
  public String toString() {
    return "OperationProgress[" + plan.id() + " " + state + " next=" + nextSequence + "]";
  }
}
