// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.world.layout;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.tool.world.region.RegionKind;
import java.nio.file.Path;
import java.util.Objects;

/**
 * One dimension's directory. In the 26.1+ layout it holds the dimension's region, entity, and POI
 * files and its own saved data ({@code data/}, which for the End includes the dragon fight).
 *
 * @param key the dimension
 * @param root the dimension directory
 */
public record DimensionDirectory(DimensionKey key, Path root) {
  public DimensionDirectory {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(root, "root");
  }

  /** The directory holding {@code kind}'s {@code r.<x>.<z>.mca} files. */
  public Path directory(RegionKind kind) {
    return root.resolve(kind.directoryName());
  }

  /** The dimension's saved-data directory, {@code data/}. */
  public Path data() {
    return root.resolve("data");
  }
}
