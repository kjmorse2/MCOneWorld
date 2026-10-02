// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.protocol.codec;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kjmorse2.mconeworld.fixtures.protocol.ProtocolFixtures;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * An independent reading of the golden vectors, written from {@code docs/formats/protocol-v1.md}
 * alone with a plain {@link ByteBuffer} and none of the production codec. It renders each payload
 * as text and compares it with the values the specification's annotated examples state. If the
 * codec and this reader ever disagree, one of them breaks the specification.
 *
 * <p>Paper and Fabric adapters can port this reader to prove byte compatibility without depending
 * on the codec's encoder. It checks the happy path only; rejection rules are tested in {@link
 * ProtocolCodecTest}.
 */
class SpecificationVectorTest {

  private static final Map<String, String> EXPECTED =
      Map.of(
          "hello-minimal",
          "hello layout=1 versions=1..1 client=- caps=[] ext=[]",
          "hello-v1",
          "hello layout=1 versions=1..1 client=1.0.0+fabric caps=[mconeworld:season_context] ext=[]",
          "hello-future-client",
          "hello layout=1 versions=1..3 client=3.1.4"
              + " caps=[example:future_feature, mconeworld:season_context]"
              + " ext=[example:hint=01ff]",
          "context-season",
          "context layout=1 accepted version=1 caps=[mconeworld:season_context]"
              + " season=5ea50003-0000-4000-8000-000000000003"
              + " name=Season 3 — Frühling ✦ revision=300"
              + " origins=[minecraft:overworld(1048576,64,-2097152),"
              + " minecraft:the_nether(131072,64,-262144)] ext=[]",
          "context-season-extremes",
          "context layout=1 accepted version=1 caps=[mconeworld:season_context]"
              + " season=5ea50003-0000-4000-8000-000000000003 name=S"
              + " revision=9223372036854775807"
              + " origins=[custom:a/b(-34359738368,-2147483648,34359738367),"
              + " minecraft:the_end(-1,2147483647,0)] ext=[]",
          "context-no-season",
          "context layout=1 accepted version=1 caps=[mconeworld:season_context] season=- ext=[]",
          "context-incompatible",
          "context layout=1 incompatible server=1..1 ext=[]");

  @Test
  void everyGoldenVectorReadsAsTheSpecificationStates() {
    assertThat(ProtocolFixtures.vectors()).hasSize(EXPECTED.size());
    for (ProtocolFixtures.Vector vector : ProtocolFixtures.vectors()) {
      ByteBuffer in = ByteBuffer.wrap(ProtocolFixtures.goldenBytes(vector));
      String text = vector.channel().equals("mconeworld:hello") ? hello(in) : context(in);
      assertThat(in.hasRemaining()).as(vector.name() + " has no trailing bytes").isFalse();
      assertThat(text).as(vector.name()).isEqualTo(EXPECTED.get(vector.name()));
    }
  }

  private static String hello(ByteBuffer in) {
    StringBuilder out = new StringBuilder("hello layout=").append(in.get() & 0xFF);
    out.append(" versions=").append(varint(in)).append("..").append(varint(in));
    out.append(" client=").append(in.get() == 1 ? string(in) : "-");
    out.append(" caps=").append(strings(in));
    out.append(" ext=").append(extensions(in));
    return out.toString();
  }

  private static String context(ByteBuffer in) {
    StringBuilder out = new StringBuilder("context layout=").append(in.get() & 0xFF);
    if (in.get() == 0) {
      out.append(" incompatible server=").append(varint(in)).append("..").append(varint(in));
    } else {
      out.append(" accepted version=").append(varint(in));
      out.append(" caps=").append(strings(in));
      if (in.get() == 1) {
        out.append(" season=").append(new java.util.UUID(in.getLong(), in.getLong()));
        out.append(" name=").append(string(in));
        out.append(" revision=").append(varint(in));
        long count = varint(in);
        out.append(" origins=[");
        for (long i = 0; i < count; i++) {
          if (i > 0) {
            out.append(", ");
          }
          out.append(string(in)).append('(').append(in.getLong()).append(',');
          out.append(in.getInt()).append(',').append(in.getLong()).append(')');
        }
        out.append(']');
      } else {
        out.append(" season=-");
      }
    }
    out.append(" ext=").append(extensions(in));
    return out.toString();
  }

  /** Unsigned LEB128: seven bits per byte, low group first, high bit set on all but the last. */
  private static long varint(ByteBuffer in) {
    long value = 0;
    for (int shift = 0; ; shift += 7) {
      int b = in.get() & 0xFF;
      value |= (long) (b & 0x7F) << shift;
      if (b < 0x80) {
        return value;
      }
    }
  }

  private static String string(ByteBuffer in) {
    byte[] bytes = new byte[(int) varint(in)];
    in.get(bytes);
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static String strings(ByteBuffer in) {
    long count = varint(in);
    StringBuilder out = new StringBuilder("[");
    for (long i = 0; i < count; i++) {
      out.append(i > 0 ? ", " : "").append(string(in));
    }
    return out.append(']').toString();
  }

  private static String extensions(ByteBuffer in) {
    long count = varint(in);
    StringBuilder out = new StringBuilder("[");
    for (long i = 0; i < count; i++) {
      String key = string(in);
      byte[] value = new byte[(int) varint(in)];
      in.get(value);
      out.append(i > 0 ? ", " : "").append(key).append('=').append(HexFormat.of().formatHex(value));
    }
    return out.append(']').toString();
  }
}
