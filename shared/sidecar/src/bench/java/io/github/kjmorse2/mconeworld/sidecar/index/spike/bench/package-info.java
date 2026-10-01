// SPDX-License-Identifier: MIT
/**
 * <b>Prompt 08 spike: the chunk-index benchmark harness.</b>
 *
 * <ul>
 *   <li>{@link io.github.kjmorse2.mconeworld.sidecar.index.spike.bench.BenchmarkDriver} runs every
 *       (candidate, dataset) case in fresh JVMs with a fixed heap and collects result lines.
 *   <li>{@link io.github.kjmorse2.mconeworld.sidecar.index.spike.bench.BenchmarkCase} is one case:
 *       a build phase and, in a new JVM, a query-and-update phase.
 *   <li>{@link io.github.kjmorse2.mconeworld.sidecar.index.spike.bench.CorruptionProbe} and {@link
 *       io.github.kjmorse2.mconeworld.sidecar.index.spike.bench.CrashProbe} test gate budgets B2
 *       and B3.
 *   <li>{@link io.github.kjmorse2.mconeworld.sidecar.index.spike.bench.Summarize} turns raw result
 *       lines into the Markdown tables in {@code docs/benchmarks/chunk-index/}.
 * </ul>
 *
 * <p>The raw result format is documented in {@code docs/benchmarks/chunk-index/README.md}.
 */
@NullMarked
package io.github.kjmorse2.mconeworld.sidecar.index.spike.bench;

import org.jspecify.annotations.NullMarked;
