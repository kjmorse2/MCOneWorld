// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.storage;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * The exclusive sidecar writer lock. At most one writer holds it at a time, across threads and
 * processes. It is released by {@link #close()}.
 *
 * <h2>Mechanism</h2>
 *
 * <ul>
 *   <li>{@code writer.lock} is a permanent, empty <em>lock target</em>. It is created once and
 *       never deleted, so its identity (inode) can never change underneath a holder. Holding the
 *       lock means holding an operating-system {@link FileLock} on it. The OS releases that lock
 *       automatically when the holding process dies.
 *   <li>{@code writer.owner.json} holds the owner's {@link LockInfo}. It is written atomically
 *       (temporary file, then rename) <em>after</em> the OS lock is acquired, and deleted
 *       <em>before</em> the lock is released.
 *   <li>So: OS lock free <em>and</em> owner file present means the owner died while holding the
 *       lock. That is the only definition of <b>provably stale</b>, and nothing is guessed from
 *       timestamps or pids (see {@link StaleLockPolicy}).
 * </ul>
 *
 * <h2>Why a JVM-wide registry</h2>
 *
 * <p>On POSIX systems, Java file locks are {@code fcntl} record locks. They belong to the
 * <em>process</em>, and closing <em>any</em> descriptor of the file releases them, even a
 * descriptor opened only to read or probe. So within a JVM that holds the lock, {@code writer.lock}
 * must never be opened again. A static registry of held lock targets answers "held by this JVM?"
 * without touching the file, both for a second {@link #acquire} and for {@link #inspect}. The
 * cross-process tests in {@code SidecarLockTest} guard this property.
 *
 * <p>Tested on Linux local filesystems. OS locks on network filesystems (NFS, SMB) are unreliable,
 * so do not place a sidecar on one; see {@code docs/formats/sidecar-storage-v1.md}.
 */
public final class SidecarLock implements AutoCloseable {
  private static final int MAX_OWNER_FILE_BYTES = 4096;

  /** Lock targets (real paths) held by this JVM. The value is a holder, or a reservation. */
  private static final Map<Path, Object> HELD_IN_THIS_JVM = new ConcurrentHashMap<>();

  private final Path lockTarget;
  private final Path ownerFile;
  private final FileChannel channel;
  private final FileLock osLock;
  private final LockInfo info;
  private boolean released;

  private SidecarLock(
      Path lockTarget, Path ownerFile, FileChannel channel, FileLock osLock, LockInfo info) {
    this.lockTarget = lockTarget;
    this.ownerFile = ownerFile;
    this.channel = channel;
    this.osLock = osLock;
    this.info = info;
  }

  /**
   * Acquires the writer lock.
   *
   * @throws LockException {@code LOCK_CONTENDED} if a live writer holds it, or if the recorded
   *     owner is unreadable. {@code LOCK_STALE} if it is provably stale and {@code policy} is
   *     {@link StaleLockPolicy#REFUSE}.
   * @throws StorageException for I/O failures
   */
  public static SidecarLock acquire(
      SidecarRoot root, LockOwner owner, Clock clock, StaleLockPolicy policy)
      throws StorageException {
    Path lockTarget = root.internal(SidecarRoot.LOCK);
    Path ownerFile = root.internal(SidecarRoot.LOCK_OWNER);
    Object reservation = new Object();
    if (HELD_IN_THIS_JVM.putIfAbsent(lockTarget, reservation) != null) {
      throw new LockException(
          StorageIssueCodes.LOCK_CONTENDED,
          "The sidecar is locked by another writer in this process",
          readOwner(ownerFile));
    }
    FileChannel channel = null;
    boolean success = false;
    try {
      channel =
          FileChannel.open(
              lockTarget,
              StandardOpenOption.CREATE,
              StandardOpenOption.WRITE,
              LinkOption.NOFOLLOW_LINKS);
      FileLock osLock;
      try {
        osLock = channel.tryLock();
      } catch (OverlappingFileLockException e) {
        osLock = null;
      }
      if (osLock == null) {
        throw new LockException(
            StorageIssueCodes.LOCK_CONTENDED,
            "The sidecar is locked by another writer",
            readOwner(ownerFile));
      }
      // We hold the OS lock. Any owner file now belongs to a writer that died holding it.
      if (Files.exists(ownerFile, LinkOption.NOFOLLOW_LINKS)) {
        LockInfo previous = readOwner(ownerFile);
        if (previous == null) {
          throw new LockException(
              StorageIssueCodes.LOCK_CONTENDED,
              "writer.owner.json is unreadable, so the previous owner cannot be proven gone; if no MCOneWorld process is running, remove it by hand",
              null);
        }
        if (policy == StaleLockPolicy.REFUSE) {
          throw new LockException(
              StorageIssueCodes.LOCK_STALE,
              "The previous writer ("
                  + previous.tool()
                  + " pid "
                  + previous.pid()
                  + " on "
                  + previous.host()
                  + ") is gone but left its lock",
              previous);
        }
      }
      LockInfo info =
          new LockInfo(
              UUID.randomUUID(),
              owner.tool(),
              owner.toolVersion(),
              hostName(),
              ProcessHandle.current().pid(),
              clock.instant());
      writeOwner(ownerFile, info);
      SidecarLock lock = new SidecarLock(lockTarget, ownerFile, channel, osLock, info);
      HELD_IN_THIS_JVM.put(lockTarget, lock);
      success = true;
      return lock;
    } catch (StorageException e) {
      throw e;
    } catch (IOException e) {
      throw new StorageException(
          StorageIssueCodes.IO_FAILURE, "Cannot acquire the sidecar lock", e);
    } finally {
      if (!success) {
        // Closing our own channel releases only the lock we took through it.
        closeQuietly(channel);
        HELD_IN_THIS_JVM.remove(lockTarget, reservation);
      }
    }
  }

  /**
   * Read-only inspection. It never modifies anything, and never disturbs a lock held by this JVM.
   * Returns {@code null} if nobody holds the lock and no owner file remains.
   */
  public static @Nullable LockStatus inspect(SidecarRoot root) throws StorageException {
    Path lockTarget = root.internal(SidecarRoot.LOCK);
    Path ownerFile = root.internal(SidecarRoot.LOCK_OWNER);
    LockInfo info = readOwner(ownerFile);
    boolean ownerPresent = Files.exists(ownerFile, LinkOption.NOFOLLOW_LINKS);
    if (HELD_IN_THIS_JVM.containsKey(lockTarget)) {
      return new LockStatus(info, true);
    }
    if (!Files.exists(lockTarget, LinkOption.NOFOLLOW_LINKS)) {
      return ownerPresent ? new LockStatus(info, false) : null;
    }
    boolean held;
    try (FileChannel probe =
        FileChannel.open(lockTarget, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
      FileLock shared;
      try {
        shared = probe.tryLock(0, Long.MAX_VALUE, true);
      } catch (OverlappingFileLockException e) {
        shared = null;
      }
      held = shared == null;
      if (shared != null) {
        shared.release();
      }
    } catch (IOException e) {
      throw new StorageException(
          StorageIssueCodes.IO_FAILURE, "Cannot inspect the sidecar lock", e);
    }
    return held || ownerPresent ? new LockStatus(info, held) : null;
  }

  /** This acquisition's metadata. */
  public LockInfo info() {
    return info;
  }

  /**
   * Verifies that this writer still holds the lock: the OS lock is valid, and {@code
   * writer.owner.json} still names this acquisition. Call it before each mutation.
   *
   * @throws StorageException {@code LOCK_LOST} otherwise
   */
  public void verifyHeld() throws StorageException {
    if (released || !osLock.isValid()) {
      throw new StorageException(StorageIssueCodes.LOCK_LOST, "The writer lock has been released");
    }
    LockInfo current = readOwner(ownerFile);
    if (current == null || !current.lockId().equals(info.lockId())) {
      throw new StorageException(
          StorageIssueCodes.LOCK_LOST, "writer.owner.json was removed or replaced externally");
    }
  }

  /** Deletes the owner file (if it is still ours), then releases the OS lock. Idempotent. */
  @Override
  public void close() throws StorageException {
    if (released) {
      return;
    }
    released = true;
    IOException failure = null;
    try {
      LockInfo current = readOwner(ownerFile);
      if (current != null && current.lockId().equals(info.lockId())) {
        Files.deleteIfExists(ownerFile);
      }
    } catch (IOException e) {
      failure = e;
    }
    try {
      osLock.release();
    } catch (IOException e) {
      failure = failure == null ? e : failure;
    }
    closeQuietly(channel);
    HELD_IN_THIS_JVM.remove(lockTarget, this);
    if (failure != null) {
      throw new StorageException(
          StorageIssueCodes.IO_FAILURE,
          "Lock released, but writer.owner.json could not be removed; it now reads as provably stale",
          failure);
    }
  }

  /**
   * <b>Test seam only.</b> Simulates the owning process dying: the OS lock is released and the
   * channel closed, but {@code writer.owner.json} stays behind, exactly as after a crash.
   */
  void abandonLikeACrashedProcess() throws IOException {
    released = true;
    osLock.release();
    channel.close();
    HELD_IN_THIS_JVM.remove(lockTarget, this);
  }

  private static void writeOwner(Path ownerFile, LockInfo info) throws IOException {
    Path temp = ownerFile.resolveSibling(ownerFile.getFileName() + ".tmp");
    Files.deleteIfExists(temp); // a leftover can only come from a writer that died holding the lock
    try (FileChannel out =
        FileChannel.open(
            temp,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS)) {
      ByteBuffer buffer = ByteBuffer.wrap(LockFileFormat.encode(info));
      while (buffer.hasRemaining()) {
        out.write(buffer);
      }
      out.force(true);
    }
    Files.move(
        temp, ownerFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
  }

  /** Reads the owner file; returns null if it is absent, oversized, or unreadable. */
  static @Nullable LockInfo readOwner(Path ownerFile) {
    try {
      if (!Files.isRegularFile(ownerFile, LinkOption.NOFOLLOW_LINKS)
          || Files.size(ownerFile) > MAX_OWNER_FILE_BYTES) {
        return null;
      }
      return LockFileFormat.decode(Files.readAllBytes(ownerFile));
    } catch (IOException e) {
      return null;
    }
  }

  private static String hostName() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (IOException | RuntimeException e) {
      return "unknown";
    }
  }

  private static void closeQuietly(@Nullable FileChannel channel) {
    if (channel == null) {
      return;
    }
    try {
      channel.close();
    } catch (IOException ignored) {
      // Nothing useful can be done; the OS releases the handle at exit.
    }
  }
}
