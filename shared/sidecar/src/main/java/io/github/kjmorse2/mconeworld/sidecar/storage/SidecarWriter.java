// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.storage;

import io.github.kjmorse2.mconeworld.domain.ArchiveId;
import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.sidecar.manifest.DecodedManifest;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
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
    try {
      beforeBegin(false);
      Path manifestFile = store.root().internal(SidecarRoot.MANIFEST);
      SidecarTransaction.Base base = null;
      if (store.fs().exists(manifestFile)) {
        byte[] bytes = store.readBytes(manifestFile, store.limits().maxDocumentBytes());
        DecodedManifest decoded;
        try {
          decoded = store.codec().decode(bytes);
        } catch (ManifestException e) {
          throw new StorageException(
              StorageIssueCodes.MANIFEST_INVALID,
              "The current manifest is invalid; repair or restore it before writing",
              e);
        }
        base = SidecarTransaction.Base.of(bytes, SidecarStore.measure(bytes), decoded);
      }
      Long actual = base == null ? null : base.revision();
      if (!Objects.equals(actual, expectedBaseRevision)) {
        throw new StorageException(
            StorageIssueCodes.BASE_CHANGED,
            "Expected base revision " + expectedBaseRevision + " but the manifest is at " + actual);
      }
      open = SidecarTransaction.begin(this, UUID.randomUUID(), base);
      return open;
    } catch (StorageException e) {
      throw e;
    } catch (IOException e) {
      throw new StorageException(StorageIssueCodes.IO_FAILURE, "Cannot begin a transaction", e);
    }
  }

  /**
   * Starts a transaction that replaces a manifest the current codec cannot decode: a legacy schema
   * being migrated. The base is identified by its exact checksum instead of a decoded revision. Use
   * it only from the migration framework ({@code sidecar.migration}), which has already parsed the
   * legacy revision and archive id. Nothing the legacy manifest references is treated as
   * pre-verified.
   *
   * @throws StorageException {@code BASE_CHANGED} if the manifest's checksum is not {@code
   *     expectedBaseChecksum}, or {@code RECOVERY_REQUIRED} if leftover transactions exist
   */
  public SidecarTransaction beginSchemaMigration(
      Checksum expectedBaseChecksum, long baseRevision, ArchiveId archiveId)
      throws StorageException {
    try {
      beforeBegin(true);
      Path manifestFile = store.root().internal(SidecarRoot.MANIFEST);
      if (!store.fs().exists(manifestFile)) {
        throw new StorageException(
            StorageIssueCodes.BASE_CHANGED, "There is no manifest to migrate");
      }
      byte[] bytes = store.readBytes(manifestFile, store.limits().maxDocumentBytes());
      Checksum actual = SidecarStore.measure(bytes);
      if (!actual.equals(expectedBaseChecksum)) {
        throw new StorageException(
            StorageIssueCodes.BASE_CHANGED, "The manifest changed since the migration was planned");
      }
      open =
          SidecarTransaction.begin(
              this,
              UUID.randomUUID(),
              new SidecarTransaction.Base(bytes, actual, baseRevision, archiveId, Map.of()));
      return open;
    } catch (StorageException e) {
      throw e;
    } catch (IOException e) {
      throw new StorageException(StorageIssueCodes.IO_FAILURE, "Cannot begin a migration", e);
    }
  }

  /**
   * Common checks: lock held, no open transaction, and no leftovers (a legacy manifest is allowed
   * for migrations).
   */
  private void beforeBegin(boolean legacyManifestAllowed) throws IOException {
    lock.verifyHeld();
    if (open != null && !open.isFinished()) {
      throw new StorageException(
          StorageIssueCodes.TRANSACTION_INVALID, "A transaction is already open");
    }
    boolean blocking =
        store.inspect().findings().stream()
            .anyMatch(
                f ->
                    !(legacyManifestAllowed
                        && f.code().equals(StorageIssueCodes.RECOVERY_MANIFEST_UNREADABLE)));
    if (blocking) {
      throw new StorageException(
          StorageIssueCodes.RECOVERY_REQUIRED,
          "Unfinished transactions or an unreadable manifest exist; run recover() or resolve them first");
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

  /**
   * Durable writes to the {@code operations/} area (plans and journals), under this writer's lock.
   */
  public OperationFileWriter operationFiles() {
    return new OperationFileWriter(this);
  }

  /**
   * Verifies that this writer still holds the lock.
   *
   * @throws StorageException {@code LOCK_LOST} if it does not
   */
  public void verifyLockHeld() throws StorageException {
    lock.verifyHeld();
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
