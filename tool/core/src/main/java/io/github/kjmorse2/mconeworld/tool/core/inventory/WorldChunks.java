// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.inventory;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkOrder;
import io.github.kjmorse2.mconeworld.tool.core.ProgressListener;
import io.github.kjmorse2.mconeworld.tool.world.WorldInspection;
import io.github.kjmorse2.mconeworld.tool.world.region.ChunkEntry;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Every region chunk of a world as sorted {@link ChunkOrder} keys per dimension, plus the inventory
 * of the scan that collected them. Eight bytes per chunk: ten million chunks take 80 MB.
 *
 * @param keys sorted, distinct chunk keys per dimension (arrays are owned; do not modify)
 * @param inventory the scan
 */
public record WorldChunks(Map<DimensionKey, long[]> keys, WorldInventory inventory) {
  public WorldChunks {
    keys = Collections.unmodifiableMap(new TreeMap<>(keys));
  }

  /** Scans {@code world} and collects its region chunks. */
  public static WorldChunks collect(WorldInspection world, ProgressListener progress)
      throws IOException {
    Map<DimensionKey, long[]> arrays = new TreeMap<>();
    Map<DimensionKey, Integer> sizes = new TreeMap<>();
    WorldInventory inventory =
        InventoryScanner.scan(
            world,
            (dimension, chunks) -> {
              long[] array = arrays.computeIfAbsent(dimension, d -> new long[1024]);
              int size = sizes.getOrDefault(dimension, 0);
              if (size + chunks.size() > array.length) {
                array = Arrays.copyOf(array, Math.max(array.length * 2, size + chunks.size()));
                arrays.put(dimension, array);
              }
              for (ChunkEntry e : chunks) {
                array[size++] = ChunkOrder.key(e.chunk());
              }
              sizes.put(dimension, size);
            },
            progress);
    Map<DimensionKey, long[]> sorted = new TreeMap<>();
    for (Map.Entry<DimensionKey, long[]> e : arrays.entrySet()) {
      long[] keys = Arrays.copyOf(e.getValue(), sizes.getOrDefault(e.getKey(), 0));
      Arrays.sort(keys);
      sorted.put(e.getKey(), keys);
    }
    return new WorldChunks(sorted, inventory);
  }
}
