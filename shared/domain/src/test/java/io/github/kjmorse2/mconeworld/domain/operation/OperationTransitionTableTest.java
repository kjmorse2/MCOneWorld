// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.operation;

import static io.github.kjmorse2.mconeworld.domain.operation.JournalEventType.APPLY_STARTED;
import static io.github.kjmorse2.mconeworld.domain.operation.JournalEventType.BACKUP_CONFIRMED;
import static io.github.kjmorse2.mconeworld.domain.operation.JournalEventType.CANCELLED;
import static io.github.kjmorse2.mconeworld.domain.operation.JournalEventType.CHECK_PASSED;
import static io.github.kjmorse2.mconeworld.domain.operation.JournalEventType.OPERATION_COMPLETED;
import static io.github.kjmorse2.mconeworld.domain.operation.JournalEventType.OPERATION_FAILED;
import static io.github.kjmorse2.mconeworld.domain.operation.JournalEventType.ROLLBACK_COMPLETED;
import static io.github.kjmorse2.mconeworld.domain.operation.JournalEventType.ROLLBACK_FAILED;
import static io.github.kjmorse2.mconeworld.domain.operation.JournalEventType.ROLLBACK_STARTED;
import static io.github.kjmorse2.mconeworld.domain.operation.JournalEventType.STEP_COMPLETED;
import static io.github.kjmorse2.mconeworld.domain.operation.JournalEventType.STEP_STARTED;
import static io.github.kjmorse2.mconeworld.domain.operation.JournalEventType.VALIDATION_STARTED;
import static io.github.kjmorse2.mconeworld.domain.operation.OperationState.APPLYING;
import static io.github.kjmorse2.mconeworld.domain.operation.OperationState.BACKED_UP;
import static io.github.kjmorse2.mconeworld.domain.operation.OperationState.COMPLETED;
import static io.github.kjmorse2.mconeworld.domain.operation.OperationState.FAILED;
import static io.github.kjmorse2.mconeworld.domain.operation.OperationState.PLANNED;
import static io.github.kjmorse2.mconeworld.domain.operation.OperationState.ROLLED_BACK;
import static io.github.kjmorse2.mconeworld.domain.operation.OperationState.ROLLING_BACK;
import static io.github.kjmorse2.mconeworld.domain.operation.OperationState.VALIDATING;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The exhaustive transition table: every (state, event type) pair, 10 × 13 = 130 cases. The
 * expected legal transitions are written out independently here, so a change to {@link
 * OperationTransitions} must be mirrored deliberately. Everything else must be illegal, both in the
 * table and through {@link OperationProgress} (see {@link ProgressStates} for how each state is
 * reached).
 */
class OperationTransitionTableTest {
  private static final Map<OperationState, Map<JournalEventType, OperationState>> LEGAL =
      Map.of(
          PLANNED,
          Map.of(BACKUP_CONFIRMED, BACKED_UP, CANCELLED, OperationState.CANCELLED),
          BACKED_UP,
          Map.of(APPLY_STARTED, APPLYING, CANCELLED, OperationState.CANCELLED),
          APPLYING,
          Map.of(
              STEP_STARTED, APPLYING,
              STEP_COMPLETED, APPLYING,
              VALIDATION_STARTED, VALIDATING,
              OPERATION_FAILED, FAILED),
          VALIDATING,
          Map.of(
              CHECK_PASSED, VALIDATING, OPERATION_COMPLETED, COMPLETED, OPERATION_FAILED, FAILED),
          FAILED,
          Map.of(ROLLBACK_STARTED, ROLLING_BACK),
          ROLLING_BACK,
          Map.of(
              ROLLBACK_STARTED, ROLLING_BACK,
              ROLLBACK_COMPLETED, ROLLED_BACK,
              ROLLBACK_FAILED, OperationState.ROLLBACK_FAILED),
          OperationState.ROLLBACK_FAILED,
          Map.of(ROLLBACK_STARTED, ROLLING_BACK),
          COMPLETED,
          Map.of(),
          ROLLED_BACK,
          Map.of(),
          OperationState.CANCELLED,
          Map.of());

  static Stream<Arguments> everyPair() {
    List<Arguments> pairs = new ArrayList<>();
    for (OperationState state : OperationState.values()) {
      for (JournalEventType event : JournalEventType.values()) {
        pairs.add(Arguments.of(state, event));
      }
    }
    return pairs.stream();
  }

  @Test
  void theTableCoversEveryState() {
    assertThat(LEGAL).containsOnlyKeys(OperationState.values());
    assertThat(everyPair()).hasSize(130);
  }

  @ParameterizedTest(name = "{0} + {1}")
  @MethodSource("everyPair")
  void transitionMatchesTheTable(OperationState state, JournalEventType event) {
    Optional<OperationState> expected = Optional.ofNullable(LEGAL.get(state).get(event));
    assertThat(OperationTransitions.next(state, event)).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0} + {1}")
  @MethodSource("everyPair")
  void illegalPairsAreRejectedByProgressWithAStableCode(
      OperationState state, JournalEventType event) {
    if (LEGAL.get(state).containsKey(event)) {
      return;
    }
    OperationProgress progress = ProgressStates.reach(state);
    for (JournalEvent candidate : ProgressStates.eventsOfType(event, progress)) {
      assertThat(progress.check(candidate).has(OperationIssueCodes.TRANSITION_ILLEGAL))
          .as("%s in %s", candidate, state)
          .isTrue();
    }
  }

  @ParameterizedTest(name = "{0} + {1}")
  @MethodSource("everyPair")
  void legalPairsAreAcceptedWhenTheirGuardsHold(OperationState state, JournalEventType event) {
    OperationState target = LEGAL.get(state).get(event);
    if (target == null) {
      return;
    }
    OperationProgress progress = ProgressStates.reachAllowing(state, event);
    JournalEvent candidate = ProgressStates.satisfying(event, progress);
    assertThat(progress.check(candidate).issues()).isEmpty();
    assertThat(progress.apply(candidate).state()).isEqualTo(target);
  }

  @Test
  void planRecordedIsLegalOnlyAsTheFirstEntry() {
    for (OperationState state : OperationState.values()) {
      assertThat(OperationTransitions.next(state, JournalEventType.PLAN_RECORDED)).isEmpty();
    }
    assertThat(OperationTransitions.initialState()).isEqualTo(PLANNED);
  }

  @Test
  void terminalStatesHaveNoLegalEvents() {
    for (OperationState state : OperationState.values()) {
      assertThat(OperationTransitions.legalEvents(state).isEmpty()).isEqualTo(state.isTerminal());
    }
  }

  @Test
  void onlyStartEventsAuthorizeMutation() {
    for (JournalEventType type : JournalEventType.values()) {
      assertThat(type.authorizesMutation())
          .isEqualTo(type == STEP_STARTED || type == ROLLBACK_STARTED);
    }
  }
}
