// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index.spike;

import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexException;
import java.nio.file.Path;
import java.util.Locale;

/** The measured candidates, by the names used in results and documents. */
public enum Candidate {
  /** C1 with one immutable file per region block. */
  REGION_FILES("c1-region-files"),
  /** C1 with region blocks appended to segment files. */
  REGION_SEGMENTS("c1-region-segments"),
  /** C2: SQLite. */
  SQLITE("c2-sqlite"),
  /** C3: Roaring bitmaps over C1 segment blocks. */
  ROARING("c3-roaring"),
  /** C4: H2 MVStore. */
  MVSTORE("c4-mvstore");

  private final String id;

  Candidate(String id) {
    this.id = id;
  }

  /** The stable name used in result files. */
  public String id() {
    return id;
  }

  /** Opens (or creates) this candidate's index in {@code directory}. */
  public SpikeIndex open(Path directory) throws ChunkIndexException {
    return switch (this) {
      case REGION_FILES ->
          RegionStoreIndex.open(directory, RegionStoreIndex.Layout.FILE_PER_REGION);
      case REGION_SEGMENTS -> RegionStoreIndex.open(directory, RegionStoreIndex.Layout.SEGMENTS);
      case SQLITE -> SqliteIndex.open(directory);
      case ROARING -> RoaringIndex.open(directory);
      case MVSTORE -> MvStoreIndex.open(directory);
    };
  }

  /** The candidate with result-file name {@code id}. */
  public static Candidate byId(String id) {
    for (Candidate c : values()) {
      if (c.id.equals(id.toLowerCase(Locale.ROOT))) {
        return c;
      }
    }
    throw new IllegalArgumentException("Unknown candidate " + id);
  }
}
