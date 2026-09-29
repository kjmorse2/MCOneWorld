// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.storage;

import io.github.kjmorse2.mconeworld.domain.Checksum;

/**
 * A data file staged in a transaction. Put the same path, checksum, and size into the manifest's
 * reference (for example a {@code FileReference}).
 *
 * @param target final sidecar-relative path
 * @param checksum SHA-256 of the staged content
 * @param sizeBytes content size
 */
public record StagedFile(SidecarPath target, Checksum checksum, long sizeBytes) {}
