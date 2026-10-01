// SPDX-License-Identifier: MIT
/**
 * Operation plans, operation states, and journal events: the platform-neutral description of every
 * world or sidecar mutation (root {@code AGENTS.md} §10, {@code shared/AGENTS.md} "Operation plan
 * and journal").
 *
 * <p>Nothing here performs an action. Shared code <em>represents and validates</em> intent and
 * state; platform modules execute steps, and only after the journal has durably acknowledged the
 * event that starts them. The normative formats and recovery semantics are in {@code
 * docs/formats/operation-journal-v1.md} and ADR 0006.
 *
 * <ul>
 *   <li>{@link io.github.kjmorse2.mconeworld.domain.operation.OperationPlan}: the immutable plan
 *   <li>{@link io.github.kjmorse2.mconeworld.domain.operation.OperationTransitions}: the state
 *       machine's transition table
 *   <li>{@link io.github.kjmorse2.mconeworld.domain.operation.OperationProgress}: replays journal
 *       events against a plan, enforcing every ordering rule
 *   <li>{@link io.github.kjmorse2.mconeworld.domain.operation.RecoveryRecommendation}: what to do
 *       after an interruption. It is only ever a recommendation: nothing resumes automatically.
 * </ul>
 */
@NullMarked
package io.github.kjmorse2.mconeworld.domain.operation;

import org.jspecify.annotations.NullMarked;
