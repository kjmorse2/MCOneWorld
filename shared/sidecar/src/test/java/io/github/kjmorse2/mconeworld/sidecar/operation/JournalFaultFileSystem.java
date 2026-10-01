// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.operation;

import io.github.kjmorse2.mconeworld.sidecar.storage.JdkStorageFileSystem;
import io.github.kjmorse2.mconeworld.sidecar.storage.StorageFileSystem;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serial;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fault injection for journal appends: tear the next append (write only a prefix, then "crash"),
 * fail the next sync, and count every mutating call.
 */
final class JournalFaultFileSystem implements StorageFileSystem {
  /** Thrown to simulate the process dying; nothing after it runs. */
  static final class Crash extends RuntimeException {
    @Serial private static final long serialVersionUID = 1L;

    Crash() {
      super("simulated crash");
    }
  }

  private final StorageFileSystem real = JdkStorageFileSystem.INSTANCE;
  final AtomicInteger mutations = new AtomicInteger();

  /** If non-negative, the next append writes only this many bytes, then crashes. */
  int tearNextAppendAfter = -1;

  /** If true, the next append's sync fails with an IOException after writing everything. */
  boolean failNextAppendSync;

  @Override
  public WritableFile openAppend(Path file) throws IOException {
    mutations.incrementAndGet();
    WritableFile delegate = real.openAppend(file);
    return new WritableFile() {
      @Override
      public void write(byte[] bytes, int offset, int length) throws IOException {
        int tear = tearNextAppendAfter;
        if (tear >= 0) {
          tearNextAppendAfter = -1;
          delegate.write(bytes, offset, Math.min(tear, length));
          delegate.sync();
          delegate.close();
          throw new Crash();
        }
        delegate.write(bytes, offset, length);
      }

      @Override
      public void sync() throws IOException {
        if (failNextAppendSync) {
          failNextAppendSync = false;
          throw new IOException("injected sync failure");
        }
        delegate.sync();
      }

      @Override
      public void close() throws IOException {
        delegate.close();
      }
    };
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
    return real.createNew(file);
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
  public void moveAtomically(Path source, Path target) throws IOException {
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
