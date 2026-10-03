// SPDX-License-Identifier: MIT
/**
 * Initializing a sidecar for an existing world (Tool T5, operation {@code
 * mconeworld:sidecar_init}): the archive's first manifest, bound to the world, and a chunk index
 * that records every existing chunk as legacy provenance (root AGENTS.md §9). The world is only
 * read.
 *
 * <p>The operation follows the safe protocol (root §10) through an operation journal, and every
 * step can be retried, so an interrupted initialization resumes explicitly ({@link
 * io.github.kjmorse2.mconeworld.tool.core.init.SidecarInitializer#resume}) instead of leaving a
 * half-made sidecar.
 */
@NullMarked
package io.github.kjmorse2.mconeworld.tool.core.init;

import org.jspecify.annotations.NullMarked;
