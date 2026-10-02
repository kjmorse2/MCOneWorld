// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.world.nbt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import io.github.kjmorse2.mconeworld.tool.world.WorldIssueCodes;
import io.github.kjmorse2.mconeworld.tool.world.nbt.NbtTag.NbtCompound;
import io.github.kjmorse2.mconeworld.tool.world.testing.NbtWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

/** Every tag type, both encodings, and every rejection with its code. */
class NbtReaderTest {
  private static final NbtLimits SMALL = new NbtLimits(4096, 16);

  private static NbtCompound read(byte[] bytes, NbtLimits limits) throws IOException {
    return NbtReader.read(new ByteArrayInputStream(bytes), limits);
  }

  private static void rejected(byte[] bytes, NbtLimits limits, IssueCode code) {
    assertThatThrownBy(() -> read(bytes, limits))
        .isInstanceOfSatisfying(NbtException.class, e -> assertThat(e.code()).isEqualTo(code));
  }

  /** A root compound written by hand: tag 10, empty name, then {@code body}. */
  private static byte[] root(byte[] body) {
    return ByteBuffer.allocate(3 + body.length)
        .put((byte) 10)
        .putShort((short) 0)
        .put(body)
        .array();
  }

  @Test
  void everyTagTypeRoundTripsInBothEncodings() throws IOException {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("b", (byte) -1);
    data.put("s", (short) 300);
    data.put("i", -70_000);
    data.put("l", Long.MIN_VALUE);
    data.put("f", 1.5f);
    data.put("d", -2.25);
    data.put("ba", new byte[] {1, 2, 3});
    data.put("str", "Frühling ☃ 🌲");
    data.put("list", List.of(1, 2, 3));
    data.put("empty", List.of());
    data.put("nested", Map.of("x", Map.of("y", "z")));
    data.put("ia", new int[] {8, 64, -8});
    data.put("la", new long[] {Long.MAX_VALUE});
    for (byte[] bytes : List.of(NbtWriter.write(data), NbtWriter.writeGzip(data))) {
      NbtCompound c = read(bytes, NbtLimits.WORLD_DATA);
      assertThat(c.members().keySet()).containsExactlyElementsOf(data.keySet());
      assertThat(c.intValue("b")).hasValue(-1);
      assertThat(c.intValue("s")).hasValue(300);
      assertThat(c.longValue("i")).hasValue(-70_000);
      assertThat(c.longValue("l")).hasValue(Long.MIN_VALUE);
      assertThat(c.get("f")).isEqualTo(new NbtTag.NbtFloat(1.5f));
      assertThat(c.get("d")).isEqualTo(new NbtTag.NbtDouble(-2.25));
      assertThat(c.get("ba")).isEqualTo(new NbtTag.NbtByteArray(new byte[] {1, 2, 3}));
      assertThat(c.string("str")).hasValue("Frühling ☃ 🌲");
      assertThat(c.list("list").orElseThrow().values()).hasSize(3);
      assertThat(c.list("empty").orElseThrow().elementId()).isZero();
      assertThat(c.compound("nested").flatMap(n -> n.compound("x")).flatMap(x -> x.string("y")))
          .hasValue("z");
      assertThat(c.intArray("ia").orElseThrow().values()).containsExactly(8, 64, -8);
      assertThat(c.get("la")).isEqualTo(new NbtTag.NbtLongArray(new long[] {Long.MAX_VALUE}));
    }
  }

  @Test
  void decodedValuesAreImmutable() throws IOException {
    NbtCompound c = read(NbtWriter.write(Map.of("ia", new int[] {1}, "l", List.of(1))), SMALL);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> c.members().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    c.intArray("ia").orElseThrow().values()[0] = 9;
    assertThat(c.intArray("ia").orElseThrow().values()).containsExactly(1);
  }

  @Test
  void structureMustBeAOneCompoundDocument() {
    rejected(new byte[] {8, 0, 0, 0, 0}, SMALL, WorldIssueCodes.NBT_MALFORMED); // root is a string
    byte[] good = NbtWriter.write(Map.of("a", 1));
    byte[] trailing = java.util.Arrays.copyOf(good, good.length + 1);
    rejected(trailing, SMALL, WorldIssueCodes.NBT_MALFORMED);
    for (int cut = 0; cut < good.length; cut++) {
      rejected(java.util.Arrays.copyOf(good, cut), SMALL, WorldIssueCodes.NBT_MALFORMED);
    }
    // Duplicate key "a" (an int 1, then an int 2).
    byte[] duplicate = root(new byte[] {3, 0, 1, 'a', 0, 0, 0, 1, 3, 0, 1, 'a', 0, 0, 0, 2, 0});
    rejected(duplicate, SMALL, WorldIssueCodes.NBT_MALFORMED);
    // Unknown tag type 13.
    rejected(root(new byte[] {13, 0, 1, 'a', 0}), SMALL, WorldIssueCodes.NBT_MALFORMED);
    // A list of type End with one element.
    rejected(
        root(new byte[] {9, 0, 1, 'a', 0, 0, 0, 0, 1, 0}), SMALL, WorldIssueCodes.NBT_MALFORMED);
    // A negative array length.
    rejected(
        root(new byte[] {11, 0, 1, 'a', (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0}),
        SMALL,
        WorldIssueCodes.NBT_MALFORMED);
    // Malformed modified UTF-8 in a key.
    rejected(
        root(new byte[] {3, 0, 1, (byte) 0xC3, 0, 0, 0, 1, 0}),
        SMALL,
        WorldIssueCodes.NBT_MALFORMED);
    // Garbage after a gzip magic.
    rejected(new byte[] {0x1f, (byte) 0x8b, 0, 0}, SMALL, WorldIssueCodes.NBT_MALFORMED);
  }

  @Test
  void hugeClaimedLengthsFailBeforeAllocating() {
    // A long array claiming 2^31 - 1 elements in a 20-byte document.
    rejected(
        root(new byte[] {12, 0, 1, 'a', 0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0}),
        SMALL,
        WorldIssueCodes.NBT_TOO_LARGE);
    // A list claiming 10^6 compounds.
    rejected(
        root(new byte[] {9, 0, 1, 'a', 10, 0, 0x0F, 0x42, 0x40, 0}),
        SMALL,
        WorldIssueCodes.NBT_TOO_LARGE);
  }

  @Test
  void depthAndDecompressedSizeAreBounded() throws IOException {
    Map<String, Object> deep = Map.of();
    for (int i = 0; i < 20; i++) {
      deep = Map.of("n", deep);
    }
    rejected(NbtWriter.write(deep), SMALL, WorldIssueCodes.NBT_TOO_LARGE);
    assertThat(read(NbtWriter.write(deep), new NbtLimits(4096, 32))).isNotNull();
    // A gzip bomb: 1 MiB of zeros in a byte array compresses to about 1 KiB.
    ByteArrayOutputStream bomb = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(bomb)) {
      gzip.write(NbtWriter.write(Map.of("a", new byte[1 << 20])));
    }
    assertThat(bomb.size()).isLessThan(8192);
    rejected(bomb.toByteArray(), new NbtLimits(64 * 1024, 16), WorldIssueCodes.NBT_TOO_LARGE);
  }
}
