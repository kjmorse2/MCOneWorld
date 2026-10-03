// SPDX-License-Identifier: MIT
/**
 * Creating the next season (Tool T9, operation {@code mconeworld:season_transition}): the only
 * operation that writes to the world. With the server stopped, on the pinned version and the 26.1+
 * layout only, and after a full backup of the world as it is, it archives the End dimension
 * directory into the sidecar (ADR 0010), then appends and activates the new season with its
 * activation pending application by the server (ADR 0011). Until the season is committed, a failure
 * restores the End from the operation's own archive.
 */
@NullMarked
package io.github.kjmorse2.mconeworld.tool.core.transition;

import org.jspecify.annotations.NullMarked;
