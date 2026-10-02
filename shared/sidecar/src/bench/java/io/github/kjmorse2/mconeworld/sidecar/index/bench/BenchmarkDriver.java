// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index.bench;

import io.github.kjmorse2.mconeworld.fixtures.index.ChunkDatasets;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * Runs the chunk-index benchmarks. Every case runs in fresh JVMs (build, then query) with a fixed
 * heap: {@code -Xmx256m} for the {@code millions} dataset (budget B1) and {@code -Xmx512m}
 * otherwise. Result lines are appended to one JSON Lines file; child logs stay in the work
 * directory.
 *
 * <pre>
 *   --candidates all | c1-region-files,c2-sqlite,…   (default all)
 *   --datasets   all | friend-server,millions,…      (default all)
 *   --scale      linear dataset scale                  (default 1)
 *   --probes     also run the corruption and crash probes on friend-server
 *   --no-cases   run only the probes
 *   --work       scratch directory  (default shared/sidecar/build/chunk-index-bench)
 *   --out        results file       (default docs/benchmarks/chunk-index/raw/&lt;run&gt;.jsonl)
 * </pre>
 */
public final class BenchmarkDriver {
  private BenchmarkDriver() {}

  public static void main(String[] args) throws Exception {
    List<String> list = Arrays.asList(args);
    if (list.isEmpty() || list.contains("--help")) {
      System.out.println(
          "usage: --candidates all|ids --datasets all|names [--scale s] [--probes] [--no-cases]"
              + " [--work dir] [--out file]");
      return;
    }
    List<Candidate> candidates = new ArrayList<>();
    for (String id : option(list, "--candidates", "all").split(",", -1)) {
      if (id.equals("all")) {
        candidates.addAll(List.of(Candidate.values()));
      } else {
        candidates.add(Candidate.byId(id));
      }
    }
    String datasetOption = option(list, "--datasets", "all");
    List<String> datasets =
        datasetOption.equals("all") ? ChunkDatasets.NAMES : List.of(datasetOption.split(",", -1));
    String scale = option(list, "--scale", "1");
    Path work = Path.of(option(list, "--work", "shared/sidecar/build/chunk-index-bench"));
    String commit = git("rev-parse", "--short=12", "HEAD");
    boolean dirty = !git("status", "--porcelain", "--", "shared", "gradle").isEmpty();
    String run =
        "run-"
            + ZonedDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
            + "-"
            + commit
            + (dirty ? "-dirty" : "");
    Path out = Path.of(option(list, "--out", "docs/benchmarks/chunk-index/raw/" + run + ".jsonl"));
    Files.createDirectories(work);
    Files.createDirectories(out.toAbsolutePath().getParent());

    try (Writer results =
        Files.newBufferedWriter(
            out, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
      results.write(environment(run, commit, dirty, scale, work) + "\n");
      results.flush();
      if (!list.contains("--no-cases")) {
        for (String dataset : datasets) {
          for (Candidate candidate : candidates) {
            Path directory = work.resolve(candidate.id() + "-" + dataset);
            CorruptionProbe.deleteTree(directory);
            String heap = dataset.equals("millions") ? "-Xmx256m" : "-Xmx512m";
            String[] caseArgs = {candidate.id(), dataset, scale, directory.toString(), run};
            boolean built =
                child(
                    results,
                    work,
                    run,
                    candidate,
                    dataset,
                    heap,
                    BenchmarkCase.class,
                    "build",
                    caseArgs);
            if (built) {
              child(
                  results,
                  work,
                  run,
                  candidate,
                  dataset,
                  heap,
                  BenchmarkCase.class,
                  "query",
                  caseArgs);
            }
            CorruptionProbe.deleteTree(directory);
          }
        }
      }
      if (list.contains("--probes")) {
        for (Candidate candidate : candidates) {
          Path probeWork = work.resolve("probe-" + candidate.id());
          child(
              results,
              work,
              run,
              candidate,
              "friend-server",
              "-Xmx512m",
              CorruptionProbe.class,
              null,
              candidate.id(),
              probeWork.toString(),
              run,
              "300");
          child(
              results,
              work,
              run,
              candidate,
              "friend-server",
              "-Xmx512m",
              CrashProbe.class,
              null,
              candidate.id(),
              probeWork.toString(),
              run,
              "40");
        }
      }
    }
    System.out.println("Results: " + out);
  }

  /** Runs one child JVM; returns whether it succeeded. Records a status line either way. */
  private static boolean child(
      Writer results,
      Path work,
      String run,
      Candidate candidate,
      String dataset,
      String heap,
      Class<?> main,
      @Nullable String phase,
      String... args)
      throws IOException, InterruptedException {
    List<String> command = new ArrayList<>();
    command.add(ProcessHandle.current().info().command().orElse("java"));
    command.add(heap);
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(main.getName());
    if (phase != null) {
      command.add(phase);
    }
    command.addAll(List.of(args));
    String label =
        candidate.id() + " " + dataset + " " + (phase == null ? main.getSimpleName() : phase);
    System.out.println("== " + label);
    Path log = work.resolve(label.replace(' ', '_') + ".log");
    Process process = new ProcessBuilder(command).redirectError(log.toFile()).start();
    try (BufferedReader stdout =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      for (String line = stdout.readLine(); line != null; line = stdout.readLine()) {
        if (line.startsWith("RESULT ")) {
          results.write(line.substring(7) + "\n");
          results.flush();
        }
      }
    }
    boolean finished = process.waitFor(90, TimeUnit.MINUTES);
    int exit = finished ? process.exitValue() : -1;
    if (!finished) {
      process.destroyForcibly();
    }
    String status = exit == 0 ? "ok" : "failed:" + exit + " " + tail(log);
    results.write(
        new JsonLine()
                .put("format", "mconeworld-bench-result")
                .put("version", 1)
                .put("run", run)
                .put("candidate", candidate.id())
                .put("dataset", dataset)
                .put("phase", phase == null ? main.getSimpleName() : phase)
                .put("metric", "case.status")
                .put("unit", "status")
                .put("value", status)
            + "\n");
    results.flush();
    System.out.println("   " + status);
    return exit == 0;
  }

  private static String tail(Path log) throws IOException {
    if (!Files.exists(log)) {
      return "";
    }
    List<String> lines = Files.readAllLines(log);
    for (String line : lines) {
      if (line.contains("Exception") || line.contains("Error")) {
        return line.trim(); // the cause, e.g. "java.lang.OutOfMemoryError: Java heap space"
      }
    }
    return String.join(" / ", lines.subList(Math.max(0, lines.size() - 3), lines.size()));
  }

  /**
   * Median latency of writing 4 KiB and forcing it to disk, in the work directory: the floor under
   * every durable commit on this machine.
   */
  private static double fsyncMillis(Path work) throws IOException {
    Path file = work.resolve("fsync-probe.bin");
    Stats stats = new Stats();
    try (java.nio.channels.FileChannel channel =
        java.nio.channels.FileChannel.open(
            file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
      java.nio.ByteBuffer block = java.nio.ByteBuffer.allocate(4096);
      for (int i = 0; i < 25; i++) {
        block.rewind();
        long t = System.nanoTime();
        channel.write(block, (long) i * 4096);
        channel.force(true);
        stats.add(System.nanoTime() - t);
      }
    } finally {
      Files.deleteIfExists(file);
    }
    return stats.percentile(50) / 1e6;
  }

  private static String environment(
      String run, String commit, boolean dirty, String scale, Path work) throws IOException {
    String cpu = "unknown";
    Path cpuinfo = Path.of("/proc/cpuinfo");
    if (Files.exists(cpuinfo)) {
      for (String line : Files.readAllLines(cpuinfo)) {
        if (line.startsWith("model name")) {
          cpu = line.substring(line.indexOf(':') + 1).trim();
          break;
        }
      }
    }
    return new JsonLine()
        .put("format", "mconeworld-bench-result")
        .put("version", 1)
        .put("run", run)
        .put("phase", "env")
        .put("metric", "environment")
        .put("unit", "info")
        .put("commit", commit)
        .put("dirty", dirty ? "yes" : "no")
        .put("scale", scale)
        .put("java", System.getProperty("java.vm.name") + " " + System.getProperty("java.version"))
        .put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"))
        .put("cpu", cpu)
        .put("processors", Runtime.getRuntime().availableProcessors())
        .put("fsyncMillisP50", fsyncMillis(work))
        .toString();
  }

  private static String option(List<String> args, String name, String fallback) {
    int i = args.indexOf(name);
    return i >= 0 && i + 1 < args.size() ? args.get(i + 1) : fallback;
  }

  private static String git(String... args) {
    List<String> command = new ArrayList<>(List.of("git"));
    command.addAll(List.of(args));
    try {
      Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
      String text = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
      return p.waitFor() == 0 ? text.toLowerCase(Locale.ROOT) : "unknown";
    } catch (IOException | InterruptedException e) {
      return "unknown";
    }
  }
}
