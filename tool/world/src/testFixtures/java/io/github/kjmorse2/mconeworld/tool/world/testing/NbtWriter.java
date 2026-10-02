// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.world.testing;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Writes NBT for tests from plain Java values: {@link Byte}, {@link Short}, {@link Integer}, {@link
 * Long}, {@link Float}, {@link Double}, {@link String}, {@code byte[]}, {@code int[]}, {@code
 * long[]}, {@link List} (the element type comes from the first element; an empty list has type
 * End), and {@link Map} with string keys (a compound, in iteration order).
 */
public final class NbtWriter {
  private NbtWriter() {}

  /** A named root compound, uncompressed. */
  public static byte[] write(Map<String, ?> root) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      out.writeByte(10);
      out.writeUTF("");
      compound(out, root);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return bytes.toByteArray();
  }

  /** A named root compound, gzip-compressed as Minecraft writes {@code .dat} files. */
  public static byte[] writeGzip(Map<String, ?> root) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
      gzip.write(write(root));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return bytes.toByteArray();
  }

  static int id(Object value) {
    return switch (value) {
      case Byte b -> 1;
      case Short s -> 2;
      case Integer i -> 3;
      case Long l -> 4;
      case Float f -> 5;
      case Double d -> 6;
      case byte[] a -> 7;
      case String s -> 8;
      case List<?> l -> 9;
      case Map<?, ?> m -> 10;
      case int[] a -> 11;
      case long[] a -> 12;
      default -> throw new IllegalArgumentException("Not an NBT value: " + value.getClass());
    };
  }

  private static void payload(DataOutputStream out, Object value) throws IOException {
    switch (value) {
      case Byte b -> out.writeByte(b);
      case Short s -> out.writeShort(s);
      case Integer i -> out.writeInt(i);
      case Long l -> out.writeLong(l);
      case Float f -> out.writeFloat(f);
      case Double d -> out.writeDouble(d);
      case byte[] a -> {
        out.writeInt(a.length);
        out.write(a);
      }
      case String s -> out.writeUTF(s);
      case List<?> list -> {
        out.writeByte(list.isEmpty() ? 0 : id(list.getFirst()));
        out.writeInt(list.size());
        for (Object element : list) {
          payload(out, element);
        }
      }
      case Map<?, ?> map -> compound(out, map);
      case int[] a -> {
        out.writeInt(a.length);
        for (int v : a) {
          out.writeInt(v);
        }
      }
      case long[] a -> {
        out.writeInt(a.length);
        for (long v : a) {
          out.writeLong(v);
        }
      }
      default -> throw new IllegalArgumentException("Not an NBT value: " + value.getClass());
    }
  }

  private static void compound(DataOutputStream out, Map<?, ?> map) throws IOException {
    for (Map.Entry<?, ?> e : map.entrySet()) {
      Object value = e.getValue();
      out.writeByte(id(value));
      out.writeUTF((String) e.getKey());
      payload(out, value);
    }
    out.writeByte(0);
  }
}
