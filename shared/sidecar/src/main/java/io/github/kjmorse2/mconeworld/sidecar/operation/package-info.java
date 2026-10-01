// SPDX-License-Identifier: MIT
/**
 * Persistence of operation plans and journals in the sidecar's {@code operations/} area. The
 * normative format is {@code docs/formats/operation-journal-v1.md}; the decisions are in ADR 0006.
 *
 * <ul>
 *   <li>{@link io.github.kjmorse2.mconeworld.sidecar.operation.OperationPlanCodec}: plan bytes ⇄
 *       {@link io.github.kjmorse2.mconeworld.domain.operation.OperationPlan}, and the plan digest
 *   <li>{@link io.github.kjmorse2.mconeworld.sidecar.operation.OperationJournals}: list, inspect
 *       (read-only), create, open, and explicitly repair a torn tail
 *   <li>{@link io.github.kjmorse2.mconeworld.sidecar.operation.OperationJournal}: appends events
 *       under the writer lock and returns a {@link
 *       io.github.kjmorse2.mconeworld.sidecar.operation.JournalAck} only once the entry is durable.
 *       Only such an ack carries a {@link
 *       io.github.kjmorse2.mconeworld.sidecar.operation.MutationPermit}.
 * </ul>
 *
 * <p>The rules themselves (legal transitions, guards, recommendations) live in {@code
 * domain.operation}; this package stores and verifies. Nothing here resumes an operation.
 */
@NullMarked
package io.github.kjmorse2.mconeworld.sidecar.operation;

import org.jspecify.annotations.NullMarked;
