// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index.bench;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkRectangle;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProvenance;
import io.github.kjmorse2.mconeworld.fixtures.index.ChunkDatasets;
import io.github.kjmorse2.mconeworld.fixtures.index.IndexWorkloads;
import io.github.kjmorse2.mconeworld.fixtures.index.SyntheticWorld;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkCursor;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexBatch;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexException;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexReader;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkMutation;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkQuery;
import io.github.kjmorse2.mconeworld.sidecar.index.SeasonFilter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * One benchmark case in its own JVM: {@code build} (bulk import, then disk size) or {@code query}
 * (cold open, lookups, scans, envelopes, durable update batches, verification). Result lines go to
 * standard output prefixed with {@code RESULT }.
 *
 * <p>Inputs (positions, rectangles, update batches) are generated before timing starts, so the
 * generator's cost is never measured.
 *
 * <p>Usage: {@code BenchmarkCase build|query <candidate> <dataset> <scale> <directory> <run>}
 */
public final class BenchmarkCase {
  static final int BUILD_BATCH = 100_000;
  private static final SeasonId UPDATE_SEASON =
      SeasonId.of(UUID.fromString("00000000-0000-4000-8000-0000000000ee"));

  private final Emitter emit;
  private final Candidate candidate;
  private final SyntheticWorld world;
  private final Path directory;
  private final HeapSampler heap = new HeapSampler();

  private BenchmarkCase(Emitter emit, Candidate candidate, SyntheticWorld world, Path directory) {
    this.emit = emit;
    this.candidate = candidate;
    this.world = world;
    this.directory = directory;
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 6) {
      throw new IllegalArgumentException(
          "usage: build|query <candidate> <dataset> <scale> <directory> <run>");
    }
    String phase = args[0];
    Candidate candidate = Candidate.byId(args[1]);
    double scale = Double.parseDouble(args[3]);
    Emitter emit = new Emitter(args[5], candidate.id(), args[2], scale, phase);
    BenchmarkCase c =
        new BenchmarkCase(emit, candidate, ChunkDatasets.named(args[2], scale), Path.of(args[4]));
    c.heap.start();
    try {
      if (phase.equals("build")) {
        c.build();
      } else {
        c.query();
      }
    } finally {
      c.heap.stop();
      emit.metric("heap.peakUsed", "bytes", c.heap.peak());
      emit.metric("rss.peak", "bytes", HeapSampler.peakRss());
      emit.metric("heap.max", "bytes", Runtime.getRuntime().maxMemory());
    }
  }

  private void build() throws Exception {
    Files.createDirectories(directory);
    long start = System.nanoTime();
    long records;
    try (BenchIndex index = candidate.open(directory)) {
      records = IndexWorkloads.bulkLoad(world, BUILD_BATCH, index);
    }
    long nanos = System.nanoTime() - start;
    emit.metric("build.records", "count", records);
    emit.metric("build.seconds", "s", nanos / 1e9);
    emit.metric("build.rate", "records/s", records / (nanos / 1e9));
    emit.metric("world.regions", "count", world.candidateRegionCount());
    long[] disk = disk(directory);
    emit.metric("disk.bytes", "bytes", disk[0]);
    emit.metric("disk.files", "count", disk[1]);
    emit.metric("disk.bytesPerRecord", "bytes", (double) disk[0] / records);
  }

  private void query() throws Exception {
    SplittableRandom random = new SplittableRandom(0xBE7C4);
    List<ChunkPosition> uniform = new ArrayList<>();
    for (int i = 0; i < 120_000; i++) {
      uniform.add(world.sampleNearby(random));
    }
    List<ChunkPosition> local = localWalk(random, 120_000);
    List<ChunkRectangle> small = new ArrayList<>();
    for (int i = 0; i < 2_200; i++) {
      small.add(world.sampleArea(random, 8));
    }
    List<ChunkRectangle> large = new ArrayList<>();
    for (int i = 0; i < 24; i++) {
      large.add(world.sampleArea(random, 256));
    }
    List<List<ChunkMutation>> updates = new ArrayList<>();
    Set<ChunkPosition> removed = new HashSet<>();
    Instant at = Instant.parse("2031-01-01T00:00:00Z");
    for (int i = 0; i < 25; i++) {
      updates.add(IndexWorkloads.updateBatch(world, random, 1000, UPDATE_SEASON, at, removed));
    }

    long baseline = liveHeapAfterGc();
    long start = System.nanoTime();
    BenchIndex index = candidate.open(directory);
    emit.metric("open.cold", "ms", (System.nanoTime() - start) / 1e6);
    index.close();
    start = System.nanoTime();
    index = candidate.open(directory);
    emit.metric("open.warm", "ms", (System.nanoTime() - start) / 1e6);
    try {
      try (ChunkIndexReader reader = index.reader()) {
        lookups("lookup.uniform", reader, uniform);
        lookups("lookup.local", reader, local);
        rectangles("scan.rect16", reader, small, 200, true);
        rectangles("scan.rect512", reader, large, 4, false);
        seasons(reader);
        fullScan(reader);
      }
      updates(index, updates);
      {
        start = System.nanoTime();
        index.checkpoint();
        emit.metric("checkpoint.seconds", "s", (System.nanoTime() - start) / 1e9);
      }
      // Memory the open index keeps (caches, catalogs, bitmaps) after every workload above.
      emit.metric("heap.indexRetained", "bytes", Math.max(0, liveHeapAfterGc() - baseline));
      start = System.nanoTime();
      index.verify();
      emit.metric("verify.seconds", "s", (System.nanoTime() - start) / 1e9);
    } finally {
      index.close();
    }
    long[] disk = disk(directory);
    emit.metric("disk.afterUpdates.bytes", "bytes", disk[0]);
    emit.metric("disk.afterUpdates.files", "count", disk[1]);
  }

  /** A walk that stays near the previous position, jumping to a new place every 1 000 steps. */
  private List<ChunkPosition> localWalk(SplittableRandom random, int count) {
    List<ChunkPosition> out = new ArrayList<>(count);
    ChunkPosition here = world.sampleExisting(random);
    for (int i = 0; i < count; i++) {
      if (i % 1000 == 0) {
        here = world.sampleExisting(random);
      }
      here =
          new ChunkPosition(
              here.dimension(),
              clamp((long) here.x() + random.nextInt(-2, 3)),
              clamp((long) here.z() + random.nextInt(-2, 3)));
      out.add(here);
    }
    return out;
  }

  private void lookups(String metric, ChunkIndexReader reader, List<ChunkPosition> positions)
      throws ChunkIndexException {
    int warmup = 20_000;
    Stats stats = new Stats();
    long hits = 0;
    for (int i = 0; i < positions.size(); i++) {
      long t = System.nanoTime();
      boolean hit = reader.find(positions.get(i)).isPresent();
      long elapsed = System.nanoTime() - t;
      if (i >= warmup) {
        stats.add(elapsed);
        hits += hit ? 1 : 0;
      }
    }
    emit.latency(metric, stats, "hitRate", (double) hits / stats.count());
  }

  private void rectangles(
      String metric,
      ChunkIndexReader reader,
      List<ChunkRectangle> areas,
      int warmup,
      boolean latency)
      throws ChunkIndexException {
    Stats stats = new Stats();
    long records = 0;
    for (int i = 0; i < areas.size(); i++) {
      ChunkRectangle area = areas.get(i);
      long t = System.nanoTime();
      long n = drain(reader, ChunkQuery.all(area.dimension()).within(area));
      long elapsed = System.nanoTime() - t;
      if (i >= warmup) {
        stats.add(elapsed);
        records += n;
      }
    }
    if (latency) {
      emit.latency(metric, stats, "records", (double) records);
    } else {
      emit.rate(metric, records, stats.total());
    }
  }

  private void seasons(ChunkIndexReader reader) throws ChunkIndexException {
    List<SeasonId> seasons = world.seasons().subList(0, Math.min(6, world.seasons().size()));
    long records = 0;
    long nanos = 0;
    Stats envelopes = new Stats();
    Stats counts = new Stats();
    for (DimensionKey dimension : world.dimensions()) {
      for (SeasonId season : seasons) {
        ChunkQuery query = ChunkQuery.all(dimension).withSeasons(SeasonFilter.touchedBy(season));
        long t = System.nanoTime();
        records += drain(reader, query);
        nanos += System.nanoTime() - t;
        ChunkQuery first =
            ChunkQuery.all(dimension).withSeasons(SeasonFilter.firstObservedIn(season));
        t = System.nanoTime();
        reader.envelope(first);
        envelopes.add(System.nanoTime() - t);
        t = System.nanoTime();
        reader.count(first);
        counts.add(System.nanoTime() - t);
      }
    }
    emit.rate("scan.season", records, nanos);
    emit.latency("envelope.season", envelopes, "queries", envelopes.count());
    emit.latency("count.season", counts, "queries", counts.count());
  }

  private void fullScan(ChunkIndexReader reader) throws ChunkIndexException {
    long records = 0;
    long t = System.nanoTime();
    for (DimensionKey dimension : reader.dimensions()) {
      records += drain(reader, ChunkQuery.all(dimension));
    }
    emit.rate("scan.full", records, System.nanoTime() - t);
  }

  private void updates(BenchIndex index, List<List<ChunkMutation>> batches)
      throws ChunkIndexException {
    Stats stats = new Stats();
    for (int i = 0; i < batches.size(); i++) {
      long t = System.nanoTime();
      try (ChunkIndexBatch batch = index.begin()) {
        for (ChunkMutation mutation : batches.get(i)) {
          batch.apply(mutation);
        }
        batch.commit();
      }
      if (i >= 5) {
        stats.add(System.nanoTime() - t);
      }
    }
    emit.latency("update.batch1000", stats, "batches", stats.count());
  }

  @SuppressWarnings("SystemGc") // a deliberate full collection, to measure live data only
  private static long liveHeapAfterGc() {
    for (int i = 0; i < 3; i++) {
      System.gc();
    }
    return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
  }

  private static long drain(ChunkIndexReader reader, ChunkQuery query) throws ChunkIndexException {
    long n = 0;
    try (ChunkCursor cursor = reader.scan(query)) {
      for (ChunkProvenance r = cursor.next(); r != null; r = cursor.next()) {
        n++;
      }
    }
    return n;
  }

  /** {total bytes, file count} of every regular file under {@code root}. */
  static long[] disk(Path root) throws IOException {
    long bytes = 0;
    long files = 0;
    try (Stream<Path> walk = Files.walk(root)) {
      for (Path p : (Iterable<Path>) walk::iterator) {
        if (Files.isRegularFile(p)) {
          bytes += Files.size(p);
          files++;
        }
      }
    }
    return new long[] {bytes, files};
  }

  private static int clamp(long v) {
    return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, v));
  }

  /** Samples used heap every 5 ms; also reads the process's peak resident set. */
  static final class HeapSampler {
    private volatile boolean running;
    private volatile long peak;
    private final Thread thread =
        new Thread(
            () -> {
              while (running) {
                peak =
                    Math.max(
                        peak, ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
                try {
                  Thread.sleep(5);
                } catch (InterruptedException e) {
                  return;
                }
              }
            },
            "heap-sampler");

    void start() {
      running = true;
      thread.setDaemon(true);
      thread.start();
    }

    void stop() {
      running = false;
      thread.interrupt();
    }

    long peak() {
      return peak;
    }

    static long peakRss() {
      try {
        for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
          if (line.startsWith("VmHWM:")) {
            return Long.parseLong(line.replaceAll("[^0-9]", "")) * 1024;
          }
        }
      } catch (IOException | RuntimeException ignored) {
        // Not Linux: reported as -1.
      }
      return -1;
    }
  }

  /** Prints result lines with the case's identifying fields. */
  static final class Emitter {
    private final String run;
    private final String candidate;
    private final String dataset;
    private final double scale;
    private final String phase;

    Emitter(String run, String candidate, String dataset, double scale, String phase) {
      this.run = run;
      this.candidate = candidate;
      this.dataset = dataset;
      this.scale = scale;
      this.phase = phase;
    }

    private JsonLine line(String metric, String unit) {
      return new JsonLine()
          .put("format", "mconeworld-bench-result")
          .put("version", 1)
          .put("run", run)
          .put("candidate", candidate)
          .put("dataset", dataset)
          .put("scale", scale)
          .put("phase", phase)
          .put("metric", metric)
          .put("unit", unit);
    }

    void metric(String metric, String unit, double value) {
      print(line(metric, unit).put("value", value));
    }

    void metric(String metric, String unit, long value) {
      print(line(metric, unit).put("value", value));
    }

    void latency(String metric, Stats stats, String extraName, double extra) {
      print(
          line(metric, "ns")
              .put("n", stats.count())
              .put("p50", stats.percentile(50))
              .put("p95", stats.percentile(95))
              .put("p99", stats.percentile(99))
              .put("max", stats.percentile(100))
              .put("mean", stats.mean())
              .put(extraName, extra));
    }

    void rate(String metric, long records, long nanos) {
      print(
          line(metric, "records/s")
              .put("value", nanos == 0 ? 0 : records / (nanos / 1e9))
              .put("records", records)
              .put("seconds", nanos / 1e9));
    }

    private static void print(JsonLine line) {
      System.out.println("RESULT " + line);
      System.out.flush();
    }
  }
}
