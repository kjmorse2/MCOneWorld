// SPDX-License-Identifier: MIT
/**
 * Opening a world together with its sidecar, read-only: {@link
 * io.github.kjmorse2.mconeworld.tool.core.session.ToolSession#inspect} gathers everything the Tool
 * shows on its status screen and decides which actions are available, and why the others are not.
 * Inspection never writes: it takes no lock, recovers nothing, and migrates nothing (tool/AGENTS.md
 * §3).
 */
@NullMarked
package io.github.kjmorse2.mconeworld.tool.core.session;

import org.jspecify.annotations.NullMarked;
