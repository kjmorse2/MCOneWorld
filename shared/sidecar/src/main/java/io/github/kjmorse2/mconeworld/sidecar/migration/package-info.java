// SPDX-License-Identifier: MIT
/**
 * Explicit, one-step-at-a-time manifest schema migrations (shared/AGENTS.md "Format evolution").
 *
 * <ul>
 *   <li><b>Never implicit.</b> Nothing here runs during an ordinary read, server start, or
 *       unrelated write. A caller must plan with {@link
 *       io.github.kjmorse2.mconeworld.sidecar.migration.MigrationPlanner} and then apply with
 *       {@link io.github.kjmorse2.mconeworld.sidecar.migration.MigrationRunner}.
 *   <li><b>Planning is side-effect free.</b> It is a dry run in memory, and its output is validated
 *       by the current manifest reader.
 *   <li><b>Applying</b> requires {@link
 *       io.github.kjmorse2.mconeworld.sidecar.migration.VerifiedBackup} evidence. It re-checks that
 *       the source is unchanged and that the migration is deterministic, then commits through the
 *       storage transaction protocol (ADR 0004), together with a persisted migration report. Any
 *       failure leaves the source manifest byte-for-byte unchanged.
 * </ul>
 *
 * <p>{@link io.github.kjmorse2.mconeworld.sidecar.migration.MigrationRegistry#builtIn()} is
 * currently <b>empty</b>: no manifest format before 1.0 was ever released. The framework is proven
 * by a synthetic, test-only v0.9 format ({@code docs/formats/manifest-v0.9-synthetic.md}).
 */
@NullMarked
package io.github.kjmorse2.mconeworld.sidecar.migration;

import org.jspecify.annotations.NullMarked;
