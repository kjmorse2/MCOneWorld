// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.world.nbt;

import io.github.kjmorse2.mconeworld.tool.world.WorldIssueCodes;
import io.github.kjmorse2.mconeworld.tool.world.nbt.NbtTag.NbtByte;
import io.github.kjmorse2.mconeworld.tool.world.nbt.NbtTag.NbtByteArray;
import io.github.kjmorse2.mconeworld.tool.world.nbt.NbtTag.NbtCompound;
import io.github.kjmorse2.mconeworld.tool.world.nbt.NbtTag.NbtDouble;
import io.github.kjmorse2.mconeworld.tool.world.nbt.NbtTag.NbtFloat;
import io.github.kjmorse2.mconeworld.tool.world.nbt.NbtTag.NbtInt;
import io.github.kjmorse2.mconeworld.tool.world.nbt.NbtTag.NbtIntArray;
import io.github.kjmorse2.mconeworld.tool.world.nbt.NbtTag.NbtList;
import io.github.kjmorse2.mconeworld.tool.world.nbt.NbtTag.NbtLong;
import io.github.kjmorse2.mconeworld.tool.world.nbt.NbtTag.NbtLongArray;
import io.github.kjmorse2.mconeworld.tool.world.nbt.NbtTag.NbtShort;
import io.github.kjmorse2.mconeworld.tool.world.nbt.NbtTag.NbtString;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UTFDataFormatException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;
import org.jspecify.annotations.Nullable;

/**
 * Reads one named root compound, in Java edition's big-endian NBT, either gzip-compressed (as
 * {@code level.dat} and the {@code .dat} saved-data files are) or uncompressed. Strict and bounded:
 *
 * <ul>
 *   <li>The root must be a compound, and nothing may follow it.
 *   <li>A compound may not repeat a key, and a list of type End must be empty.
 *   <li>Strings are modified UTF-8 and must decode.
 *   <li>Decompressed size and nesting depth are capped by {@link NbtLimits}. Every list or array
 *       length is checked against the remaining byte budget before allocation, so a small file
 *       cannot make the reader allocate a large one.
 * </ul>
 *
 * Any violation is an {@link NbtException}; nothing else escapes except plain {@link IOException}s.
 */
public final class NbtReader {
  private static final int GZIP_MAGIC = 0x1f8b;

  private final DataInputStream in;
  private final BoundedInputStream counter;
  private final NbtLimits limits;

  private NbtReader(BoundedInputStream counter, NbtLimits limits) {
    this.counter = counter;
    this.in = new DataInputStream(counter);
    this.limits = limits;
  }

  /**
   * Reads {@code file}, which must be a regular file and not a symbolic link.
   *
   * @throws NbtException if the content is malformed or exceeds {@code limits}
   * @throws IOException if the file cannot be read
   */
  public static NbtCompound readFile(Path file, NbtLimits limits) throws IOException {
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Not a regular file: " + file.getFileName());
    }
    try (InputStream raw = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
      return read(raw, limits);
    }
  }

  /**
   * Reads one root compound from {@code raw}, detecting gzip compression. The stream is read to its
   * end but not closed.
   *
   * @throws NbtException if the content is malformed or exceeds {@code limits}
   */
  public static NbtCompound read(InputStream raw, NbtLimits limits) throws IOException {
    BufferedInputStream buffered = new BufferedInputStream(raw);
    buffered.mark(2);
    int first = buffered.read();
    int second = buffered.read();
    buffered.reset();
    InputStream source = buffered;
    if (first >= 0 && second >= 0 && ((first << 8) | second) == GZIP_MAGIC) {
      try {
        source = new GZIPInputStream(buffered);
      } catch (ZipException | EOFException e) {
        throw malformed("invalid or truncated gzip header", e);
      }
    }
    NbtReader reader = new NbtReader(new BoundedInputStream(source, limits.maxBytes()), limits);
    try {
      return reader.readRoot();
    } catch (EOFException e) {
      throw malformed("the data ends early", e);
    } catch (UTFDataFormatException e) {
      throw malformed("a string is not valid modified UTF-8", e);
    } catch (ZipException e) {
      throw malformed("corrupt compressed data", e);
    }
  }

  private NbtCompound readRoot() throws IOException {
    int type = in.readUnsignedByte();
    if (type != 10) {
      throw malformed("the root tag is type " + type + ", not a compound", null);
    }
    in.readUTF(); // the root's name, conventionally empty
    NbtCompound root = readCompound(1);
    if (counter.read() != -1) {
      throw malformed("bytes follow the root compound", null);
    }
    return root;
  }

  private NbtTag readPayload(int type, int depth) throws IOException {
    return switch (type) {
      case 1 -> new NbtByte(in.readByte());
      case 2 -> new NbtShort(in.readShort());
      case 3 -> new NbtInt(in.readInt());
      case 4 -> new NbtLong(in.readLong());
      case 5 -> new NbtFloat(in.readFloat());
      case 6 -> new NbtDouble(in.readDouble());
      case 7 -> {
        byte[] values = new byte[length(1)];
        in.readFully(values);
        yield new NbtByteArray(values);
      }
      case 8 -> new NbtString(in.readUTF());
      case 9 -> readList(depth + 1);
      case 10 -> readCompound(depth + 1);
      case 11 -> {
        int[] values = new int[length(4)];
        for (int i = 0; i < values.length; i++) {
          values[i] = in.readInt();
        }
        yield new NbtIntArray(values);
      }
      case 12 -> {
        long[] values = new long[length(8)];
        for (int i = 0; i < values.length; i++) {
          values[i] = in.readLong();
        }
        yield new NbtLongArray(values);
      }
      default -> throw malformed("unknown tag type " + type, null);
    };
  }

  private NbtList readList(int depth) throws IOException {
    checkDepth(depth);
    int elementType = in.readUnsignedByte();
    int count = length(minimumSize(elementType));
    if (elementType == 0) {
      if (count != 0) {
        throw malformed("a list of type End has " + count + " elements", null);
      }
      return new NbtList(0, List.of());
    }
    List<NbtTag> values = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      values.add(readPayload(elementType, depth));
    }
    return new NbtList(elementType, values);
  }

  private NbtCompound readCompound(int depth) throws IOException {
    checkDepth(depth);
    Map<String, NbtTag> members = new LinkedHashMap<>();
    while (true) {
      int type = in.readUnsignedByte();
      if (type == 0) {
        return new NbtCompound(members);
      }
      String name = in.readUTF();
      if (members.containsKey(name)) {
        throw malformed("duplicate compound key \"" + name + "\"", null);
      }
      members.put(name, readPayload(type, depth));
    }
  }

  /** A length prefix, checked against the bytes left in the budget for its elements. */
  private int length(int elementBytes) throws IOException {
    int count = in.readInt();
    if (count < 0) {
      throw malformed("negative length " + count, null);
    }
    if ((long) count * elementBytes > counter.remaining()) {
      throw new NbtException(
          WorldIssueCodes.NBT_TOO_LARGE,
          "a length of " + count + " exceeds the " + limits.maxBytes() + "-byte limit");
    }
    return count;
  }

  private void checkDepth(int depth) throws NbtException {
    if (depth > limits.maxDepth()) {
      throw new NbtException(
          WorldIssueCodes.NBT_TOO_LARGE, "nesting deeper than " + limits.maxDepth());
    }
  }

  /** The fewest bytes one payload of {@code type} can occupy; 0 only for type End. */
  private static int minimumSize(int type) throws NbtException {
    return switch (type) {
      case 0 -> 0;
      case 1, 10 -> 1;
      case 2, 8 -> 2;
      case 3, 5, 7, 11, 12 -> 4;
      case 4, 6 -> 8;
      case 9 -> 5;
      default -> throw malformed("unknown list element type " + type, null);
    };
  }

  private static NbtException malformed(String message, @Nullable Throwable cause) {
    return new NbtException(WorldIssueCodes.NBT_MALFORMED, "Malformed NBT: " + message, cause);
  }

  /** Counts bytes and fails once more than the budget has been read. */
  private static final class BoundedInputStream extends FilterInputStream {
    private final long max;
    private long count;

    BoundedInputStream(InputStream in, long max) {
      super(in);
      this.max = max;
    }

    long remaining() {
      return max - count;
    }

    @Override
    public int read() throws IOException {
      int b = super.read();
      if (b >= 0) {
        add(1);
      }
      return b;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int n = super.read(buffer, offset, (int) Math.min(length, Math.max(1, remaining() + 1)));
      if (n > 0) {
        add(n);
      }
      return n;
    }

    private void add(long n) throws NbtException {
      count += n;
      if (count > max) {
        throw new NbtException(WorldIssueCodes.NBT_TOO_LARGE, "more than " + max + " bytes of NBT");
      }
    }
  }
}
