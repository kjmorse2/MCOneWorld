// SPDX-License-Identifier: MIT
/**
 * Region files ({@code r.<x>.<z>.mca}) read through their 8 KiB headers only: which chunks exist
 * and when each was last saved, without decompressing any chunk. Scans stream one region file at a
 * time, so memory does not grow with the world.
 */
@NullMarked
package io.github.kjmorse2.mconeworld.tool.world.region;

import org.jspecify.annotations.NullMarked;
