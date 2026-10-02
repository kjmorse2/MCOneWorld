// SPDX-License-Identifier: MIT
/**
 * World-file access for MCOneWorld Tool (tool/AGENTS.md): reading {@code level.dat}, the dimension
 * layout, region-file headers, and {@code session.lock}, plus the End-dimension archive of ADR
 * 0010, the only world write in v1.
 *
 * <p>Parts are adapted from MCA Selector (MIT, © Querz, ADR 0009); such files say so in their
 * header. Nothing here depends on the sidecar, JavaFX, or any Minecraft artifact.
 */
@NullMarked
package io.github.kjmorse2.mconeworld.tool.world;

import org.jspecify.annotations.NullMarked;
