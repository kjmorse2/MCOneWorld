// SPDX-License-Identifier: MIT
/**
 * <b>Prompt 08 spike: not production code.</b> Candidate chunk-index encodings, built only to
 * measure them against the budgets in {@code docs/benchmarks/chunk-index/budgets.md}:
 *
 * <ul>
 *   <li>{@link io.github.kjmorse2.mconeworld.sidecar.index.spike.RegionStoreIndex} (C1):
 *       region-partitioned, compressed, checksummed record blocks with a catalog, in either one
 *       file per region or append-only segment files.
 *   <li>{@link io.github.kjmorse2.mconeworld.sidecar.index.spike.RegionLogIndex} (C1-log, the
 *       design ADR 0007 recommends): C1 segment blocks as a checkpoint plus an append-only log of
 *       mutations.
 *   <li>{@link io.github.kjmorse2.mconeworld.sidecar.index.spike.SqliteIndex} (C2): SQLite through
 *       sqlite-jdbc.
 *   <li>{@link io.github.kjmorse2.mconeworld.sidecar.index.spike.RoaringIndex} (C3): Roaring
 *       bitmaps for membership and season sets, with C1's region blocks as side tables.
 *   <li>{@link io.github.kjmorse2.mconeworld.sidecar.index.spike.MvStoreIndex} (C4): H2 MVStore, a
 *       pure-Java log-structured key-value store.
 * </ul>
 *
 * <p>Each implements the {@code sidecar.index} contract and is checked against the reference index
 * by differential tests before it is benchmarked. Their on-disk layouts are deliberately
 * unversioned and use enum ordinals: they are throwaway measurement vehicles. The proposed format
 * is specified separately in {@code docs/formats/chunk-index-v1-draft.md}.
 */
@NullMarked
package io.github.kjmorse2.mconeworld.sidecar.index.spike;

import org.jspecify.annotations.NullMarked;
