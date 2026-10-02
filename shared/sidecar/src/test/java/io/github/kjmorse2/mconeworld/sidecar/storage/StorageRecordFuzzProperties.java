// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.storage;

import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.fixtures.Mutations;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.statistics.Statistics;

/**
 * Corrupted {@code writer.owner.json} and {@code transaction.json} (root AGENTS.md §20). Their
 * decoders report an unreadable file as {@code null}, which the lock and recovery code turn into a
 * refusal; they must never throw, whatever the bytes.
 */
class StorageRecordFuzzProperties {
  private static final byte[] LOCK =
      LockFileFormat.encode(
          new LockInfo(
              UUID.fromString("10c40000-0000-4000-8000-000000000001"),
              "mconeworld-test",
              "1.0.0",
              "host",
              4242,
              Instant.parse("2026-01-01T00:00:00Z")));
  private static final byte[] TRANSACTION =
      new TransactionRecord(
              UUID.fromString("7a000000-0000-4000-8000-000000000001"),
              TransactionRecord.Phase.PREPARED,
              Checksum.parse("sha256:" + "ab".repeat(32)),
              3L,
              4,
              List.of(
                  new TransactionRecord.Entry(
                      "staged-1",
                      SidecarPath.of("seasons/a.json"),
                      Checksum.parse("sha256:" + "cd".repeat(32)),
                      12)),
              Checksum.parse("sha256:" + "ef".repeat(32)))
          .encode();

  @Property(tries = 2000)
  void lockFileMutantsNeverThrow(@ForAll long seed) {
    LockInfo info = LockFileFormat.decode(Mutations.mutate(LOCK, seed));
    Statistics.label("lock").collect(info == null ? "unreadable" : "readable");
  }

  @Property(tries = 2000)
  void transactionRecordMutantsNeverThrow(@ForAll long seed) {
    TransactionRecord record = TransactionRecord.decode(Mutations.mutate(TRANSACTION, seed));
    Statistics.label("transaction").collect(record == null ? "unreadable" : "readable");
  }
}
