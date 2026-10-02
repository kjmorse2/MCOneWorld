// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import io.github.kjmorse2.mconeworld.sidecar.storage.JdkStorageFileSystem;
import io.github.kjmorse2.mconeworld.sidecar.storage.StorageFileSystem;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A real filesystem that numbers every mutating step (create, write, sync, append, move, delete,
 * directory creation and sync) and can make step {@code failAtStep} fail. A failing write may first
 * write a prefix of its bytes, which is what a crash in the middle of a write leaves behind. It
 * also counts syncs and positioned reads, which the resource tests use as deterministic stand-ins
 * for time.
 */
final class IndexFaultFileSystem implements StorageFileSystem {
  private final StorageFileSystem real = JdkStorageFileSystem.INSTANCE;

  /** Steps seen so far; the next mutating step is {@code steps + 1}. */
  int steps;

  /** The step that fails (1-based), or 0 for none. */
  int failAtStep;

  /** If the failing step is a write, how many of its bytes reach the file first (-1: none). */
  int tearBytes = -1;

  final List<String> stepLog = new ArrayList<>();
  final AtomicInteger dataSyncs = new AtomicInteger();
  final AtomicInteger fullSyncs = new AtomicInteger();
  final AtomicInteger positionedReads = new AtomicInteger();
  final AtomicInteger openHandles = new AtomicInteger();

  private void step(String what) throws IOException {
    steps++;
    stepLog.add(steps + " " + what);
    if (steps == failAtStep) {
      throw new IOException("injected failure at step " + steps + " (" + what + ")");
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
    step("mkdir " + dir.getFileName());
    real.createDirectory(dir);
  }

  @Override
  public WritableFile createNew(Path file) throws IOException {
    step("create " + file.getFileName());
    return track(file, real.createNew(file));
  }

  @Override
  public WritableFile openAppend(Path file) throws IOException {
    step("append-open " + file.getFileName());
    return track(file, real.openAppend(file));
  }

  private WritableFile track(Path file, WritableFile delegate) {
    openHandles.incrementAndGet();
    return new WritableFile() {
      private boolean closed;

      @Override
      public void write(byte[] bytes, int offset, int length) throws IOException {
        steps++;
        stepLog.add(steps + " write " + file.getFileName() + " " + length);
        if (steps == failAtStep) {
          if (tearBytes > 0) {
            delegate.write(bytes, offset, Math.min(tearBytes, length));
          }
          throw new IOException("injected write failure at step " + steps);
        }
        delegate.write(bytes, offset, length);
      }

      @Override
      public void sync() throws IOException {
        step("sync " + file.getFileName());
        fullSyncs.incrementAndGet();
        delegate.sync();
      }

      @Override
      public void syncData() throws IOException {
        step("datasync " + file.getFileName());
        dataSyncs.incrementAndGet();
        delegate.syncData();
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
  public long size(Path file) throws IOException {
    return real.size(file);
  }

  @Override
  public InputStream openRead(Path file) throws IOException {
    return real.openRead(file);
  }

  @Override
  public ReadableFile openRandomAccess(Path file) throws IOException {
    ReadableFile delegate = real.openRandomAccess(file);
    openHandles.incrementAndGet();
    return new ReadableFile() {
      private boolean closed;

      @Override
      public long size() throws IOException {
        return delegate.size();
      }

      @Override
      public void readFully(long position, byte[] target, int offset, int length)
          throws IOException {
        positionedReads.incrementAndGet();
        delegate.readFully(position, target, offset, length);
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
  public void moveAtomically(Path source, Path target) throws IOException {
    step("move " + source.getFileName() + " -> " + target.getFileName());
    real.moveAtomically(source, target);
  }

  @Override
  public void delete(Path path) throws IOException {
    step("delete " + path.getFileName());
    real.delete(path);
  }

  @Override
  public List<Path> list(Path dir) throws IOException {
    return real.list(dir);
  }

  @Override
  public void syncDirectory(Path dir) throws IOException {
    step("dirsync " + dir.getFileName());
    real.syncDirectory(dir);
  }

  @Override
  public Path toRealPath(Path path) throws IOException {
    return real.toRealPath(path);
  }
}
