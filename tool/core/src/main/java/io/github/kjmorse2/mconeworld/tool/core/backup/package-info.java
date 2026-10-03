// SPDX-License-Identifier: MIT
/**
 * Full backups of a world and its sidecar into the sidecar's {@code backups/} area (Tool T7,
 * operation {@code mconeworld:backup}; ADR 0012, {@code backup-v1.md}). Copy, verify, then record:
 * only a verified backup is ever recorded. Restore is not automated yet: {@link
 * io.github.kjmorse2.mconeworld.tool.core.backup.RestoreInstructions} gives the exact manual steps.
 */
@NullMarked
package io.github.kjmorse2.mconeworld.tool.core.backup;

import org.jspecify.annotations.NullMarked;
