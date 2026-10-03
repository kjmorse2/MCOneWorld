// SPDX-License-Identifier: MIT
/**
 * Reconciling the chunk index with the world after it was changed outside MCOneWorld, typically
 * trimmed with MCA Selector (Tool T6, operation {@code mconeworld:reconcile}). Chunks the world
 * gained are recorded; records of chunks it lost are first written, in full, to a report in {@code
 * reports/}, then removed. Removing a protected chunk's record needs explicit acknowledgement. The
 * world is only read.
 */
@NullMarked
package io.github.kjmorse2.mconeworld.tool.core.reconcile;

import org.jspecify.annotations.NullMarked;
