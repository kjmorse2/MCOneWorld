// SPDX-License-Identifier: MIT
/**
 * A bounded, read-only NBT reader. The Tool never writes NBT (ADR 0010), so there is no writer.
 * Every input is untrusted (root AGENTS.md §20): decompressed size, nesting depth, and every length
 * are bounded before anything is allocated, and any malformed input fails with {@link
 * io.github.kjmorse2.mconeworld.tool.world.nbt.NbtException}.
 */
@NullMarked
package io.github.kjmorse2.mconeworld.tool.world.nbt;

import org.jspecify.annotations.NullMarked;
