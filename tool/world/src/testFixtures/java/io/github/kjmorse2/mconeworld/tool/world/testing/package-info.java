// SPDX-License-Identifier: MIT
/**
 * Test support for {@code tool:world} and its users: {@link
 * io.github.kjmorse2.mconeworld.tool.world.testing.SyntheticWorld} builds worlds on disk in code,
 * with no Mojang assets, and {@link io.github.kjmorse2.mconeworld.tool.world.testing.NbtWriter}
 * writes the NBT they need. The Tool itself never writes NBT (ADR 0010); this writer exists only
 * for tests.
 */
@NullMarked
package io.github.kjmorse2.mconeworld.tool.world.testing;

import org.jspecify.annotations.NullMarked;
