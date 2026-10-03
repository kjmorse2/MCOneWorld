// SPDX-License-Identifier: MIT
/**
 * Runs a Tool operation through its journal (root AGENTS.md §10): confirm the backup, re-observe
 * the preconditions, then every step and check, appending each transition durably. The same loop
 * starts and resumes an operation, so an interrupted one continues exactly where its journal stops.
 */
@NullMarked
package io.github.kjmorse2.mconeworld.tool.core.operation;

import org.jspecify.annotations.NullMarked;
