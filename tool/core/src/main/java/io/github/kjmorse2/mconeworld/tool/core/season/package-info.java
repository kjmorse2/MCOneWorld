// SPDX-License-Identifier: MIT
/**
 * Planning a new season (Tool T8): {@link
 * io.github.kjmorse2.mconeworld.tool.core.season.SeasonPlanner} proposes a distant, aligned origin
 * in unused terrain and validates a manual pick. Pure logic over the world's occupied chunks and
 * the archive's seasons; it reads and writes nothing. The season transition (T9) uses it.
 */
@NullMarked
package io.github.kjmorse2.mconeworld.tool.core.season;

import org.jspecify.annotations.NullMarked;
