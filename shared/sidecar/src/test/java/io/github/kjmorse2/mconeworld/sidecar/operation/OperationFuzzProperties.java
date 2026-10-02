// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.operation;

import static io.github.kjmorse2.mconeworld.sidecar.operation.JournalTestSupport.journals;
import static io.github.kjmorse2.mconeworld.sidecar.operation.JournalTestSupport.store;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.kjmorse2.mconeworld.fixtures.ManifestFixtures;
import io.github.kjmorse2.mconeworld.fixtures.Mutations;
import io.github.kjmorse2.mconeworld.fixtures.OperationFixtures;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.CanonicalJsonWriter;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonReadException;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonReadLimits;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonReader;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonObject;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonString;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.statistics.Statistics;

/**
 * Corrupted plans and journals (root AGENTS.md §20). Plans and single journal lines either decode
 * or fail with {@link OperationFormatException}. Journal lines are also re-sealed with a correct
 * checksum after mutation, so the fuzzing reaches the field checks behind the checksum. Whole
 * corrupted journals on disk are always classified by {@link OperationJournals#inspect}, never
 * thrown as anything but a coded {@link IOException}.
 */
class OperationFuzzProperties {
  private static final byte[] PLAN = ManifestFixtures.resource(OperationFixtures.GOLDEN_PLAN_V1);
  private static final byte[] JOURNAL =
      ManifestFixtures.resource(OperationFixtures.GOLDEN_JOURNAL_V1);
  private static final List<byte[]> LINES = lines(JOURNAL);
  private static final JsonReader JSON =
      new JsonReader(new JsonReadLimits(1 << 20, 16, 8192, 4096, 4096));

  @Property(tries = 2000)
  void planMutantsDecodeOrFailWithAFormatException(@ForAll long seed) {
    try {
      DecodedPlan decoded = new OperationPlanCodec().decode(Mutations.mutate(PLAN, seed));
      assertThat(decoded.plan()).isNotNull();
      Statistics.label("plan").collect("accepted");
    } catch (OperationFormatException e) {
      Statistics.label("plan").collect(e.code().value());
    }
  }

  @Property(tries = 3000)
  void resealedJournalLineMutantsDecodeOrFailWithAFormatException(@ForAll long seed) {
    byte[] line = LINES.get((int) Math.floorMod(seed, (long) LINES.size()));
    byte[] mutant = reseal(Mutations.mutate(line, seed));
    try {
      new JournalEntryCodec().decode(mutant);
      Statistics.label("line").collect("accepted");
    } catch (OperationFormatException e) {
      Statistics.label("line").collect(e.code().value());
    }
  }

  @Property(tries = 300)
  void corruptedJournalsOnDiskAreClassified(@ForAll long seed) throws IOException {
    Path root = Files.createTempDirectory("journal-fuzz");
    try {
      SidecarFixture.write(root, PLAN, JOURNAL);
      Path journal = SidecarFixture.journalPath(root);
      Files.write(journal, Mutations.mutate(JOURNAL, seed));
      OperationStatus status = journals(store(root)).inspect(OperationFixtures.fullPlan().id());
      Statistics.label("integrity").collect(status.integrity().name());
    } finally {
      try (var walk = Files.walk(root)) {
        for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
          Files.delete(p);
        }
      }
    }
  }

  /** Recomputes the checksum of a line that still parses as an object; others are left as is. */
  private static byte[] reseal(byte[] line) {
    try {
      JsonValue tree = JSON.read(line);
      if (!(tree instanceof JsonObject object)) {
        return line;
      }
      Map<String, JsonValue> members = new LinkedHashMap<>(object.members());
      members.remove("checksum");
      String checksum =
          OperationPlanCodec.sha256(CanonicalJsonWriter.writeCompact(new JsonObject(members)))
              .toString();
      members.put("checksum", new JsonString(checksum));
      return CanonicalJsonWriter.writeCompact(new JsonObject(members));
    } catch (JsonReadException | RuntimeException e) {
      return line;
    }
  }

  private static List<byte[]> lines(byte[] journal) {
    List<byte[]> out = new ArrayList<>();
    int start = 0;
    for (int i = 0; i < journal.length; i++) {
      if (journal[i] == '\n') {
        out.add(Arrays.copyOfRange(journal, start, i));
        start = i + 1;
      }
    }
    return out;
  }

  /** Lays out the golden plan and journal as one operation in a new sidecar. */
  private static final class SidecarFixture {
    static void write(Path root, byte[] plan, byte[] journal) throws IOException {
      Path dir = journalPath(root).getParent();
      Files.createDirectories(dir);
      Files.write(dir.resolve(OperationJournals.PLAN_FILE), plan);
      Files.write(journalPath(root), journal);
    }

    static Path journalPath(Path root) {
      return JournalTestSupport.journalFile(root, OperationFixtures.fullPlan());
    }
  }

  static {
    assertThat(new String(JOURNAL, StandardCharsets.UTF_8)).endsWith("\n");
  }
}
