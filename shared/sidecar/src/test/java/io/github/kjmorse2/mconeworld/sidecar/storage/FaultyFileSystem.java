// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.storage;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/**
 * A {@link StorageFileSystem} over the real filesystem that can:
 *
 * <ul>
 *   <li>fail with an {@link IOException}, or crash with {@link SimulatedCrash}, at a chosen {@link
 *       CommitStage}
 *   <li>simulate a full disk after a number of written bytes
 *   <li>refuse atomic moves
 *   <li>count mutating operations and open handles
 * </ul>
 */
final class FaultyFileSystem implements StorageFileSystem {
  private final StorageFileSystem real = JdkStorageFileSystem.INSTANCE;

  @Nullable CommitStage failAt;
  boolean crash;
  long capacityBytes = Long.MAX_VALUE;
  boolean atomicMoveUnsupported;
  @Nullable StageAction onStage;

  final AtomicInteger mutations = new AtomicInteger();
  final AtomicInteger openHandles = new AtomicInteger();
  final List<String> stagesSeen = new ArrayList<>();
  private long written;

  /** A custom action to run at a stage (for example, corrupting a staged file). */
  interface StageAction {
    void run(CommitStage stage, @Nullable Path subject) throws IOException;
  }

  static FaultyFileSystem failingAt(CommitStage stage) {
    FaultyFileSystem fs = new FaultyFileSystem();
    fs.failAt = stage;
    return fs;
  }

  static FaultyFileSystem crashingAt(CommitStage stage) {
    FaultyFileSystem fs = failingAt(stage);
    fs.crash = true;
    return fs;
  }

  @Override
  public void checkpoint(CommitStage stage, @Nullable Path subject) throws IOException {
    stagesSeen.add(stage.name());
    StageAction action = onStage;
    if (action != null) {
      action.run(stage, subject);
    }
    if (stage == failAt) {
      failAt = null; // fire once
      if (crash) {
        throw new SimulatedCrash(stage.name());
      }
      throw new IOException("injected failure at " + stage);
    }
  }

  @Override
  public boolean exists(Path path) {
    return real.exists(path);
  }

  @Override
  public boolean isSymbolicLink(Path path) {
    return real.isSymbolicLink(path);
  }

  @Override
  public boolean isRegularFile(Path path) {
    return real.isRegularFile(path);
  }

  @Override
  public boolean isDirectory(Path path) {
    return real.isDirectory(path);
  }

  @Override
  public void createDirectory(Path dir) throws IOException {
    mutations.incrementAndGet();
    real.createDirectory(dir);
  }

  @Override
  public WritableFile createNew(Path file) throws IOException {
    mutations.incrementAndGet();
    return track(file, real.createNew(file));
  }

  @Override
  public WritableFile openAppend(Path file) throws IOException {
    mutations.incrementAndGet();
    return track(file, real.openAppend(file));
  }

  @Override
  public long size(Path file) throws IOException {
    return real.size(file);
  }

  /** Called before each write and sync of a file opened for writing; may throw. */
  interface WriteAction {
    void run(Path file, boolean sync) throws IOException;
  }

  @Nullable WriteAction onWrite;

  private WritableFile track(Path file, WritableFile delegate) {
    openHandles.incrementAndGet();
    return new WritableFile() {
      private boolean closed;

      @Override
      public void write(byte[] bytes, int offset, int length) throws IOException {
        WriteAction action = onWrite;
        if (action != null) {
          action.run(file, false);
        }
        if (written + length > capacityBytes) {
          throw new IOException("No space left on device (simulated)");
        }
        written += length;
        delegate.write(bytes, offset, length);
      }

      @Override
      public void sync() throws IOException {
        WriteAction action = onWrite;
        if (action != null) {
          action.run(file, true);
        }
        delegate.sync();
      }

      @Override
      public void close() throws IOException {
        if (!closed) {
          closed = true;
          openHandles.decrementAndGet();
        }
        delegate.close();
      }
    };
  }

  @Override
  public InputStream openRead(Path file) throws IOException {
    InputStream in = real.openRead(file);
    openHandles.incrementAndGet();
    return new FilterInputStream(in) {
      private boolean closed;

      @Override
      public void close() throws IOException {
        if (!closed) {
          closed = true;
          openHandles.decrementAndGet();
        }
        super.close();
      }
    };
  }

  @Override
  public ReadableFile openRandomAccess(Path file) throws IOException {
    return real.openRandomAccess(file);
  }

  @Override
  public void moveAtomically(Path source, Path target) throws IOException {
    if (atomicMoveUnsupported) {
      throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "simulated");
    }
    mutations.incrementAndGet();
    real.moveAtomically(source, target);
  }

  @Override
  public void delete(Path path) throws IOException {
    mutations.incrementAndGet();
    real.delete(path);
  }

  @Override
  public List<Path> list(Path dir) throws IOException {
    return real.list(dir);
  }

  @Override
  public void syncDirectory(Path dir) throws IOException {
    real.syncDirectory(dir);
  }

  @Override
  public Path toRealPath(Path path) throws IOException {
    return real.toRealPath(path);
  }
}
