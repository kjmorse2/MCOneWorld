// SPDX-License-Identifier: MIT
/**
 * Platform-neutral MCOneWorld domain values: identifiers, dimensions, positions, seasons, and
 * structured validation results.
 *
 * <p>Everything here is shared by at least two user-facing products (Tool, Paper, Client) and must
 * never reference Paper, Bukkit, Fabric, Minecraft, JavaFX, or MCA Selector types. Absolute
 * coordinates are authoritative; season-local coordinates are derived views (root {@code AGENTS.md}
 * §8).
 *
 * <p>All packages in this module are {@code @NullMarked}: types are non-null unless annotated
 * {@code @Nullable}.
 */
@NullMarked
package io.github.kjmorse2.mconeworld.domain;

import org.jspecify.annotations.NullMarked;
