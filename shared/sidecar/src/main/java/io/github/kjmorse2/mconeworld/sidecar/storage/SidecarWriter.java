// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.storage;

import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.sidecar.manifest.DecodedManifest;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Exclusive write access to a sidecar, for as long as it holds the writer lock. It is not
 * thread-safe: use it from one thread. {@link #close()} releases the lock, and aborts a transaction
 * that is still open.
 */
public final class SidecarWriter implements AutoCloseable {
  private final SidecarStore store;
  private final SidecarLock lock;
  private @Nullable SidecarTransaction open;

  SidecarWriter(SidecarStore store, SidecarLock lock) {
    this.store = store;
    this.lock = lock;
  }

  public SidecarStore store() {
    return store;
  }

  /** The lock metadata recorded for this writer. */
  public LockInfo lockInfo() {
    return lock.info();
  }

  /**
   * Starts a transaction.
   *
   * @param expectedBaseRevision the manifest revision the caller based its changes on, or {@code
   *     null} if it expects that no manifest exists yet (initial creation)
   * @throws StorageException {@code BASE_CHANGED} if the manifest is not at that revision, {@code
   *     RECOVERY_REQUIRED} if leftovers exist, {@code MANIFEST_INVALID} if the current manifest is
   *     invalid, or {@code LOCK_LOST}
   */
  public SidecarTransaction begin(@Nullable Long expectedBaseRevision) throws StorageException {
    lock.verifyHeld();
    if (open != null && !open.isFinished()) {
      throw new StorageException(
          StorageIssueCodes.TRANSACTION_INVALID, "A transaction is already open");
    }
    try {
      if (!store.inspect().findings().isEmpty()) {
        throw new StorageException(
            StorageIssueCodes.RECOVERY_REQUIRED,
            "Unfinished transactions or an unreadable manifest exist; run recover() or resolve them first");
      }
      byte[] baseBytes = null;
      DecodedManifest base = null;
      Path manifestFile = store.root().internal(SidecarRoot.MANIFEST);
      if (store.fs().exists(manifestFile)) {
        baseBytes = store.readBytes(manifestFile, store.limits().maxDocumentBytes());
        try {
          base = store.codec().decode(baseBytes);
        } catch (ManifestException e) {
          throw new StorageException(
              StorageIssueCodes.MANIFEST_INVALID,
              "The current manifest is invalid; repair or restore it before writing",
              e);
        }
      }
      Long actual = base == null ? null : base.document().revision();
      if (!java.util.Objects.equals(actual, expectedBaseRevision)) {
        throw new StorageException(
            StorageIssueCodes.BASE_CHANGED,
            "Expected base revision " + expectedBaseRevision + " but the manifest is at " + actual);
      }
      Checksum baseChecksum = baseBytes == null ? null : SidecarStore.measure(baseBytes);
      open = SidecarTransaction.begin(this, UUID.randomUUID(), base, baseBytes, baseChecksum);
      return open;
    } catch (StorageException e) {
      throw e;
    } catch (IOException e) {
      throw new StorageException(StorageIssueCodes.IO_FAILURE, "Cannot begin a transaction", e);
    }
  }

  /**
   * Resolves every automatically recoverable finding: it removes the staging directories of
   * committed and aborted transactions, and the aborted transactions' provable orphans. Unexplained
   * and ambiguous findings are left untouched. Idempotent: a second call finds nothing more to do.
   *
   * @return the report after recovery (what still needs a person)
   */
  public RecoveryReport recover() throws IOException {
    lock.verifyHeld();
    RecoveryReport report = store.inspect();
    for (RecoveryFinding finding : report.findings()) {
      if (!finding.automaticallyRecoverable()) {
        continue;
      }
      Path dir = store.root().internal(SidecarRoot.STAGING).resolve(finding.stagingEntry());
      Path recordFile = dir.resolve(TransactionRecord.FILE_NAME);
      // An empty staging directory (crash before the intent was written) has no record.
      TransactionRecord record =
          store.fs().isRegularFile(recordFile)
              ? TransactionRecord.decode(store.readBytes(recordFile, TransactionRecord.MAX_BYTES))
              : null;
      if (record != null) {
        for (TransactionRecord.Entry entry : record.files()) {
          // Re-check immediately before deleting: only provable orphans are removed.
          if (finding.orphanedFiles().contains(entry.target()) && store.matches(entry)) {
            store.fs().delete(store.root().resolve(entry.target(), store.fs()));
          }
        }
      }
      SidecarTransaction.removeStagingDirectory(store.fs(), dir);
    }
    removeEmptyStagingRoot();
    return store.inspect();
  }

  private void removeEmptyStagingRoot() throws IOException {
    Path staging = store.root().internal(SidecarRoot.STAGING);
    if (store.fs().isDirectory(staging) && store.fs().list(staging).isEmpty()) {
      store.fs().delete(staging);
    }
  }

  SidecarLock lock() {
    return lock;
  }

  /** Aborts an open transaction, if any, then releases the lock. */
  @Override
  public void close() throws StorageException {
    SidecarTransaction current = open;
    try {
      if (current != null && !current.isFinished()) {
        current.abort();
      }
    } finally {
      lock.close();
    }
  }
}
