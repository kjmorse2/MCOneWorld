// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import io.github.kjmorse2.mconeworld.fixtures.Mutations;
import io.github.kjmorse2.mconeworld.fixtures.index.ChunkIndexFixtures;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.statistics.Statistics;

/**
 * Corrupted chunk-index exports (root AGENTS.md §20). The export is diagnostic, but it is read back
 * strictly: every mutant of the golden export is read to the end or fails with an {@link
 * IOException} ({@link ChunkIndexException}), never anything else.
 */
class ChunkIndexExportFuzzProperties {
  private static final byte[] GOLDEN = golden();
  private static final int GOLDEN_RECORDS = 27;

  @Property(tries = 3000)
  void mutantsReadOrFailWithAnIoException(@ForAll long seed) {
    byte[] mutant = Mutations.mutate(GOLDEN, seed);
    try {
      ChunkIndexExport.Reader reader = ChunkIndexExport.read(new ByteArrayInputStream(mutant));
      int records = 0;
      while (reader.next() != null) {
        records++;
      }
      // A mutant that changes the record count can only be a whole-line edit (a truncation at a
      // line boundary); the export has no trailer, a documented limitation of a diagnostic format.
      Statistics.label("export")
          .collect(records == GOLDEN_RECORDS ? "accepted" : "accepted, " + records + " records");
    } catch (ChunkIndexException e) {
      Statistics.label("export").collect(e.code().value());
    } catch (IOException e) {
      Statistics.label("export").collect("io");
    }
  }

  private static byte[] golden() {
    try (InputStream in =
        ChunkIndexExportFuzzProperties.class.getResourceAsStream(
            ChunkIndexFixtures.GOLDEN_EXPORT)) {
      if (in == null) {
        throw new IllegalStateException("missing golden export");
      }
      return in.readAllBytes();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
