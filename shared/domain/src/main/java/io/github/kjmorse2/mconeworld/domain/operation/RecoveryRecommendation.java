// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.operation;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What to do about an operation, derived only from its last durable state. It is a
 * <b>recommendation</b>: shared code never resumes, retries, or rolls back anything. A caller acts
 * only after the person (or an explicitly configured policy) chooses an action.
 *
 * @param lastDurableState the state of the last intact journal entry, or {@code null} if no entry
 *     could be trusted
 * @param action the recommended action
 * @param alternatives other acceptable actions, in declaration order; never contains {@code action}
 * @param partialEffectsPossible whether world or player state may be in an intermediate state that
 *     matches neither the state before the operation nor its completed result
 * @param requiresConfirmation whether a person must confirm before anything is recorded or done. It
 *     is {@code false} only for {@link RecoveryAction#NONE}.
 * @param nextStep the step to start or restart, for {@code RESUME_APPLY} and {@code RETRY_STEP}
 * @param nextCheck the check to run, for {@code RESUME_VALIDATION}
 * @param explanation a single-line explanation for the person deciding
 */
public record RecoveryRecommendation(
    @Nullable OperationState lastDurableState,
    RecoveryAction action,
    Set<RecoveryAction> alternatives,
    boolean partialEffectsPossible,
    boolean requiresConfirmation,
    @Nullable Integer nextStep,
    @Nullable Integer nextCheck,
    String explanation) {

  public RecoveryRecommendation {
    Objects.requireNonNull(action, "action");
    Objects.requireNonNull(explanation, "explanation");
    EnumSet<RecoveryAction> copy = EnumSet.noneOf(RecoveryAction.class);
    copy.addAll(alternatives);
    if (copy.contains(action)) {
      throw new IllegalArgumentException("Alternatives must not repeat the recommended action");
    }
    if (requiresConfirmation == (action == RecoveryAction.NONE)) {
      throw new IllegalArgumentException("Only NONE needs no confirmation");
    }
    alternatives = Collections.unmodifiableSet(copy);
  }

  /**
   * The recommendation when the journal cannot be trusted past {@code lastDurableState} (for
   * example corruption in the middle): a person must inspect everything, assuming any later state.
   */
  public static RecoveryRecommendation manualInspection(
      @Nullable OperationState lastDurableState, String explanation) {
    return new RecoveryRecommendation(
        lastDurableState,
        RecoveryAction.MANUAL_INSPECTION,
        Set.of(),
        true,
        true,
        null,
        null,
        explanation);
  }
}
