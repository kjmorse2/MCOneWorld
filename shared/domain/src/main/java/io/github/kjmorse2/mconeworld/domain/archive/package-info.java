// SPDX-License-Identifier: MIT
/**
 * The manifest-independent archive state: every season plus the records that hang off it (protected
 * regions, portal markers, backups, player archives), and the explicit active-season reference.
 *
 * <p>{@link io.github.kjmorse2.mconeworld.domain.archive.ArchiveState} is what the sidecar manifest
 * (Prompt 04) decodes into and encodes from, but it knows nothing about JSON or files. Constructors
 * enforce structural rules. {@link
 * io.github.kjmorse2.mconeworld.domain.archive.ArchiveState#validate()} checks the cross-record
 * semantics, and reports every problem instead of stopping at the first.
 */
@NullMarked
package io.github.kjmorse2.mconeworld.domain.archive;

import org.jspecify.annotations.NullMarked;
