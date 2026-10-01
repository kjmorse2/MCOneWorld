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

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * The operation state machine's transition table: which event types are legal in which state, and
 * the state each leads to. Everything not listed is illegal. {@link JournalEventType#PLAN_RECORDED}
 * is legal only as the first journal entry, which leads to {@link OperationState#PLANNED}; it is
 * illegal in every state.
 *
 * <p>This table checks only the event <em>type</em>. {@link OperationProgress} adds the guards that
 * depend on the plan and the history, such as step order.
 *
 * <table>
 *   <caption>Legal transitions</caption>
 *   <tr><th>From</th><th>Event</th><th>To</th></tr>
 *   <tr><td>PLANNED</td><td>BACKUP_CONFIRMED</td><td>BACKED_UP</td></tr>
 *   <tr><td>PLANNED, BACKED_UP</td><td>CANCELLED</td><td>CANCELLED</td></tr>
 *   <tr><td>BACKED_UP</td><td>APPLY_STARTED</td><td>APPLYING</td></tr>
 *   <tr><td>APPLYING</td><td>STEP_STARTED, STEP_COMPLETED</td><td>APPLYING</td></tr>
 *   <tr><td>APPLYING</td><td>VALIDATION_STARTED</td><td>VALIDATING</td></tr>
 *   <tr><td>VALIDATING</td><td>CHECK_PASSED</td><td>VALIDATING</td></tr>
 *   <tr><td>VALIDATING</td><td>OPERATION_COMPLETED</td><td>COMPLETED</td></tr>
 *   <tr><td>APPLYING, VALIDATING</td><td>OPERATION_FAILED</td><td>FAILED</td></tr>
 *   <tr><td>FAILED, ROLLING_BACK, ROLLBACK_FAILED</td><td>ROLLBACK_STARTED</td>
 *       <td>ROLLING_BACK</td></tr>
 *   <tr><td>ROLLING_BACK</td><td>ROLLBACK_COMPLETED</td><td>ROLLED_BACK</td></tr>
 *   <tr><td>ROLLING_BACK</td><td>ROLLBACK_FAILED</td><td>ROLLBACK_FAILED</td></tr>
 * </table>
 *
 * <p>{@code ROLLBACK_STARTED} in {@code ROLLING_BACK} restarts a rollback attempt that was
 * interrupted before it could record its outcome.
 */
public final class OperationTransitions {
  private static final Map<OperationState, Map<JournalEventType, OperationState>> TABLE =
      buildTable();

  private OperationTransitions() {}

  /** The state after the first entry, {@link JournalEventType#PLAN_RECORDED}. */
  public static OperationState initialState() {
    return OperationState.PLANNED;
  }

  /** Returns the state {@code event} leads to from {@code from}, or empty if it is illegal. */
  public static Optional<OperationState> next(OperationState from, JournalEventType event) {
    return Optional.ofNullable(legalEvents(from).get(event));
  }

  /** Returns the legal event types in {@code from} and the state each leads to. */
  public static Map<JournalEventType, OperationState> legalEvents(OperationState from) {
    return TABLE.getOrDefault(from, Map.of());
  }

  private static Map<OperationState, Map<JournalEventType, OperationState>> buildTable() {
    Map<OperationState, Map<JournalEventType, OperationState>> table =
        new EnumMap<>(OperationState.class);
    put(table, OperationState.PLANNED, BACKUP_CONFIRMED, OperationState.BACKED_UP);
    put(table, OperationState.PLANNED, CANCELLED, OperationState.CANCELLED);
    put(table, OperationState.BACKED_UP, CANCELLED, OperationState.CANCELLED);
    put(table, OperationState.BACKED_UP, APPLY_STARTED, OperationState.APPLYING);
    put(table, OperationState.APPLYING, STEP_STARTED, OperationState.APPLYING);
    put(table, OperationState.APPLYING, STEP_COMPLETED, OperationState.APPLYING);
    put(table, OperationState.APPLYING, VALIDATION_STARTED, OperationState.VALIDATING);
    put(table, OperationState.APPLYING, OPERATION_FAILED, OperationState.FAILED);
    put(table, OperationState.VALIDATING, CHECK_PASSED, OperationState.VALIDATING);
    put(table, OperationState.VALIDATING, OPERATION_COMPLETED, OperationState.COMPLETED);
    put(table, OperationState.VALIDATING, OPERATION_FAILED, OperationState.FAILED);
    put(table, OperationState.FAILED, ROLLBACK_STARTED, OperationState.ROLLING_BACK);
    put(table, OperationState.ROLLING_BACK, ROLLBACK_STARTED, OperationState.ROLLING_BACK);
    put(table, OperationState.ROLLING_BACK, ROLLBACK_COMPLETED, OperationState.ROLLED_BACK);
    put(table, OperationState.ROLLING_BACK, ROLLBACK_FAILED, OperationState.ROLLBACK_FAILED);
    put(table, OperationState.ROLLBACK_FAILED, ROLLBACK_STARTED, OperationState.ROLLING_BACK);
    Map<OperationState, Map<JournalEventType, OperationState>> frozen =
        new EnumMap<>(OperationState.class);
    for (OperationState state : OperationState.values()) {
      Map<JournalEventType, OperationState> row = table.get(state);
      frozen.put(state, row == null ? Map.of() : Collections.unmodifiableMap(row));
    }
    return Collections.unmodifiableMap(frozen);
  }

  private static void put(
      Map<OperationState, Map<JournalEventType, OperationState>> table,
      OperationState from,
      JournalEventType event,
      OperationState to) {
    table.computeIfAbsent(from, s -> new EnumMap<>(JournalEventType.class)).put(event, to);
  }
}
