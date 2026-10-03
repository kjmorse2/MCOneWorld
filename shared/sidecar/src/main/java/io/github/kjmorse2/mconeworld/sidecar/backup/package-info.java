// SPDX-License-Identifier: MIT
/**
 * Backups inside the sidecar (ADR 0012; {@code docs/formats/backup-v1.md}): the {@code
 * mconeworld-backup} 1.0 manifest that lists every file of a backup with its size and SHA-256
 * ({@link io.github.kjmorse2.mconeworld.sidecar.backup.BackupManifestCodec}), verification of a
 * backup against it ({@link io.github.kjmorse2.mconeworld.sidecar.backup.BackupVerifier}), and the
 * commit that records a verified backup in the sidecar manifest ({@link
 * io.github.kjmorse2.mconeworld.sidecar.backup.BackupRecords}).
 *
 * <p>Copying files into a backup is the caller's job (the Tool), through {@link
 * io.github.kjmorse2.mconeworld.sidecar.storage.BackupFileWriter}.
 */
@NullMarked
package io.github.kjmorse2.mconeworld.sidecar.backup;

import org.jspecify.annotations.NullMarked;
