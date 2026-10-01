// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.fixtures.index;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkRectangle;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProtection;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProvenance;
import io.github.kjmorse2.mconeworld.domain.provenance.Confidence;
import io.github.kjmorse2.mconeworld.domain.provenance.EvidenceSource;
import io.github.kjmorse2.mconeworld.domain.provenance.FirstObservation;
import io.github.kjmorse2.mconeworld.domain.provenance.ProvenanceEvidence;
import io.github.kjmorse2.mconeworld.domain.provenance.SeasonActivity;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkOrder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A deterministic synthetic world: chunk provenance defined by simple shapes (discs, rectangles,
 * and random-walk trails) instead of stored records, so any size can be generated and streamed
 * without materializing it or committing it.
 *
 * <p>Each shape belongs to a <b>layer</b>: either the legacy import or one season. A chunk exists
 * if any shape covers it. Its record is a pure function of the seed, its position, and the layers
 * covering it:
 *
 * <ul>
 *   <li>First observation: the earliest covering layer (legacy before every season).
 *   <li>Activity: one entry for every covering season, except that the first season's own entry
 *       appears for only 60 % of chunks. So chunks covered by several seasons are "revisited".
 *   <li>Protection: {@code PROTECTED} inside a protected shape, and for 0.5 % of other chunks.
 *   <li>Timestamps fall inside the layer's 91-day season. Runtime observations carry microseconds,
 *       like {@code Instant.now()} on Linux. Offline evidence uses whole seconds.
 * </ul>
 *
 * <p>Memory is proportional to the number of touched regions, not chunks: shapes are indexed by the
 * regions their bounds overlap, and {@link #records(DimensionKey)} computes one region at a time.
 */
public final class SyntheticWorld {
  /** The layer of chunks that predate tracking. */
  public static final int LEGACY = -1;

  private static final long EPOCH_SECOND = Instant.parse("2021-01-01T00:00:00Z").getEpochSecond();
  private static final long SEASON_SECONDS = 91L * 24 * 3600;
  private static final int MAX_SEASONS = 62;

  private final String name;
  private final long seed;
  private final List<SeasonId> seasons;
  private final Map<DimensionKey, NavigableMap<Long, List<Shape>>> regions;
  private final List<Shape> shapes;
  private final double[] cumulativeArea;

  private SyntheticWorld(Builder builder) {
    this.name = builder.name;
    this.seed = builder.seed;
    this.seasons = List.copyOf(builder.seasons);
    this.shapes = List.copyOf(builder.shapes);
    TreeMap<DimensionKey, NavigableMap<Long, List<Shape>>> byDimension = new TreeMap<>();
    cumulativeArea = new double[shapes.size()];
    double total = 0;
    for (int i = 0; i < shapes.size(); i++) {
      Shape shape = shapes.get(i);
      total += shape.area();
      cumulativeArea[i] = total;
      NavigableMap<Long, List<Shape>> map =
          byDimension.computeIfAbsent(shape.dimension(), d -> new TreeMap<>());
      for (int rx = shape.minX() >> 5; rx <= shape.maxX() >> 5; rx++) {
        for (int rz = shape.minZ() >> 5; rz <= shape.maxZ() >> 5; rz++) {
          map.computeIfAbsent(ChunkOrder.regionStart(rx, rz), k -> new ArrayList<>()).add(shape);
        }
      }
    }
    this.regions = Collections.unmodifiableMap(byDimension);
  }

  /** Starts a world named {@code name} whose every random choice derives from {@code seed}. */
  public static Builder builder(String name, long seed) {
    return new Builder(name, seed);
  }

  /** The dataset name. */
  public String name() {
    return name;
  }

  /** The seasons, oldest first. Layer {@code i} is {@code seasons().get(i)}. */
  public List<SeasonId> seasons() {
    return seasons;
  }

  /** The dimensions with at least one shape, in {@link DimensionKey} order. */
  public List<DimensionKey> dimensions() {
    return List.copyOf(regions.keySet());
  }

  /** The number of regions with at least one candidate chunk; an upper bound on touched regions. */
  public int candidateRegionCount() {
    return regions.values().stream().mapToInt(Map::size).sum();
  }

  /** The record of {@code chunk}, or {@code null} if no shape covers it. */
  public @Nullable ChunkProvenance recordAt(ChunkPosition chunk) {
    NavigableMap<Long, List<Shape>> map = regions.get(chunk.dimension());
    if (map == null) {
      return null;
    }
    long key = ChunkOrder.key(chunk);
    List<Shape> candidates =
        map.get(ChunkOrder.regionStart(ChunkOrder.regionX(key), ChunkOrder.regionZ(key)));
    return candidates == null ? null : record(chunk, candidates);
  }

  /**
   * Streams every record of {@code dimension} in {@link ChunkOrder}, computing one region at a
   * time.
   */
  public Iterator<ChunkProvenance> records(DimensionKey dimension) {
    NavigableMap<Long, List<Shape>> map = regions.getOrDefault(dimension, new TreeMap<>());
    Iterator<Map.Entry<Long, List<Shape>>> regionIterator = map.entrySet().iterator();
    return new Iterator<>() {
      private List<ChunkProvenance> buffer = List.of();
      private int next;

      @Override
      public boolean hasNext() {
        while (next >= buffer.size() && regionIterator.hasNext()) {
          Map.Entry<Long, List<Shape>> region = regionIterator.next();
          buffer = regionRecords(dimension, region.getKey(), region.getValue());
          next = 0;
        }
        return next < buffer.size();
      }

      @Override
      public ChunkProvenance next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        return buffer.get(next++);
      }
    };
  }

  /** A chunk that has a record, chosen by area-weighted shape sampling. */
  public ChunkPosition sampleExisting(SplittableRandom random) {
    for (int attempt = 0; attempt < 1000; attempt++) {
      double target = random.nextDouble() * cumulativeArea[cumulativeArea.length - 1];
      int index = 0;
      while (cumulativeArea[index] < target) {
        index++;
      }
      Shape shape = shapes.get(index);
      ChunkPosition candidate = shape.samplePoint(random);
      if (recordAt(candidate) != null) {
        return candidate;
      }
    }
    throw new IllegalStateException("No existing chunk found in " + name + " after 1000 attempts");
  }

  /**
   * A chunk within 64 chunks of an existing one, which may or may not have a record: the realistic
   * mix of hits and misses near explored terrain.
   */
  public ChunkPosition sampleNearby(SplittableRandom random) {
    ChunkPosition base = sampleExisting(random);
    return new ChunkPosition(
        base.dimension(),
        clamp((long) base.x() + random.nextInt(-64, 65)),
        clamp((long) base.z() + random.nextInt(-64, 65)));
  }

  /** A square of side {@code 2 * half + 1} chunks centred on an existing chunk. */
  public ChunkRectangle sampleArea(SplittableRandom random, int half) {
    ChunkPosition center = sampleExisting(random);
    return new ChunkRectangle(
        center.dimension(),
        clamp((long) center.x() - half),
        clamp((long) center.z() - half),
        clamp((long) center.x() + half),
        clamp((long) center.z() + half));
  }

  /** The start of season {@code layer}'s 91-day window. */
  public static Instant seasonStart(int layer) {
    return Instant.ofEpochSecond(EPOCH_SECOND + layer * SEASON_SECONDS);
  }

  private List<ChunkProvenance> regionRecords(
      DimensionKey dimension, long regionStart, List<Shape> candidates) {
    List<ChunkProvenance> out = new ArrayList<>();
    int baseX = ChunkOrder.regionX(regionStart) << 5;
    int baseZ = ChunkOrder.regionZ(regionStart) << 5;
    for (int index = 0; index < 1024; index++) {
      ChunkPosition chunk =
          new ChunkPosition(dimension, baseX | (index & 31), baseZ | (index >>> 5));
      ChunkProvenance record = record(chunk, candidates);
      if (record != null) {
        out.add(record);
      }
    }
    return out;
  }

  private @Nullable ChunkProvenance record(ChunkPosition chunk, List<Shape> candidates) {
    long layers = 0; // bit 0: legacy; bit i + 1: season i
    boolean protectedShape = false;
    for (Shape shape : candidates) {
      if (shape.covers(chunk.x(), chunk.z(), seed)) {
        layers |= 1L << (shape.layer() + 1);
        protectedShape |= shape.protectedArea();
      }
    }
    if (layers == 0) {
      return null;
    }
    long h = mix(seed, chunk.x(), chunk.z());
    int firstLayer = Long.numberOfTrailingZeros(layers) - 1;
    FirstObservation first = firstObservation(firstLayer, h);
    List<SeasonActivity> activity = new ArrayList<>();
    for (int layer = Math.max(0, firstLayer); layer < seasons.size(); layer++) {
      if ((layers & (1L << (layer + 1))) == 0) {
        continue;
      }
      long ha = mix(h, layer, 0x5EA5);
      if (layer == firstLayer && Math.floorMod(ha, 10) >= 6) {
        continue;
      }
      activity.add(
          new SeasonActivity(
              seasons.get(layer),
              runtimeInstant(layer, ha),
              Math.floorMod(ha >>> 8, 20) == 0
                  ? new ProvenanceEvidence(EvidenceSource.ADMINISTRATOR, Confidence.HIGH)
                  : new ProvenanceEvidence(EvidenceSource.RUNTIME_OBSERVATION, Confidence.HIGH)));
    }
    boolean isProtected = protectedShape || Math.floorMod(h >>> 20, 1000) < 5;
    return new ChunkProvenance(
        chunk, first, activity, isProtected ? ChunkProtection.PROTECTED : ChunkProtection.NONE);
  }

  private FirstObservation firstObservation(int layer, long h) {
    if (layer == LEGACY) {
      Instant at = Instant.ofEpochSecond(EPOCH_SECOND - 86_400 + Math.floorMod(h, 3600));
      return new FirstObservation.Legacy(
          at,
          Math.floorMod(h >>> 12, 2) == 0
              ? new ProvenanceEvidence(EvidenceSource.WORLD_IMPORT, Confidence.HIGH)
              : new ProvenanceEvidence(EvidenceSource.OFFLINE_SCAN, Confidence.MEDIUM));
    }
    int kind = Math.floorMod(h >>> 12, 100);
    if (kind < 90) {
      return new FirstObservation.InSeason(
          seasons.get(layer),
          runtimeInstant(layer, h),
          new ProvenanceEvidence(EvidenceSource.RUNTIME_OBSERVATION, Confidence.HIGH));
    }
    Instant offline =
        Instant.ofEpochSecond(
            seasonStart(layer).getEpochSecond() + Math.floorMod(h, SEASON_SECONDS));
    return new FirstObservation.InSeason(
        seasons.get(layer),
        offline,
        kind < 97
            ? new ProvenanceEvidence(EvidenceSource.REGION_FILE_TIMESTAMP, Confidence.MEDIUM)
            : new ProvenanceEvidence(EvidenceSource.INHABITED_TIME, Confidence.LOW));
  }

  private static Instant runtimeInstant(int layer, long h) {
    return Instant.ofEpochSecond(
        seasonStart(layer).getEpochSecond() + Math.floorMod(h, SEASON_SECONDS),
        Math.floorMod(h >>> 24, 1_000_000) * 1000L);
  }

  /** SplitMix64 over three inputs: a cheap, well-distributed, deterministic hash. */
  static long mix(long seed, long a, long b) {
    long z = seed + 0x9E3779B97F4A7C15L * (a * 31 + b + 1);
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }

  static int clamp(long value) {
    return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
  }

  /** Adds shapes and seasons; every random choice derives from the seed. */
  public static final class Builder {
    private final String name;
    private final long seed;
    private final SplittableRandom random;
    private final List<SeasonId> seasons = new ArrayList<>();
    private final List<Shape> shapes = new ArrayList<>();

    private Builder(String name, long seed) {
      this.name = Objects.requireNonNull(name, "name");
      this.seed = seed;
      this.random = new SplittableRandom(seed);
    }

    /** Adds the next season and returns its layer. */
    public int season() {
      if (seasons.size() == MAX_SEASONS) {
        throw new IllegalStateException("At most " + MAX_SEASONS + " seasons");
      }
      int layer = seasons.size();
      seasons.add(SeasonId.of(new UUID(mix(seed, 1, layer) | 1, mix(seed, 2, layer))));
      return layer;
    }

    /** A disc of radius {@code radius} chunks with a slightly ragged edge. */
    public Builder disc(
        DimensionKey dimension,
        int layer,
        int centerX,
        int centerZ,
        int radius,
        boolean isProtected) {
      shapes.add(new Disc(dimension, checkLayer(layer), centerX, centerZ, radius, isProtected));
      return this;
    }

    /** A filled rectangle, inclusive. */
    public Builder rect(DimensionKey dimension, int layer, int minX, int minZ, int maxX, int maxZ) {
      shapes.add(new Rect(dimension, checkLayer(layer), minX, minZ, maxX, maxZ));
      return this;
    }

    /**
     * A random-walk travel path of about {@code length} chunks, {@code 2 * halfWidth + 1} chunks
     * wide (a player's loaded area while travelling), starting at ({@code startX}, {@code startZ}).
     */
    public Builder trail(
        DimensionKey dimension, int layer, int startX, int startZ, int length, int halfWidth) {
      checkLayer(layer);
      double heading = random.nextDouble() * 2 * Math.PI;
      double x = startX;
      double z = startZ;
      int travelled = 0;
      while (travelled < length) {
        int step = Math.min(length - travelled, 64 + random.nextInt(193));
        heading += (random.nextDouble() - 0.5) * 1.2;
        double nx = x + Math.cos(heading) * step;
        double nz = z + Math.sin(heading) * step;
        shapes.add(new Segment(dimension, layer, x, z, nx, nz, halfWidth));
        x = nx;
        z = nz;
        travelled += step;
      }
      return this;
    }

    /** A random value from this builder's seeded generator, for placing shapes. */
    public int nextInt(int origin, int bound) {
      return random.nextInt(origin, bound);
    }

    /** Builds the world. Shapes are indexed by region; no record is computed yet. */
    public SyntheticWorld build() {
      if (shapes.isEmpty()) {
        throw new IllegalStateException("A synthetic world needs at least one shape");
      }
      return new SyntheticWorld(this);
    }

    private int checkLayer(int layer) {
      if (layer < LEGACY || layer >= seasons.size()) {
        throw new IllegalArgumentException("Unknown layer " + layer);
      }
      return layer;
    }
  }

  /** A region of chunks belonging to one layer. Bounds are inclusive and clamped to {@code int}. */
  private interface Shape {
    DimensionKey dimension();

    int layer();

    boolean protectedArea();

    int minX();

    int minZ();

    int maxX();

    int maxZ();

    double area();

    boolean covers(int x, int z, long seed);

    ChunkPosition samplePoint(SplittableRandom random);
  }

  private record Disc(
      DimensionKey dimension, int layer, int cx, int cz, int radius, boolean protectedArea)
      implements Shape {
    @Override
    public int minX() {
      return clamp((long) cx - radius - 1);
    }

    @Override
    public int minZ() {
      return clamp((long) cz - radius - 1);
    }

    @Override
    public int maxX() {
      return clamp((long) cx + radius + 1);
    }

    @Override
    public int maxZ() {
      return clamp((long) cz + radius + 1);
    }

    @Override
    public double area() {
      return Math.PI * radius * radius;
    }

    @Override
    public boolean covers(int x, int z, long seed) {
      double dx = (double) x - cx;
      double dz = (double) z - cz;
      double jitter = 1 + ((mix(seed, x, z ^ 0x0D15C) & 0xFFFF) / 65535.0 - 0.5) * 0.2;
      return dx * dx + dz * dz <= (double) radius * radius * jitter;
    }

    @Override
    public ChunkPosition samplePoint(SplittableRandom random) {
      double r = radius * Math.sqrt(random.nextDouble());
      double angle = random.nextDouble() * 2 * Math.PI;
      return new ChunkPosition(
          dimension,
          clamp(Math.round(cx + r * Math.cos(angle))),
          clamp(Math.round(cz + r * Math.sin(angle))));
    }
  }

  private record Rect(DimensionKey dimension, int layer, int minX, int minZ, int maxX, int maxZ)
      implements Shape {
    @Override
    public boolean protectedArea() {
      return false;
    }

    @Override
    public double area() {
      return ((double) maxX - minX + 1) * ((double) maxZ - minZ + 1);
    }

    @Override
    public boolean covers(int x, int z, long seed) {
      return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    @Override
    public ChunkPosition samplePoint(SplittableRandom random) {
      return new ChunkPosition(
          dimension,
          (int) random.nextLong(minX, (long) maxX + 1),
          (int) random.nextLong(minZ, (long) maxZ + 1));
    }
  }

  private record Segment(
      DimensionKey dimension, int layer, double x0, double z0, double x1, double z1, int halfWidth)
      implements Shape {
    @Override
    public boolean protectedArea() {
      return false;
    }

    @Override
    public int minX() {
      return clamp((long) Math.floor(Math.min(x0, x1)) - halfWidth - 1);
    }

    @Override
    public int minZ() {
      return clamp((long) Math.floor(Math.min(z0, z1)) - halfWidth - 1);
    }

    @Override
    public int maxX() {
      return clamp((long) Math.ceil(Math.max(x0, x1)) + halfWidth + 1);
    }

    @Override
    public int maxZ() {
      return clamp((long) Math.ceil(Math.max(z0, z1)) + halfWidth + 1);
    }

    @Override
    public double area() {
      return Math.hypot(x1 - x0, z1 - z0) * (2 * halfWidth + 1);
    }

    @Override
    public boolean covers(int x, int z, long seed) {
      double dx = x1 - x0;
      double dz = z1 - z0;
      double lengthSquared = dx * dx + dz * dz;
      double t = lengthSquared == 0 ? 0 : ((x - x0) * dx + (z - z0) * dz) / lengthSquared;
      t = Math.max(0, Math.min(1, t));
      double px = x0 + t * dx - x;
      double pz = z0 + t * dz - z;
      return px * px + pz * pz <= (double) halfWidth * halfWidth;
    }

    @Override
    public ChunkPosition samplePoint(SplittableRandom random) {
      double t = random.nextDouble();
      return new ChunkPosition(
          dimension,
          clamp(Math.round(x0 + t * (x1 - x0)) + random.nextInt(-halfWidth, halfWidth + 1)),
          clamp(Math.round(z0 + t * (z1 - z0)) + random.nextInt(-halfWidth, halfWidth + 1)));
    }
  }
}
