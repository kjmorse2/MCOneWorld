// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index.bench;

import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexException;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexOptions;
import java.nio.file.Path;
import java.util.Locale;

/**
 * The measured configurations of the production index. They differ only in how many region rows are
 * compressed together, the knob decision D3 of ADR 0007 asked Prompt 09 to settle by measurement.
 * Older result files name the Prompt 08 spike candidates; {@code Summarize} reads those too.
 */
public enum Candidate {
  /** One row (32 chunks) per compression unit. */
  PROD_ROWS1("prod-rows1", 1),
  /** Four rows (128 chunks) per unit: the default. */
  PROD_ROWS4("prod-rows4", 4),
  /** The whole region in one unit, as in the Prompt 08 spike. */
  PROD_ROWS32("prod-rows32", 32);

  private final String id;
  private final int rowsPerUnit;

  Candidate(String id, int rowsPerUnit) {
    this.id = id;
    this.rowsPerUnit = rowsPerUnit;
  }

  /** The stable name used in result files. */
  public String id() {
    return id;
  }

  /** Opens (or creates) this configuration's index in the sidecar at {@code directory}. */
  public BenchIndex open(Path directory) throws ChunkIndexException {
    return ProductionIndex.open(directory, ChunkIndexOptions.DEFAULT.withRowsPerUnit(rowsPerUnit));
  }

  /** The configuration with result-file name {@code id}. */
  public static Candidate byId(String id) {
    for (Candidate c : values()) {
      if (c.id.equals(id.toLowerCase(Locale.ROOT))) {
        return c;
      }
    }
    throw new IllegalArgumentException("Unknown candidate " + id);
  }
}
