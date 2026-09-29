// SPDX-License-Identifier: MIT
/**
 * Storage safety for the sidecar directory: portable relative paths, and (from Prompt 05)
 * checksums, locking, and atomic writes. Everything operates on a caller-supplied sidecar root.
 * Nothing here discovers server paths through global state.
 */
@NullMarked
package io.github.kjmorse2.mconeworld.sidecar.storage;

import org.jspecify.annotations.NullMarked;
