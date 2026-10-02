// SPDX-License-Identifier: MIT
/**
 * Headless services of MCOneWorld Tool (tool/AGENTS.md): opening a world with its sidecar,
 * initialization, reconciliation, backups, and season planning and transition. Every mutation
 * follows the safe-operation protocol (root AGENTS.md §10) through the shared operation journals.
 *
 * <p>The JavaFX UI and the command line both drive these services; nothing here uses JavaFX.
 */
@NullMarked
package io.github.kjmorse2.mconeworld.tool.core;

import org.jspecify.annotations.NullMarked;
