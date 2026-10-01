// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index.spike.bench;

import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.fixtures.index.ChunkDatasets;
import io.github.kjmorse2.mconeworld.fixtures.index.ChunkRecordText;
import io.github.kjmorse2.mconeworld.fixtures.index.ReferenceChunkIndex;
import io.github.kjmorse2.mconeworld.fixtures.index.SyntheticWorld;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexReader;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkMutation;
import io.github.kjmorse2.mconeworld.sidecar.index.spike.Candidate;
import io.github.kjmorse2.mconeworld.sidecar.index.spike.RegionLogIndex;
import io.github.kjmorse2.mconeworld.sidecar.index.spike.SpikeIndex;
import io.github.kjmorse2.mconeworld.sidecar.index.spike.Workload;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Budget B3: kills a writer process with {@code SIGKILL} at a random moment while it commits update
 * batches, then checks that the index reopens to exactly the last acknowledged state or the state
 * of the batch that was being committed, never a mixture and never unreadable.
 *
 * <p>The writer keeps a reference index in step with the candidate. Before each commit it records
 * the expected digest of the next state ({@code INTENT k digest}) and after it the acknowledgement
 * ({@code DONE k}), syncing its log each time. Killing a process leaves the OS page cache intact,
 * so this tests atomicity, not power-loss durability.
 *
 * <p>Usage: {@code CrashProbe <candidate> <work directory> <run> <trials>} (the parent), or {@code
 * CrashProbe writer <candidate> <index directory> <log>} (spawned by the parent).
 */
public final class CrashProbe {
  private static final SeasonId SEASON =
      SeasonId.of(UUID.fromString("00000000-0000-4000-8000-0000000000cc"));
  private static final double SCALE = 0.5;

  private CrashProbe() {}

  public static void main(String[] args) throws Exception {
    if (args[0].equals("writer")) {
      writer(Candidate.byId(args[1]), Path.of(args[2]), Path.of(args[3]));
    } else {
      parent(Candidate.byId(args[0]), Path.of(args[1]), args[2], Integer.parseInt(args[3]));
    }
  }

  private static void parent(Candidate candidate, Path work, String run, int trials)
      throws Exception {
    BenchmarkCase.Emitter emit =
        new BenchmarkCase.Emitter(run, candidate.id(), "friend-server", SCALE, "crash");
    CorruptionProbe.deleteTree(work);
    Path base = work.resolve("base");
    Files.createDirectories(base);
    SyntheticWorld world = ChunkDatasets.named("friend-server", SCALE);
    try (SpikeIndex index = candidate.open(base)) {
      Workload.bulkLoad(world, 5000, index);
    }
    String baseDigest;
    try (SpikeIndex index = candidate.open(base);
        ChunkIndexReader reader = index.reader()) {
      baseDigest = ChunkRecordText.digest(reader);
    }
    SplittableRandom random = new SplittableRandom(0xC2A5);
    int old = 0;
    int next = 0;
    int inconsistent = 0;
    int openFailed = 0;
    long commits = 0;
    List<String> problems = new ArrayList<>();
    for (int trial = 0; trial < trials; trial++) {
      Path copy = work.resolve("trial");
      CorruptionProbe.deleteTree(copy);
      CorruptionProbe.copyTree(base, copy);
      Path log = work.resolve("trial.log");
      Files.deleteIfExists(log);
      Process writer =
          new ProcessBuilder(javaCommand("writer", candidate.id(), copy.toString(), log.toString()))
              .redirectErrorStream(true)
              .start();
      BufferedReader out =
          new BufferedReader(
              new InputStreamReader(writer.getInputStream(), StandardCharsets.UTF_8));
      String line;
      while ((line = out.readLine()) != null && !line.equals("READY")) {
        // Wait until the writer has loaded its reference and starts committing.
      }
      Thread.sleep(random.nextInt(20, 1500));
      writer.destroyForcibly();
      writer.waitFor(30, TimeUnit.SECONDS);

      Map<Integer, String> intents = new HashMap<>();
      int done = 0;
      int lastIntent = 0;
      if (Files.exists(log)) {
        for (String entry : Files.readAllLines(log)) {
          String[] parts = entry.split(" ", -1);
          if (parts[0].equals("INTENT") && parts.length == 3) {
            intents.put(Integer.parseInt(parts[1]), parts[2]);
            lastIntent = Integer.parseInt(parts[1]);
          } else if (parts[0].equals("DONE") && parts.length == 2) {
            done = Integer.parseInt(parts[1]);
          }
        }
      }
      commits += done;
      String acknowledged = done == 0 ? baseDigest : intents.get(done);
      String pending = lastIntent > done ? intents.get(lastIntent) : null;
      try (SpikeIndex index = candidate.open(copy)) {
        index.verify();
        String actual;
        try (ChunkIndexReader reader = index.reader()) {
          actual = ChunkRecordText.digest(reader);
        }
        if (actual.equals(acknowledged)) {
          old++;
        } else if (actual.equals(pending)) {
          next++;
        } else {
          inconsistent++;
          problems.add("trial " + trial + ": done=" + done + " intent=" + lastIntent);
        }
      } catch (Throwable t) { // deliberately broad: any failure to reopen is a finding
        openFailed++;
        problems.add("trial " + trial + ": " + t);
      }
    }
    emit.metric("crash.trials", "count", trials);
    emit.metric("crash.acknowledgedState", "count", old);
    emit.metric("crash.pendingState", "count", next);
    emit.metric("crash.inconsistent", "count", inconsistent);
    emit.metric("crash.openFailed", "count", openFailed);
    emit.metric("crash.commitsBeforeKill", "count", commits);
    if (!problems.isEmpty()) {
      System.out.println(
          "RESULT "
              + new JsonLine()
                  .put("format", "mconeworld-bench-result")
                  .put("version", 1)
                  .put("run", run)
                  .put("candidate", candidate.id())
                  .put("dataset", "friend-server")
                  .put("scale", SCALE)
                  .put("phase", "crash")
                  .put("metric", "crash.problems")
                  .put("unit", "list")
                  .put(
                      "value",
                      String.join(" | ", problems.subList(0, Math.min(10, problems.size())))));
    }
    CorruptionProbe.deleteTree(work);
  }

  private static void writer(Candidate candidate, Path directory, Path logFile) throws Exception {
    SyntheticWorld world = ChunkDatasets.named("friend-server", SCALE);
    ReferenceChunkIndex reference = new ReferenceChunkIndex();
    Workload.bulkLoad(world, 5000, reference);
    SplittableRandom random = new SplittableRandom(0x5EED);
    Set<ChunkPosition> removed = new HashSet<>();
    try (SpikeIndex index = candidate.open(directory);
        FileChannel log =
            FileChannel.open(
                logFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
      System.out.println("READY");
      System.out.flush();
      for (int k = 1; ; k++) {
        List<ChunkMutation> batch =
            Workload.updateBatch(
                world, random, 200, SEASON, Instant.parse("2032-01-01T00:00:00Z"), removed);
        Workload.applyAll(batch, reference);
        String digest;
        try (ChunkIndexReader reader = reference.reader()) {
          digest = ChunkRecordText.digest(reader);
        }
        append(log, "INTENT " + k + " " + digest + "\n");
        Workload.applyAll(batch, index);
        append(log, "DONE " + k + "\n");
        if (index instanceof RegionLogIndex regionLog && k % 5 == 0) {
          regionLog.checkpoint(); // kills also land inside checkpoints; state must not change
        }
      }
    }
  }

  private static void append(FileChannel log, String line) throws java.io.IOException {
    ByteBuffer bytes = ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8));
    while (bytes.hasRemaining()) {
      log.write(bytes);
    }
    log.force(true);
  }

  /** A command line that runs this class in a new JVM with the current classpath and options. */
  static List<String> javaCommand(String... args) {
    List<String> command = new ArrayList<>();
    command.add(ProcessHandle.current().info().command().orElse("java"));
    command.add("-Xmx512m");
    command.add("--enable-native-access=ALL-UNNAMED");
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(CrashProbe.class.getName());
    command.addAll(List.of(args));
    return command;
  }
}
