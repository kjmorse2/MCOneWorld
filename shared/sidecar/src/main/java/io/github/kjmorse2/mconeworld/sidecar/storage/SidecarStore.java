// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.storage;

import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.sidecar.manifest.DecodedManifest;
import io.github.kjmorse2.mconeworld.sidecar.manifest.FileReference;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestCodec;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestException;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestLimits;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Entry point for one sidecar directory.
 *
 * <p><b>Reading</b> ({@link #readManifest()}, {@link #inspect()}) is strictly read-only. It
 * creates, modifies, renames, and deletes nothing, not even for a damaged or non-canonical sidecar.
 *
 * <p><b>Writing</b> requires {@link #openWriter}, which holds the exclusive writer lock. Every
 * change is one {@link SidecarTransaction} whose commit point is a single atomic rename of {@code
 * manifest.json}. The previous manifest is kept as {@code manifest.previous.json}. Protocol,
 * durability, and recovery rules: {@code docs/formats/sidecar-storage-v1.md} and ADR 0004.
 */
public final class SidecarStore {
  private final SidecarRoot root;
  private final ManifestCodec codec;
  private final ManifestLimits limits;
  private final StorageFileSystem fs;

  private SidecarStore(
      SidecarRoot root, ManifestCodec codec, ManifestLimits limits, StorageFileSystem fs) {
    this.root = root;
    this.codec = codec;
    this.limits = limits;
    this.fs = fs;
  }

  /** Opens a store over {@code root} with the production filesystem and default limits. */
  public static SidecarStore open(SidecarRoot root) {
    return open(root, ManifestLimits.DEFAULT, JdkStorageFileSystem.INSTANCE);
  }

  /** Opens a store with explicit limits and filesystem (the fault-injection seam). */
  public static SidecarStore open(SidecarRoot root, ManifestLimits limits, StorageFileSystem fs) {
    return new SidecarStore(
        Objects.requireNonNull(root, "root"),
        new ManifestCodec(limits),
        limits,
        Objects.requireNonNull(fs, "fs"));
  }

  public SidecarRoot root() {
    return root;
  }

  /** Returns whether {@code manifest.json} exists. */
  public boolean hasManifest() {
    return fs.exists(root.internal(SidecarRoot.MANIFEST));
  }

  /**
   * Reads and fully validates {@code manifest.json}. Read-only.
   *
   * @throws ManifestException if the manifest is invalid
   * @throws IOException if it cannot be read
   */
  public DecodedManifest readManifest() throws IOException, ManifestException {
    return codec.decode(readBytes(root.internal(SidecarRoot.MANIFEST), limits.maxDocumentBytes()));
  }

  /**
   * Returns the exact bytes of {@code manifest.json}, bounded by the manifest size limit, without
   * decoding them. Read-only. Used, for example, to plan migrations of legacy manifests that the
   * current codec cannot decode.
   */
  public byte[] readManifestBytes() throws IOException {
    return readBytes(root.internal(SidecarRoot.MANIFEST), limits.maxDocumentBytes());
  }

  /** Reads {@code manifest.previous.json}, the last-known-good fallback. Read-only. */
  public DecodedManifest readPreviousManifest() throws IOException, ManifestException {
    return codec.decode(
        readBytes(root.internal(SidecarRoot.PREVIOUS_MANIFEST), limits.maxDocumentBytes()));
  }

  /**
   * Acquires the writer lock and returns a writer. Close the writer to release the lock.
   *
   * @throws LockException if another writer holds the lock, or it is stale and the policy refuses
   */
  public SidecarWriter openWriter(LockOwner owner, Clock clock, StaleLockPolicy policy)
      throws StorageException {
    return new SidecarWriter(this, SidecarLock.acquire(root, owner, clock, policy));
  }

  /**
   * Classifies everything an interruption may have left behind. It is read-only, and it is always
   * safe to call, with or without the lock.
   */
  public RecoveryReport inspect() throws IOException {
    List<RecoveryFinding> findings = new ArrayList<>();
    Path manifestFile = root.internal(SidecarRoot.MANIFEST);
    @Nullable Checksum current = null;
    Set<SidecarPath> referenced = new HashSet<>();
    if (fs.exists(manifestFile)) {
      try {
        byte[] bytes = readBytes(manifestFile, limits.maxDocumentBytes());
        current = measure(bytes);
        DecodedManifest decoded = codec.decode(bytes);
        referenced = referencedPaths(decoded);
      } catch (IOException | ManifestException e) {
        findings.add(
            new RecoveryFinding(
                StorageIssueCodes.RECOVERY_MANIFEST_UNREADABLE,
                "",
                "manifest.json cannot be read or is invalid ("
                    + e.getMessage()
                    + "); manifest.previous.json "
                    + (fs.exists(root.internal(SidecarRoot.PREVIOUS_MANIFEST))
                        ? "exists"
                        : "does not exist"),
                List.of(),
                false));
      }
    }
    Path staging = root.internal(SidecarRoot.STAGING);
    if (fs.isDirectory(staging)) {
      for (Path entry : fs.list(staging)) {
        findings.add(classify(entry, current, referenced));
      }
    } else if (fs.exists(staging)) {
      findings.add(
          new RecoveryFinding(
              StorageIssueCodes.RECOVERY_UNEXPLAINED,
              SidecarRoot.STAGING,
              ".staging exists but is not a directory",
              List.of(),
              false));
    }
    return new RecoveryReport(findings, SidecarLock.inspect(root));
  }

  private RecoveryFinding classify(
      Path entry, @Nullable Checksum current, Set<SidecarPath> referenced) throws IOException {
    String name = entry.getFileName().toString();
    Path recordFile = entry.resolve(TransactionRecord.FILE_NAME);
    TransactionRecord record = null;
    if (fs.isDirectory(entry) && fs.isRegularFile(recordFile)) {
      try {
        record = TransactionRecord.decode(readBytes(recordFile, TransactionRecord.MAX_BYTES));
      } catch (StorageException e) {
        record = null;
      }
    }
    if (record == null && fs.isDirectory(entry) && fs.list(entry).isEmpty()) {
      // A crash between creating the directory and writing its record: nothing to explain.
      return new RecoveryFinding(
          StorageIssueCodes.RECOVERY_ABORTED_CLEANUP,
          name,
          "Empty staging directory from a transaction interrupted before it recorded its intent",
          List.of(),
          true);
    }
    if (record == null || !record.id().toString().equals(name)) {
      return new RecoveryFinding(
          StorageIssueCodes.RECOVERY_UNEXPLAINED,
          name,
          "Staging entry without a readable transaction record; never removed automatically. Inspect it, then delete it by hand if it is not needed.",
          List.of(),
          false);
    }
    boolean atBase = Objects.equals(current, record.baseManifest());
    if (record.phase() == TransactionRecord.Phase.PREPARED
        && current != null
        && current.equals(record.targetManifest())) {
      return new RecoveryFinding(
          StorageIssueCodes.RECOVERY_COMMITTED_CLEANUP,
          name,
          "Transaction committed revision "
              + record.targetRevision()
              + " but its staging directory remains",
          List.of(),
          true);
    }
    if (atBase) {
      List<SidecarPath> orphans = new ArrayList<>();
      if (record.phase() == TransactionRecord.Phase.PREPARED) {
        for (TransactionRecord.Entry e : record.files()) {
          if (!referenced.contains(e.target()) && matches(e)) {
            orphans.add(e.target());
          }
        }
      }
      return new RecoveryFinding(
          StorageIssueCodes.RECOVERY_ABORTED_CLEANUP,
          name,
          "Transaction did not reach its commit point; the manifest is unchanged",
          orphans,
          true);
    }
    return new RecoveryFinding(
        StorageIssueCodes.RECOVERY_OUTCOME_UNKNOWN,
        name,
        "manifest.json matches neither this transaction's base nor its target; review manually",
        List.of(),
        false);
  }

  /** Returns whether the file at {@code e.target()} exists and matches the recorded content. */
  boolean matches(TransactionRecord.Entry e) throws IOException {
    Path target = root.resolve(e.target(), fs);
    if (!fs.isRegularFile(target)) {
      return false;
    }
    Checksums.Measured m = measureFile(target, e.sizeBytes());
    return m != null && m.checksum().equals(e.checksum()) && m.sizeBytes() == e.sizeBytes();
  }

  static Set<SidecarPath> referencedPaths(DecodedManifest decoded) {
    Set<SidecarPath> paths = new HashSet<>();
    for (FileReference f : decoded.document().files()) {
      paths.add(f.path());
    }
    paths.addAll(decoded.document().backupPaths().values());
    return paths;
  }

  // --- helpers shared with the writer --------------------------------------------------------

  StorageFileSystem fs() {
    return fs;
  }

  ManifestCodec codec() {
    return codec;
  }

  ManifestLimits limits() {
    return limits;
  }

  byte[] readBytes(Path file, long maxBytes) throws IOException {
    try (InputStream in = fs.openRead(file)) {
      return Checksums.readBounded(in, maxBytes);
    }
  }

  /** Measures a file, returning null if it holds more than {@code expectedSize} bytes. */
  Checksums.@Nullable Measured measureFile(Path file, long expectedSize) throws IOException {
    try (InputStream in = fs.openRead(file)) {
      return Checksums.sha256(in, expectedSize);
    } catch (StorageException e) {
      if (e.code().equals(StorageIssueCodes.TOO_LARGE)) {
        return null;
      }
      throw e;
    }
  }

  static Checksum measure(byte[] bytes) {
    try {
      return Checksums.sha256(new java.io.ByteArrayInputStream(bytes), bytes.length).checksum();
    } catch (IOException e) {
      throw new IllegalStateException("In-memory stream cannot fail", e);
    }
  }
}
