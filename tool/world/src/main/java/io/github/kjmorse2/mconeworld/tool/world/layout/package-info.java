// SPDX-License-Identifier: MIT
/**
 * Where a world keeps each dimension: the 26.1+ layout ({@code dimensions/<namespace>/<path>/}) and
 * the legacy layout (the world root, {@code DIM-1}, {@code DIM1}). Detection is read-only, never
 * follows symbolic links, and reports anything ambiguous instead of guessing.
 */
@NullMarked
package io.github.kjmorse2.mconeworld.tool.world.layout;

import org.jspecify.annotations.NullMarked;
