// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.world.region;

import io.github.kjmorse2.mconeworld.domain.coordinate.RegionPosition;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationIssue;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * The header of one region file.
 *
 * @param region which region the file name says it is
 * @param kind region, entities, or POI
 * @param file the file
 * @param fileSize its size in bytes; 0 is normal and means "no chunks"
 * @param chunks the valid entries, in header order
 * @param issues invalid, overlapping, or out-of-bounds entries, and a truncated header
 */
public record RegionScan(
    RegionPosition region,
    RegionKind kind,
    Path file,
    long fileSize,
    List<ChunkEntry> chunks,
    List<ValidationIssue> issues) {
  public RegionScan {
    Objects.requireNonNull(region, "region");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(file, "file");
    chunks = List.copyOf(chunks);
    issues = List.copyOf(issues);
  }
}
