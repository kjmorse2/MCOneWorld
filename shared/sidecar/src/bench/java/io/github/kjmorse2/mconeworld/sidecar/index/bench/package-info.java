// SPDX-License-Identifier: MIT
/**
 * <b>The chunk-index benchmark harness</b> (built in Prompt 08, kept after the spike candidates
 * were removed in Prompt 09). It measures the production index, {@code SidecarChunkIndex}, through
 * {@link io.github.kjmorse2.mconeworld.sidecar.index.bench.ProductionIndex}, in the block layouts
 * listed by {@link io.github.kjmorse2.mconeworld.sidecar.index.bench.Candidate}.
 *
 * <ul>
 *   <li>{@link io.github.kjmorse2.mconeworld.sidecar.index.bench.BenchmarkDriver} runs every
 *       (candidate, dataset) case in fresh JVMs with a fixed heap and collects result lines.
 *   <li>{@link io.github.kjmorse2.mconeworld.sidecar.index.bench.BenchmarkCase} is one case: a
 *       build phase and, in a new JVM, a query-and-update phase.
 *   <li>{@link io.github.kjmorse2.mconeworld.sidecar.index.bench.CorruptionProbe} and {@link
 *       io.github.kjmorse2.mconeworld.sidecar.index.bench.CrashProbe} test gate budgets B2 and B3.
 *   <li>{@link io.github.kjmorse2.mconeworld.sidecar.index.bench.Summarize} turns raw result lines
 *       into the Markdown tables in {@code docs/benchmarks/chunk-index/}.
 * </ul>
 *
 * <p>The raw result format is documented in {@code docs/benchmarks/chunk-index/README.md}.
 */
@NullMarked
package io.github.kjmorse2.mconeworld.sidecar.index.bench;

import org.jspecify.annotations.NullMarked;
