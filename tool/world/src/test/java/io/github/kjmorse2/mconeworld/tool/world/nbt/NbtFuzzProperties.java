// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.world.nbt;

import io.github.kjmorse2.mconeworld.fixtures.Mutations;
import io.github.kjmorse2.mconeworld.tool.world.testing.NbtWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.statistics.Statistics;

/**
 * Corrupted {@code level.dat}-like input (root AGENTS.md §20): every mutant either reads or fails
 * with a coded {@link NbtException}. In-memory input has no other I/O failure, so a plain {@link
 * IOException} would mean an unclassified corruption.
 */
class NbtFuzzProperties {
  private static final Map<String, Object> DATA =
      Map.of(
          "Data",
          Map.of(
              "DataVersion", 4903,
              "LevelName", "Synthetic",
              "spawn", Map.of("pos", new int[] {8, 64, -8}, "dimension", "minecraft:overworld"),
              "ServerBrands", List.of("vanilla", "paper"),
              "Version", Map.of("Id", 4903, "Name", "26.2", "Snapshot", (byte) 0)));
  private static final byte[] PLAIN = NbtWriter.write(DATA);
  private static final byte[] GZIP = NbtWriter.writeGzip(DATA);

  @Property(tries = 2000)
  void plainMutantsReadOrFailWithAnIoException(@ForAll long seed) {
    check(Mutations.mutate(PLAIN, seed));
  }

  @Property(tries = 1000)
  void gzipMutantsReadOrFailWithAnIoException(@ForAll long seed) {
    check(Mutations.mutate(GZIP, seed));
  }

  private static void check(byte[] mutant) {
    try {
      NbtReader.read(new ByteArrayInputStream(mutant), NbtLimits.WORLD_DATA);
      Statistics.label("nbt").collect("read");
    } catch (NbtException e) {
      Statistics.label("nbt").collect(e.code().value());
    } catch (IOException e) {
      throw new AssertionError("unclassified failure: " + e, e);
    }
  }
}
