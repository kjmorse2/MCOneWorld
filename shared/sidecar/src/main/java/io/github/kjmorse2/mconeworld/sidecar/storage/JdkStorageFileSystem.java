// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Stream;

/** The production {@link StorageFileSystem}, built on {@code java.nio.file}. */
public final class JdkStorageFileSystem implements StorageFileSystem {
  /** Shared, stateless instance. */
  public static final JdkStorageFileSystem INSTANCE = new JdkStorageFileSystem();

  private JdkStorageFileSystem() {}

  @Override
  public boolean exists(Path path) {
    return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
  }

  @Override
  public boolean isSymbolicLink(Path path) {
    return Files.isSymbolicLink(path);
  }

  @Override
  public boolean isRegularFile(Path path) {
    return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
  }

  @Override
  public boolean isDirectory(Path path) {
    return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
  }

  @Override
  public void createDirectory(Path dir) throws IOException {
    Files.createDirectory(dir);
  }

  @Override
  public WritableFile createNew(Path file) throws IOException {
    FileChannel channel =
        FileChannel.open(
            file,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS);
    return new WritableFile() {
      @Override
      public void write(byte[] bytes, int offset, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, length);
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
      }

      @Override
      public void sync() throws IOException {
        channel.force(true);
      }

      @Override
      public void close() throws IOException {
        channel.close();
      }
    };
  }

  @Override
  public InputStream openRead(Path file) throws IOException {
    return Channels.newInputStream(
        FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
  }

  @Override
  public void moveAtomically(Path source, Path target) throws IOException {
    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
  }

  @Override
  public void delete(Path path) throws IOException {
    Files.delete(path);
  }

  @Override
  public List<Path> list(Path dir) throws IOException {
    try (Stream<Path> entries = Files.list(dir)) {
      return entries.sorted().toList();
    }
  }

  @Override
  public void syncDirectory(Path dir) throws IOException {
    FileChannel channel;
    try {
      channel = FileChannel.open(dir, StandardOpenOption.READ);
    } catch (IOException e) {
      // Some platforms (notably Windows) cannot open a directory as a channel.
      throw new UnsupportedOperationException("Directory sync is not supported here", e);
    }
    try (channel) {
      channel.force(true);
    }
  }

  @Override
  public Path toRealPath(Path path) throws IOException {
    return path.toRealPath();
  }
}
