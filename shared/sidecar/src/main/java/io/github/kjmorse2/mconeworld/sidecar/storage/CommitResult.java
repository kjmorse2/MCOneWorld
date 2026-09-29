// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.storage;

import io.github.kjmorse2.mconeworld.domain.Checksum;
import java.util.UUID;

/**
 * Outcome of a successful commit. The new manifest is in place and readable.
 *
 * @param transactionId the committed transaction
 * @param revision the committed manifest revision
 * @param manifestChecksum checksum of the committed manifest bytes
 * @param directorySyncSupported whether directory metadata (the renames) was flushed to stable
 *     storage. If {@code false}, the platform could not sync directories, and a power loss shortly
 *     after the commit may revert to the previous manifest. It can never leave a torn one.
 * @param cleanupComplete whether the staging directory was removed. If {@code false}, {@link
 *     SidecarStore#inspect()} reports it and {@link SidecarWriter#recover()} removes it.
 */
public record CommitResult(
    UUID transactionId,
    long revision,
    Checksum manifestChecksum,
    boolean directorySyncSupported,
    boolean cleanupComplete) {}
