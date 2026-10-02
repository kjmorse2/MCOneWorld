// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.world.testing;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.domain.coordinate.CoordinateLimits;
import io.github.kjmorse2.mconeworld.domain.coordinate.RegionPosition;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.DeflaterOutputStream;

/**
 * Builds a Minecraft world directory in code for tests: {@code level.dat}, the seed file, dimension
 * directories in the 26.1+ or the legacy layout, and region files whose headers list exactly the
 * chunks asked for. No Mojang assets are involved. Every chunk payload is one sector holding a
 * zlib-compressed empty compound, enough for header-level readers.
 *
 * <p>The shapes follow {@code docs/compatibility/world-format-26.2.md}.
 */
public final class SyntheticWorld {
  /** Kinds of region files, by directory name ({@code region}, {@code entities}, {@code poi}). */
  public static final List<String> KINDS = List.of("region", "entities", "poi");

  private final Path dir;
  private final boolean modern;

  private SyntheticWorld(Path dir, boolean modern) {
    this.dir = dir;
    this.modern = modern;
    try {
      Files.createDirectories(dir);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A 26.1+ world: every dimension under {@code dimensions/<namespace>/<path>/}. */
  public static SyntheticWorld modern(Path dir) {
    return new SyntheticWorld(dir, true);
  }

  /** A pre-26.1 world: the Overworld at the root, {@code DIM-1}, {@code DIM1}. */
  public static SyntheticWorld legacy(Path dir) {
    return new SyntheticWorld(dir, false);
  }

  public Path dir() {
    return dir;
  }

  /** A 26.x {@code level.dat} of {@code dataVersion}, spawn (8, 64, -8) in the Overworld. */
  public SyntheticWorld levelDat(int dataVersion) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("DataVersion", dataVersion);
    data.put("LevelName", "Synthetic");
    data.put(
        "Version",
        Map.of(
            "Id", dataVersion, "Name", dataVersion == 4903 ? "26.2" : "other", "Series", "main"));
    if (modern) {
      data.put("spawn", Map.of("pos", new int[] {8, 64, -8}, "dimension", "minecraft:overworld"));
    } else {
      data.put("SpawnX", 8);
      data.put("SpawnY", 64);
      data.put("SpawnZ", -8);
    }
    data.put("ServerBrands", List.of("vanilla"));
    return levelDat(data);
  }

  /** A {@code level.dat} whose {@code Data} compound is exactly {@code data}. */
  public SyntheticWorld levelDat(Map<String, ?> data) {
    return file("level.dat", NbtWriter.writeGzip(Map.of("Data", data)));
  }

  /** The seed: in {@code world_gen_settings.dat} for a modern world. */
  public SyntheticWorld seed(long seed) {
    return file(
        "data/minecraft/world_gen_settings.dat",
        NbtWriter.writeGzip(Map.of("DataVersion", 4903, "data", Map.of("seed", seed))));
  }

  /**
   * Creates {@code dimension}'s directory with empty region, entities, poi, and data directories.
   */
  public SyntheticWorld dimension(DimensionKey dimension) {
    try {
      for (String kind : KINDS) {
        Files.createDirectories(dimensionDir(dimension).resolve(kind));
      }
      Files.createDirectories(dimensionDir(dimension).resolve("data/minecraft"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return this;
  }

  /** The directory of {@code dimension} in this world's layout. */
  public Path dimensionDir(DimensionKey dimension) {
    boolean vanilla =
        dimension.equals(DimensionKey.OVERWORLD)
            || dimension.equals(DimensionKey.THE_NETHER)
            || dimension.equals(DimensionKey.THE_END);
    if (modern || !vanilla) {
      return dir.resolve("dimensions").resolve(dimension.namespace()).resolve(dimension.path());
    }
    if (dimension.equals(DimensionKey.OVERWORLD)) {
      return dir;
    }
    return dir.resolve(dimension.equals(DimensionKey.THE_NETHER) ? "DIM-1" : "DIM1");
  }

  /**
   * Writes region files of {@code kind} listing exactly {@code chunks}, each saved at {@code
   * timestamp} (epoch seconds), creating the dimension first.
   */
  public SyntheticWorld chunks(
      DimensionKey dimension, String kind, Collection<ChunkPosition> chunks, long timestamp) {
    dimension(dimension);
    Map<RegionPosition, List<ChunkPosition>> byRegion = new TreeMap<>();
    for (ChunkPosition chunk : chunks) {
      if (!chunk.dimension().equals(dimension)) {
        throw new IllegalArgumentException(chunk + " is not in " + dimension);
      }
      byRegion.computeIfAbsent(chunk.region(), r -> new ArrayList<>()).add(chunk);
    }
    for (Map.Entry<RegionPosition, List<ChunkPosition>> e : byRegion.entrySet()) {
      RegionPosition r = e.getKey();
      regionFile(
          dimension,
          kind,
          "r." + r.x() + "." + r.z() + ".mca",
          regionBytes(e.getValue(), timestamp));
    }
    return this;
  }

  /** Writes {@code content} as a file of the dimension's {@code kind} directory. */
  public SyntheticWorld regionFile(
      DimensionKey dimension, String kind, String name, byte[] content) {
    dimension(dimension);
    return file(
        dir.relativize(dimensionDir(dimension).resolve(kind).resolve(name)).toString(), content);
  }

  /** A {@code session.lock} like Minecraft's: the snowman character. */
  public SyntheticWorld sessionLock() {
    return file("session.lock", "☃".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  /** Writes {@code content} at {@code relative}, creating directories. */
  public SyntheticWorld file(String relative, byte[] content) {
    try {
      Path target = dir.resolve(relative);
      Files.createDirectories(target.getParent());
      Files.write(target, content);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return this;
  }

  /** A region file's bytes: a header listing {@code chunks} and one data sector per chunk. */
  public static byte[] regionBytes(Collection<ChunkPosition> chunks, long timestamp) {
    byte[] payload = chunkPayload();
    ByteBuffer file = ByteBuffer.allocate((2 + chunks.size()) * 4096);
    int sector = 2;
    for (ChunkPosition chunk : chunks) {
      int index = chunk.regionLocalX() + chunk.regionLocalZ() * CoordinateLimits.REGION_SIZE_CHUNKS;
      file.putInt(index * 4, (sector << 8) | 1);
      file.putInt(4096 + index * 4, (int) timestamp);
      file.put(sector * 4096, payload);
      sector++;
    }
    return file.array();
  }

  /** One chunk's stored form: length, compression type 2 (zlib), and an empty compound. */
  private static byte[] chunkPayload() {
    ByteArrayOutputStream compressed = new ByteArrayOutputStream();
    try (DeflaterOutputStream zlib = new DeflaterOutputStream(compressed)) {
      zlib.write(NbtWriter.write(Map.of()));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    byte[] data = compressed.toByteArray();
    ByteBuffer out = ByteBuffer.allocate(5 + data.length);
    out.putInt(1 + data.length).put((byte) 2).put(data);
    return out.array();
  }
}
