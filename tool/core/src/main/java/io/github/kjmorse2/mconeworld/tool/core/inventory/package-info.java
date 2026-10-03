// SPDX-License-Identifier: MIT
/**
 * A streamed, read-only inventory of a world's region files: chunk counts per dimension and a
 * digest that identifies the exact headers, so a plan made from one scan can prove at apply time
 * that the world has not changed since.
 */
@NullMarked
package io.github.kjmorse2.mconeworld.tool.core.inventory;

import org.jspecify.annotations.NullMarked;
