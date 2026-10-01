// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.operation;

import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.domain.operation.JournalEvent;
import io.github.kjmorse2.mconeworld.domain.operation.OperationPlan;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestLimits;
import io.github.kjmorse2.mconeworld.sidecar.storage.JdkStorageFileSystem;
import io.github.kjmorse2.mconeworld.sidecar.storage.LockOwner;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarRoot;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarStore;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarWriter;
import io.github.kjmorse2.mconeworld.sidecar.storage.StaleLockPolicy;
import io.github.kjmorse2.mconeworld.sidecar.storage.StorageException;
import io.github.kjmorse2.mconeworld.sidecar.storage.StorageFileSystem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/** Shared setup for journal tests: a sidecar in a temporary directory. */
final class JournalTestSupport {
  static final LockOwner OWNER = new LockOwner("mconeworld-test", "0.0.0");
  static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:01:40Z"), ZoneOffset.UTC);
  static final OperationPlanCodec PLANS = new OperationPlanCodec();

  private JournalTestSupport() {}

  static SidecarStore store(Path root) throws StorageException {
    return store(root, JdkStorageFileSystem.INSTANCE);
  }

  static SidecarStore store(Path root, StorageFileSystem fs) throws StorageException {
    return SidecarStore.open(SidecarRoot.open(root, fs), ManifestLimits.DEFAULT, fs);
  }

  static SidecarWriter writer(SidecarStore store) throws StorageException {
    return store.openWriter(OWNER, CLOCK, StaleLockPolicy.REFUSE);
  }

  static OperationJournals journals(SidecarStore store) {
    return OperationJournals.of(store, CLOCK);
  }

  static Checksum digest(OperationPlan plan) {
    return PLANS.digest(plan);
  }

  /** Appends every event in order and returns the journal. */
  static OperationJournal appendAll(OperationJournal journal, List<JournalEvent> events)
      throws IOException {
    for (JournalEvent event : events) {
      journal.append(journal.progress().nextSequence(), event);
    }
    return journal;
  }

  static Path journalFile(Path root, OperationPlan plan) {
    return root.resolve("operations").resolve(plan.id().toString()).resolve("journal.jsonl");
  }

  static Path planFile(Path root, OperationPlan plan) {
    return root.resolve("operations").resolve(plan.id().toString()).resolve("plan.json");
  }

  static List<String> lines(Path file) throws IOException {
    return Files.readAllLines(file);
  }

  /**
   * Rewrites one journal line as a writer of {@code version} would: with an extra member and a
   * recomputed checksum, so only the version policy can reject it.
   */
  static String rewritten(String line, String version) throws Exception {
    String body =
        line.replaceFirst("\"checksum\":\"sha256:[0-9a-f]{64}\",", "")
            .replace("\"schemaVersion\":\"1.0\"", "\"schemaVersion\":\"" + version + "\"")
            .replace("{\"event\"", "{\"futureMember\":true,\"event\"");
    var tree =
        new io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonReader(
                OperationJson.limits(1 << 20))
            .read(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    Checksum sum =
        OperationPlanCodec.sha256(
            io.github.kjmorse2.mconeworld.sidecar.internal.json.CanonicalJsonWriter.writeCompact(
                tree));
    return body.replaceFirst("\\{", "{\"checksum\":\"" + sum + "\",");
  }
}
