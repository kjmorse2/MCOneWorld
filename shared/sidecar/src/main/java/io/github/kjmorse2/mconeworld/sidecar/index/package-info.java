// SPDX-License-Identifier: MIT
/**
 * The chunk-index <b>contract</b>: how millions of {@link
 * io.github.kjmorse2.mconeworld.domain.provenance.ChunkProvenance} records are queried and updated
 * without ever being loaded at once.
 *
 * <p><b>Status (Prompt 08): API only.</b> No persistent implementation exists. The on-disk encoding
 * is proposed in ADR 0007 and is not accepted. Until it is, the only implementation is the
 * in-memory reference in {@code shared:test-fixtures}, which is used for semantics and differential
 * testing. Do not persist chunk-index data through anything else.
 *
 * <p>The contract, in one place so every implementation behaves identically:
 *
 * <ul>
 *   <li><b>Semantics live in shared code.</b> How a mutation changes a record is {@link
 *       io.github.kjmorse2.mconeworld.sidecar.index.ChunkMutation#applyTo}. Which records a query
 *       returns is {@link io.github.kjmorse2.mconeworld.sidecar.index.ChunkQuery#matches}. The
 *       order is {@link io.github.kjmorse2.mconeworld.sidecar.index.ChunkOrder}. Implementations
 *       only store and find records.
 *   <li><b>Streaming.</b> Scans return a {@link
 *       io.github.kjmorse2.mconeworld.sidecar.index.ChunkCursor}. Paging resumes from the last
 *       position returned ({@link io.github.kjmorse2.mconeworld.sidecar.index.ChunkQuery#after}).
 *       No method returns a collection of records.
 *   <li><b>Explicit ownership.</b> Whoever opens a reader, cursor, or batch closes it. Closing a
 *       reader invalidates its cursors; closing the index invalidates everything. Use after close
 *       is a programming error ({@link IllegalStateException}).
 *   <li><b>Snapshots.</b> A reader sees the state committed when it was opened, and nothing later.
 *   <li><b>Atomic batches.</b> A batch commits all of its mutations or none. One rejected mutation
 *       poisons the batch, which can then only be closed.
 *   <li><b>One writer.</b> At most one batch is open at a time, matching the sidecar lock (ADR
 *       0004).
 * </ul>
 */
@NullMarked
package io.github.kjmorse2.mconeworld.sidecar.index;

import org.jspecify.annotations.NullMarked;
