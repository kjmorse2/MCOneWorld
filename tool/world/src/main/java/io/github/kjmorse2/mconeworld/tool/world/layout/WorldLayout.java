// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.world.layout;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationResult;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The dimensions of one world.
 *
 * @param worldDir the world directory
 * @param kind the layout
 * @param dimensions the dimensions found, the vanilla ones first, then the rest by key
 * @param findings everything ambiguous or skipped while detecting
 */
public record WorldLayout(
    Path worldDir,
    LayoutKind kind,
    List<DimensionDirectory> dimensions,
    ValidationResult findings) {
  public WorldLayout {
    Objects.requireNonNull(worldDir, "worldDir");
    Objects.requireNonNull(kind, "kind");
    dimensions = List.copyOf(dimensions);
    Objects.requireNonNull(findings, "findings");
  }

  /** The directory of {@code key}, if the world has that dimension. */
  public Optional<DimensionDirectory> dimension(DimensionKey key) {
    return dimensions.stream().filter(d -> d.key().equals(key)).findFirst();
  }
}
