// SPDX-License-Identifier: MIT
/**
 * Test support for the chunk index ({@code sidecar.index}): the in-memory {@link
 * io.github.kjmorse2.mconeworld.fixtures.index.ReferenceChunkIndex}, the {@link
 * io.github.kjmorse2.mconeworld.fixtures.index.ChunkIndexDifferential} checker that compares any
 * implementation against it, and the deterministic synthetic datasets ({@link
 * io.github.kjmorse2.mconeworld.fixtures.index.ChunkDatasets}) used by tests and benchmarks.
 *
 * <p>Datasets are generated from a seed and streamed in {@link
 * io.github.kjmorse2.mconeworld.sidecar.index.ChunkOrder}, so even the ten-million-record dataset
 * is never materialized and nothing generated is committed.
 */
@NullMarked
package io.github.kjmorse2.mconeworld.fixtures.index;

import org.jspecify.annotations.NullMarked;
