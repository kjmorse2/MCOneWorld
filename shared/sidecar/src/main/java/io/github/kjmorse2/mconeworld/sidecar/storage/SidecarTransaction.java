// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.storage;

import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.domain.archive.BackupRecord;
import io.github.kjmorse2.mconeworld.sidecar.manifest.DecodedManifest;
import io.github.kjmorse2.mconeworld.sidecar.manifest.FileReference;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestDocument;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One atomic change to the sidecar: stage zero or more new data files, then commit a new manifest
 * that references them. It is obtained from {@link SidecarWriter#begin(Long)}.
 *
 * <p><b>Protocol</b>, in order. The manifest rename is the single commit point.
 *
 * <ol>
 *   <li>begin: create {@code .staging/<id>/}, and durably write {@code transaction.json} (phase
 *       STAGING)
 *   <li>stage: write each data file into the staging directory, then fsync it
 *   <li>commit, preparation:
 *       <ul>
 *         <li>encode and validate the new manifest
 *         <li>re-read every staged file and check its checksum and size
 *         <li>verify every newly referenced file
 *         <li>durably write the staged manifest and a copy of the current one
 *         <li>replace {@code transaction.json} atomically with phase PREPARED, listing every target
 *         <li>move data files to their final, never-overwritten paths
 *       </ul>
 *   <li>commit point: atomic rename of the copy to {@code manifest.previous.json}, then of the new
 *       manifest to {@code manifest.json}; then fsync the root directory
 *   <li>cleanup: remove the staging directory, deleting {@code transaction.json} last
 * </ol>
 *
 * <p>Any exception before the commit point rolls back: moved files are removed, and the staging
 * directory is deleted. The previous manifest is never touched. If the rollback itself fails, or
 * the process dies, the leftovers are classified precisely by {@link SidecarStore#inspect()}.
 * Failures after the commit point never undo the commit; they are reported in {@link CommitResult}.
 */
public final class SidecarTransaction {
  private static final String STAGED_MANIFEST = "manifest.json";
  private static final String STAGED_PREVIOUS = "manifest.previous.json";
  private static final int BUFFER = 64 * 1024;

  private enum State {
    OPEN,
    COMMITTED,
    ABORTED,
    FAILED
  }

  private final SidecarWriter writer;
  private final SidecarStore store;
  private final StorageFileSystem fs;
  private final SidecarRoot root;
  private final UUID id;
  private final Path dir;
  private final @Nullable DecodedManifest base;
  private final byte @Nullable [] baseBytes;
  private final @Nullable Checksum baseChecksum;
  private final List<TransactionRecord.Entry> staged = new ArrayList<>();
  private final List<Path> movedTargets = new ArrayList<>();
  private @Nullable Checksum targetManifest;
  private boolean directorySyncSupported = true;
  private State state = State.OPEN;
  private int counter;

  private SidecarTransaction(
      SidecarWriter writer,
      UUID id,
      Path dir,
      @Nullable DecodedManifest base,
      byte @Nullable [] baseBytes,
      @Nullable Checksum baseChecksum) {
    this.writer = writer;
    this.store = writer.store();
    this.fs = store.fs();
    this.root = store.root();
    this.id = id;
    this.dir = dir;
    this.base = base;
    this.baseBytes = baseBytes;
    this.baseChecksum = baseChecksum;
  }

  static SidecarTransaction begin(
      SidecarWriter writer,
      UUID id,
      @Nullable DecodedManifest base,
      byte @Nullable [] baseBytes,
      @Nullable Checksum baseChecksum)
      throws StorageException {
    StorageFileSystem fs = writer.store().fs();
    SidecarRoot root = writer.store().root();
    Path staging = root.internal(SidecarRoot.STAGING);
    Path dir = staging.resolve(id.toString());
    SidecarTransaction tx = new SidecarTransaction(writer, id, dir, base, baseBytes, baseChecksum);
    try {
      if (fs.isSymbolicLink(staging) || (fs.exists(staging) && !fs.isDirectory(staging))) {
        throw new StorageException(
            StorageIssueCodes.SYMLINK_REFUSED, ".staging must be a real directory");
      }
      if (!fs.exists(staging)) {
        fs.createDirectory(staging);
        tx.syncDir(root.path());
      }
      fs.createDirectory(dir);
      tx.syncDir(staging);
      fs.checkpoint(CommitStage.BEFORE_STAGING, dir);
      tx.writeRecord(TransactionRecord.Phase.STAGING);
    } catch (IOException | RuntimeException e) {
      throw tx.failAndRollBack(e);
    }
    return tx;
  }

  /** This transaction's id, which is also its staging directory name. */
  public UUID id() {
    return id;
  }

  /**
   * Streams {@code content} into staging for the new file {@code target}. The stream is not closed.
   *
   * @throws StorageException {@code RESERVED_PATH}, {@code SYMLINK_REFUSED}, {@code TOO_LARGE}, or
   *     {@code TRANSACTION_INVALID} (duplicate target). On any failure the transaction rolls back.
   */
  public StagedFile stage(SidecarPath target, InputStream content, long maxBytes)
      throws StorageException {
    ensureOpen();
    try {
      writer.lock().verifyHeld();
      if (SidecarRoot.isReserved(target)) {
        throw new StorageException(
            StorageIssueCodes.RESERVED_PATH, target + " is reserved for sidecar bookkeeping");
      }
      if (staged.stream().anyMatch(e -> e.target().equals(target))) {
        throw new StorageException(
            StorageIssueCodes.TRANSACTION_INVALID,
            target + " is already staged in this transaction");
      }
      root.resolve(target, fs);
      String name = "data-" + counter++;
      Path stagedPath = dir.resolve(name);
      Checksums.Digester digester = new Checksums.Digester();
      try (StorageFileSystem.WritableFile file = fs.createNew(stagedPath)) {
        byte[] buffer = new byte[BUFFER];
        int n;
        while ((n = content.read(buffer)) != -1) {
          if (digester.size() + n > maxBytes) {
            throw new StorageException(
                StorageIssueCodes.TOO_LARGE, target + " exceeds " + maxBytes + " bytes");
          }
          file.write(buffer, 0, n);
          digester.update(buffer, 0, n);
          fs.checkpoint(CommitStage.WRITING_CONTENT, stagedPath);
        }
        fs.checkpoint(CommitStage.BEFORE_SYNC, stagedPath);
        file.sync();
      }
      Checksums.Measured measured = digester.finish();
      staged.add(
          new TransactionRecord.Entry(name, target, measured.checksum(), measured.sizeBytes()));
      return new StagedFile(target, measured.checksum(), measured.sizeBytes());
    } catch (IOException | RuntimeException e) {
      throw failAndRollBack(e);
    }
  }

  /**
   * Validates and commits {@code next}. Its revision must be exactly the base revision + 1 (or 0
   * for the first manifest), and every staged file must be referenced by it.
   *
   * @throws StorageException if the commit did not happen. The previous manifest is intact.
   */
  public CommitResult commit(ManifestDocument next) throws StorageException {
    ensureOpen();
    Path manifestFile = root.internal(SidecarRoot.MANIFEST);
    try {
      prepare(next);
      fs.checkpoint(CommitStage.BEFORE_MANIFEST_REPLACEMENT, manifestFile);
      if (baseBytes != null) {
        fs.moveAtomically(
            dir.resolve(STAGED_PREVIOUS), root.internal(SidecarRoot.PREVIOUS_MANIFEST));
      }
      fs.moveAtomically(dir.resolve(STAGED_MANIFEST), manifestFile); // commit point
    } catch (IOException | RuntimeException e) {
      if (!committedDespite()) {
        throw failAndRollBack(e);
      }
    }
    state = State.COMMITTED;
    try {
      syncDir(root.path());
    } catch (IOException e) {
      directorySyncSupported = false; // committed; durability of the rename is reported, not thrown
    }
    boolean cleaned = true;
    try {
      fs.checkpoint(CommitStage.AFTER_MANIFEST_REPLACEMENT, manifestFile);
      removeStagingDirectory(fs, dir);
      Path staging = root.internal(SidecarRoot.STAGING);
      if (fs.list(staging).isEmpty()) {
        fs.delete(staging);
      }
    } catch (IOException e) {
      cleaned = false; // recovery will classify the leftover as COMMITTED and remove it
    }
    Checksum committed = targetManifest;
    if (committed == null) {
      throw new IllegalStateException("targetManifest is set by prepare()");
    }
    return new CommitResult(id, next.revision(), committed, directorySyncSupported, cleaned);
  }

  /** Rolls back an open transaction. The previous manifest is never touched. Idempotent. */
  public void abort() throws StorageException {
    if (state != State.OPEN) {
      return;
    }
    state = State.ABORTED;
    try {
      rollBack();
    } catch (IOException e) {
      throw new StorageException(
          StorageIssueCodes.IO_FAILURE, "Rollback incomplete; run recovery", e);
    }
  }

  boolean isFinished() {
    return state != State.OPEN;
  }

  // --- preparation -------------------------------------------------------------------------

  private void prepare(ManifestDocument next) throws IOException {
    writer.lock().verifyHeld();
    long expectedRevision = base == null ? 0 : base.document().revision() + 1;
    if (next.revision() != expectedRevision) {
      throw new StorageException(
          StorageIssueCodes.TRANSACTION_INVALID,
          "New manifest revision must be " + expectedRevision + ", got " + next.revision());
    }
    if (base != null && !next.archiveId().equals(base.document().archiveId())) {
      throw new StorageException(
          StorageIssueCodes.TRANSACTION_INVALID, "The archive id of a sidecar never changes");
    }
    Map<SidecarPath, Checksums.Measured> references = references(next);
    for (SidecarPath path : references.keySet()) {
      if (SidecarRoot.isReserved(path)) {
        throw new StorageException(
            StorageIssueCodes.RESERVED_PATH, "The manifest may not reference " + path);
      }
      root.resolve(path, fs);
    }
    byte[] bytes;
    try {
      bytes = store.codec().encode(next);
    } catch (ManifestException e) {
      throw new StorageException(
          StorageIssueCodes.MANIFEST_INVALID, "The new manifest is invalid: " + e.getMessage(), e);
    }

    fs.checkpoint(CommitStage.BEFORE_CHECKSUM_VERIFICATION, dir);
    for (TransactionRecord.Entry entry : staged) {
      Checksums.Measured onDisk =
          store.measureFile(dir.resolve(entry.stagedName()), entry.sizeBytes());
      if (onDisk == null
          || !onDisk.checksum().equals(entry.checksum())
          || onDisk.sizeBytes() != entry.sizeBytes()) {
        throw new StorageException(
            StorageIssueCodes.CHECKSUM_MISMATCH,
            "Staged content for " + entry.target() + " changed on disk");
      }
    }
    verifyReferences(references);

    Set<SidecarPath> alreadyInPlace = new HashSet<>();
    for (TransactionRecord.Entry entry : staged) {
      Path target = root.resolve(entry.target(), fs);
      if (fs.exists(target)) {
        if (store.matches(entry)) {
          alreadyInPlace.add(entry.target()); // idempotent re-stage of identical content
        } else {
          throw new StorageException(
              StorageIssueCodes.DATA_FILE_CONFLICT,
              entry.target()
                  + " already exists with different content; committed data files are immutable");
        }
      }
    }

    writeDurably(dir.resolve(STAGED_MANIFEST), bytes);
    byte[] reread = store.readBytes(dir.resolve(STAGED_MANIFEST), bytes.length);
    if (!java.util.Arrays.equals(reread, bytes)) {
      throw new StorageException(
          StorageIssueCodes.CHECKSUM_MISMATCH, "Staged manifest did not read back identically");
    }
    if (baseBytes != null) {
      writeDurably(dir.resolve(STAGED_PREVIOUS), baseBytes);
    }
    targetManifest = SidecarStore.measure(bytes);
    writeRecord(TransactionRecord.Phase.PREPARED);

    for (TransactionRecord.Entry entry : staged) {
      if (alreadyInPlace.contains(entry.target())) {
        continue;
      }
      Path target = root.resolve(entry.target(), fs);
      ensureParents(target);
      fs.moveAtomically(dir.resolve(entry.stagedName()), target);
      movedTargets.add(target);
      syncDir(target.getParent());
    }
  }

  /** Every path the manifest references, with the content identity it claims. */
  private static Map<SidecarPath, Checksums.Measured> references(ManifestDocument doc) {
    Map<SidecarPath, Checksums.Measured> refs = new HashMap<>();
    for (FileReference file : doc.files()) {
      refs.put(file.path(), new Checksums.Measured(file.checksum(), file.sizeBytes()));
    }
    for (BackupRecord backup : doc.state().backups()) {
      SidecarPath path = doc.backupPaths().get(backup.id());
      if (path != null) {
        refs.put(path, new Checksums.Measured(backup.checksum(), backup.sizeBytes()));
      }
    }
    return refs;
  }

  private void verifyReferences(Map<SidecarPath, Checksums.Measured> references)
      throws IOException {
    Map<SidecarPath, Checksums.Measured> baseRefs =
        base == null ? Map.of() : references(base.document());
    Set<SidecarPath> stagedTargets = new HashSet<>();
    for (TransactionRecord.Entry entry : staged) {
      stagedTargets.add(entry.target());
      Checksums.Measured claimed = references.get(entry.target());
      if (claimed == null) {
        throw new StorageException(
            StorageIssueCodes.TRANSACTION_INVALID,
            entry.target() + " was staged but the new manifest does not reference it");
      }
      if (!claimed.checksum().equals(entry.checksum())
          || claimed.sizeBytes() != entry.sizeBytes()) {
        throw new StorageException(
            StorageIssueCodes.CHECKSUM_MISMATCH,
            "The manifest's checksum or size for "
                + entry.target()
                + " does not match the staged content");
      }
    }
    for (Map.Entry<SidecarPath, Checksums.Measured> ref : references.entrySet()) {
      if (stagedTargets.contains(ref.getKey())
          || ref.getValue().equals(baseRefs.get(ref.getKey()))) {
        continue; // staged now, or unchanged since it was verified at its own commit
      }
      Path file = root.resolve(ref.getKey(), fs);
      Checksums.Measured actual =
          fs.isRegularFile(file) ? store.measureFile(file, ref.getValue().sizeBytes()) : null;
      if (!ref.getValue().equals(actual)) {
        throw new StorageException(
            StorageIssueCodes.REFERENCE_UNVERIFIED,
            ref.getKey() + " is missing or does not match its checksum and size");
      }
    }
  }

  private void ensureParents(Path target) throws IOException {
    List<Path> missing = new ArrayList<>();
    for (Path p = target.getParent();
        p != null && !p.equals(root.path()) && !fs.exists(p);
        p = p.getParent()) {
      missing.add(0, p);
    }
    for (Path p : missing) {
      fs.createDirectory(p);
      syncDir(p.getParent());
    }
  }

  // --- durability helpers --------------------------------------------------------------------

  private void writeRecord(TransactionRecord.Phase phase) throws IOException {
    TransactionRecord record =
        new TransactionRecord(
            id,
            phase,
            baseChecksum,
            base == null ? null : base.document().revision(),
            base == null ? 0 : base.document().revision() + 1,
            staged,
            phase == TransactionRecord.Phase.PREPARED ? targetManifest : null);
    Path file = dir.resolve(TransactionRecord.FILE_NAME);
    if (phase == TransactionRecord.Phase.STAGING) {
      writeDurably(file, record.encode());
    } else {
      Path temp = dir.resolve(TransactionRecord.FILE_NAME + ".tmp");
      writeDurably(temp, record.encode());
      fs.moveAtomically(temp, file);
    }
    syncDir(dir);
  }

  private void writeDurably(Path file, byte[] bytes) throws IOException {
    try (StorageFileSystem.WritableFile out = fs.createNew(file)) {
      out.write(bytes, 0, bytes.length);
      out.sync();
    }
  }

  /** Syncs a directory; records (rather than fails on) platforms that cannot. */
  private void syncDir(@Nullable Path directory) throws IOException {
    if (directory == null) {
      return;
    }
    try {
      fs.syncDirectory(directory);
    } catch (UnsupportedOperationException e) {
      directorySyncSupported = false;
    }
  }

  // --- failure handling ----------------------------------------------------------------------

  private void ensureOpen() throws StorageException {
    if (state != State.OPEN) {
      throw new StorageException(StorageIssueCodes.TRANSACTION_INVALID, "Transaction is " + state);
    }
  }

  /**
   * True if the manifest on disk is already the target, so an error came after the commit point.
   */
  private boolean committedDespite() {
    Checksum target = targetManifest;
    if (target == null) {
      return false;
    }
    try {
      return target.equals(
          SidecarStore.measure(
              store.readBytes(
                  root.internal(SidecarRoot.MANIFEST), store.limits().maxDocumentBytes())));
    } catch (IOException e) {
      return false;
    }
  }

  private StorageException failAndRollBack(Exception cause) {
    state = State.FAILED;
    StorageException failure = toStorage(cause);
    try {
      rollBack();
    } catch (IOException | RuntimeException rollbackFailure) {
      failure.addSuppressed(rollbackFailure);
    }
    return failure;
  }

  private void rollBack() throws IOException {
    for (int i = movedTargets.size() - 1; i >= 0; i--) {
      Path target = movedTargets.get(i);
      if (fs.exists(target)) {
        fs.delete(
            target); // placed by this transaction and unreferenced: the manifest was not replaced
      }
    }
    movedTargets.clear();
    if (fs.exists(dir)) {
      removeStagingDirectory(fs, dir);
    }
    Path staging = root.internal(SidecarRoot.STAGING);
    if (fs.isDirectory(staging) && fs.list(staging).isEmpty()) {
      fs.delete(staging);
    }
  }

  /**
   * Removes a staging directory, deleting {@code transaction.json} last so a partial removal stays
   * explained.
   */
  static void removeStagingDirectory(StorageFileSystem fs, Path dir) throws IOException {
    Path record = dir.resolve(TransactionRecord.FILE_NAME);
    for (Path entry : fs.list(dir)) {
      if (!entry.equals(record)) {
        fs.delete(entry);
      }
    }
    fs.checkpoint(CommitStage.DURING_CLEANUP, dir);
    if (fs.exists(record)) {
      fs.delete(record);
    }
    fs.delete(dir);
  }

  private static StorageException toStorage(Exception e) {
    if (e instanceof StorageException se) {
      return se;
    }
    if (e instanceof AtomicMoveNotSupportedException) {
      return new StorageException(
          StorageIssueCodes.ATOMIC_MOVE_UNSUPPORTED,
          "The filesystem cannot rename atomically; refusing to write rather than risk a torn manifest",
          e);
    }
    return new StorageException(StorageIssueCodes.IO_FAILURE, String.valueOf(e.getMessage()), e);
  }
}
