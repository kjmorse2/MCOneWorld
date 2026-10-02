// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index.bench;

import io.github.kjmorse2.mconeworld.fixtures.index.ChunkDatasets;
import io.github.kjmorse2.mconeworld.fixtures.index.ChunkRecordText;
import io.github.kjmorse2.mconeworld.fixtures.index.IndexWorkloads;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexException;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Stream;

/**
 * Budget B2: flips one byte at a random offset of a random index file (weighted by size), on a
 * fresh copy each trial, then classifies what two kinds of reader see:
 *
 * <ul>
 *   <li><b>read path</b>: open the index and export every record, as a normal reader would.
 *   <li><b>verify path</b>: open, run the candidate's strongest integrity check, then export.
 * </ul>
 *
 * <p>Outcomes: {@code detected} (a coded {@link ChunkIndexException}), {@code crashed} (any other
 * exception or error: detected, but not cleanly), {@code harmless} (the export is byte-identical,
 * for example a flip in free space), or {@code silent} (no error, different records). A {@code
 * silent} outcome on the read path fails gate B2.
 *
 * <p>Usage: {@code CorruptionProbe <candidate> <work directory> <run> <trials>}
 */
public final class CorruptionProbe {
  private CorruptionProbe() {}

  public static void main(String[] args) throws Exception {
    Candidate candidate = Candidate.byId(args[0]);
    Path work = Path.of(args[1]);
    int trials = Integer.parseInt(args[3]);
    BenchmarkCase.Emitter emit =
        new BenchmarkCase.Emitter(args[2], candidate.id(), "friend-server", 1.0, "corruption");
    Path base = work.resolve("base");
    deleteTree(work);
    Files.createDirectories(base);
    try (BenchIndex index = candidate.open(base)) {
      IndexWorkloads.bulkLoad(ChunkDatasets.named("friend-server", 1.0), 5000, index);
    }
    String expected;
    try (BenchIndex index = candidate.open(base);
        ChunkIndexReader reader = index.reader()) {
      expected = ChunkRecordText.digest(reader);
    }
    List<Path> files = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(base)) {
      walk.filter(Files::isRegularFile).sorted().forEach(files::add);
    }
    long[] sizes = new long[files.size()];
    long total = 0;
    for (int i = 0; i < files.size(); i++) {
      sizes[i] = Files.size(files.get(i));
      total += sizes[i];
    }

    SplittableRandom random = new SplittableRandom(0xC0DE);
    int[] read = new int[4];
    int[] verify = new int[4];
    List<String> silentCases = new ArrayList<>();
    for (int trial = 0; trial < trials; trial++) {
      long target = random.nextLong(total);
      int file = 0;
      while (target >= sizes[file]) {
        target -= sizes[file++];
      }
      int flip = 1 + random.nextInt(255);
      Path copy = work.resolve("trial");
      deleteTree(copy);
      copyTree(base, copy);
      Path victim = copy.resolve(base.relativize(files.get(file)));
      flipByte(victim, target, flip);
      int r = classify(candidate, copy, expected, false);
      read[r]++;
      deleteTree(copy);
      copyTree(base, copy);
      flipByte(victim, target, flip);
      int v = classify(candidate, copy, expected, true);
      verify[v]++;
      if ((r == 3 || v == 3) && silentCases.size() < 20) {
        silentCases.add(
            base.relativize(files.get(file))
                + "@"
                + target
                + (r == 3 ? ":read" : "")
                + (v == 3 ? ":verify" : ""));
      }
    }
    String[] names = {"detected", "crashed", "harmless", "silent"};
    for (int i = 0; i < 4; i++) {
      emit.metric("corruption.read." + names[i], "count", read[i]);
      emit.metric("corruption.verify." + names[i], "count", verify[i]);
    }
    emit.metric("corruption.trials", "count", trials);
    emit.metric("corruption.bytes", "bytes", total);
    System.out.println(
        "RESULT "
            + new JsonLine()
                .put("format", "mconeworld-bench-result")
                .put("version", 1)
                .put("run", args[2])
                .put("candidate", candidate.id())
                .put("dataset", "friend-server")
                .put("scale", 1.0)
                .put("phase", "corruption")
                .put("metric", "corruption.silentExamples")
                .put("unit", "list")
                .put("value", String.join(" ", silentCases)));
    deleteTree(work);
  }

  /** 0 detected, 1 crashed, 2 harmless, 3 silent. */
  private static int classify(
      Candidate candidate, Path directory, String expected, boolean verify) {
    try (BenchIndex index = candidate.open(directory)) {
      if (verify) {
        index.verify();
      }
      try (ChunkIndexReader reader = index.reader()) {
        return ChunkRecordText.digest(reader).equals(expected) ? 2 : 3;
      }
    } catch (ChunkIndexException e) {
      return 0;
    } catch (Throwable t) { // deliberately broad: any uncoded failure counts as "crashed"
      return 1;
    }
  }

  public static void flipByte(Path file, long offset, int mask) throws IOException {
    try (FileChannel channel =
        FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
      ByteBuffer one = ByteBuffer.allocate(1);
      channel.read(one, offset);
      one.flip();
      byte b = one.get();
      channel.write(ByteBuffer.wrap(new byte[] {(byte) (b ^ mask)}), offset);
    }
  }

  static void copyTree(Path from, Path to) throws IOException {
    try (Stream<Path> walk = Files.walk(from)) {
      for (Path p : (Iterable<Path>) walk::iterator) {
        Path target = to.resolve(from.relativize(p));
        if (Files.isDirectory(p)) {
          Files.createDirectories(target);
        } else {
          Files.copy(p, target);
        }
      }
    }
  }

  static void deleteTree(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(root)) {
      List<Path> all = walk.sorted(Comparator.reverseOrder()).toList();
      for (Path p : all) {
        Files.delete(p);
      }
    }
  }
}
