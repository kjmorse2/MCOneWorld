// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.fixtures;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * Deterministic corruption of golden inputs for decoder fuzzing. One seed gives one mutant, so a
 * property test that takes the seed from jqwik reproduces any failure from jqwik's recorded seed.
 *
 * <p>Each mutant applies one to four edits: flip, insert, or delete a byte; truncate; duplicate a
 * slice; replace a byte with a JSON structural character or a digit; or splice in a token that
 * stresses parsers (huge or negative numbers, exponents, escapes, malformed UTF-8, and so on).
 */
public final class Mutations {
  private static final byte[] STRUCTURAL =
      "{}[],:\"\\ 0123456789-n".getBytes(StandardCharsets.UTF_8);

  private static final byte[][] TOKENS = {
    utf8("99999999999999999999"),
    utf8("-9223372036854775808"),
    utf8("9223372036854775807"),
    utf8("-0"),
    utf8("1e5"),
    utf8("0.5"),
    utf8("null"),
    utf8("true"),
    utf8("\"\""),
    utf8("\\u0000"),
    utf8("\\ud800"),
    utf8("{}"),
    utf8("[]"),
    utf8("\"x\":1,"),
    utf8("../"),
    utf8("2026-02-30T00:00:00Z"),
    utf8("0000-00-00T00:00:00Z"),
    new byte[] {(byte) 0xC0, (byte) 0x80},
    new byte[] {(byte) 0xED, (byte) 0xA0, (byte) 0x80},
    new byte[] {(byte) 0xFF},
    new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
    new byte[] {'\n'},
  };

  private Mutations() {}

  /** A mutant of {@code input} determined by {@code seed}; the input is never modified. */
  public static byte[] mutate(byte[] input, long seed) {
    SplittableRandom random = new SplittableRandom(seed);
    byte[] out = input.clone();
    int edits = 1 + random.nextInt(4);
    for (int i = 0; i < edits; i++) {
      out = edit(out, random);
    }
    return out;
  }

  private static byte[] edit(byte[] in, SplittableRandom random) {
    int at = in.length == 0 ? 0 : random.nextInt(in.length + 1);
    return switch (random.nextInt(8)) {
      case 0 -> {
        byte[] out = in.clone();
        if (at < out.length) {
          out[at] ^= (byte) (1 << random.nextInt(8));
        }
        yield out;
      }
      case 1 -> splice(in, at, 0, new byte[] {(byte) random.nextInt(256)});
      case 2 -> at < in.length ? splice(in, at, 1, new byte[0]) : in;
      case 3 -> Arrays.copyOf(in, at);
      case 4 -> {
        int length = Math.min(in.length - at, random.nextInt(32));
        yield splice(in, at, 0, Arrays.copyOfRange(in, at, at + length));
      }
      case 5 ->
          at < in.length
              ? splice(in, at, 1, new byte[] {STRUCTURAL[random.nextInt(STRUCTURAL.length)]})
              : in;
      default -> {
        int removed = Math.min(in.length - at, random.nextInt(4));
        yield splice(in, at, removed, TOKENS[random.nextInt(TOKENS.length)]);
      }
    };
  }

  private static byte[] splice(byte[] in, int at, int removed, byte[] inserted) {
    byte[] out = new byte[in.length - removed + inserted.length];
    System.arraycopy(in, 0, out, 0, at);
    System.arraycopy(inserted, 0, out, at, inserted.length);
    System.arraycopy(in, at + removed, out, at + inserted.length, in.length - at - removed);
    return out;
  }

  private static byte[] utf8(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }
}
