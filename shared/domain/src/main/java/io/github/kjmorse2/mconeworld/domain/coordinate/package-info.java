// SPDX-License-Identifier: MIT
/**
 * Checked coordinate types and conversions.
 *
 * <h2>Invariants (root AGENTS.md §7–§8, shared/AGENTS.md "Coordinates")</h2>
 *
 * <ul>
 *   <li><b>Absolute coordinates are authoritative.</b> {@link
 *       io.github.kjmorse2.mconeworld.domain.coordinate.BlockPosition} is Minecraft's real
 *       coordinate. {@link io.github.kjmorse2.mconeworld.domain.coordinate.SeasonLocalPosition} is
 *       a derived presentation value: {@code local = absolute - origin} on X and Z only.
 *   <li><b>Every position carries a dimension.</b> There is no dimension-less position type.
 *   <li><b>Distinct types for distinct spaces.</b> Block, chunk, region, and season-local positions
 *       are separate types and cannot be interchanged.
 *   <li><b>Floor division.</b> Block to chunk is {@code floorDiv(x, 16)} and chunk to region is
 *       {@code floorDiv(x, 32)}. Truncating division is never used, so block {@code -1} is in chunk
 *       {@code -1}, not chunk {@code 0}.
 *   <li><b>Checked arithmetic.</b> X/Z math uses {@code long} with {@code Math.*Exact}.
 *       Out-of-range results throw {@link
 *       io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException}. Nothing is clamped,
 *       wrapped, truncated, or rounded, except in methods whose name says {@code Floor}.
 *   <li><b>No doubles.</b> Integer transforms never pass through floating point.
 * </ul>
 *
 * <h2>Two kinds of bounds</h2>
 *
 * <ul>
 *   <li><b>Representation bounds</b> ({@link
 *       io.github.kjmorse2.mconeworld.domain.coordinate.CoordinateLimits}) are what these types can
 *       hold. They are chosen so that every block position has an {@code int} chunk and region, and
 *       so they are wider than any Minecraft world. Constructors enforce them.
 *   <li><b>World bounds</b> ({@link io.github.kjmorse2.mconeworld.domain.coordinate.WorldBounds})
 *       are the limits of a particular game version or dimension. They are checked separately and
 *       explicitly, because they differ per dimension and per version and may change. Constructors
 *       never apply them implicitly.
 * </ul>
 *
 * <p>Overworld/Nether scaling is an explicit, dimension-checked operation in {@link
 * io.github.kjmorse2.mconeworld.domain.coordinate.OverworldNetherScaling}. It is never a generic
 * property of arbitrary dimensions.
 */
@NullMarked
package io.github.kjmorse2.mconeworld.domain.coordinate;

import org.jspecify.annotations.NullMarked;
