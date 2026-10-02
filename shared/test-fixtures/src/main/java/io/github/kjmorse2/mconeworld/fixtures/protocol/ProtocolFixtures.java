// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.fixtures.protocol;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.DisplayName;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.coordinate.CoordinateLimits;
import io.github.kjmorse2.mconeworld.domain.coordinate.DimensionOrigin;
import io.github.kjmorse2.mconeworld.protocol.Capability;
import io.github.kjmorse2.mconeworld.protocol.CapabilitySet;
import io.github.kjmorse2.mconeworld.protocol.ContextMessage;
import io.github.kjmorse2.mconeworld.protocol.Extension;
import io.github.kjmorse2.mconeworld.protocol.Extensions;
import io.github.kjmorse2.mconeworld.protocol.HelloMessage;
import io.github.kjmorse2.mconeworld.protocol.SeasonContext;
import io.github.kjmorse2.mconeworld.protocol.VersionRange;
import io.github.kjmorse2.mconeworld.protocol.codec.ProtocolCodec;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * The golden vectors of protocol version 1: every message kind, from minimal to extreme. Each is a
 * value plus a {@code .bin} file holding its exact bytes and a {@code .meta} file with the channel,
 * layout, and SHA-256. The bytes are also annotated field by field in {@code
 * docs/formats/protocol-v1.md} §8.
 *
 * <p>To change them intentionally (only together with a new protocol version), run {@link #main}
 * with a scratch directory, review the bytes against the specification, and copy them in.
 */
public final class ProtocolFixtures {
  /** Classpath directory of the golden files. */
  public static final String GOLDEN_DIRECTORY =
      "/io/github/kjmorse2/mconeworld/fixtures/protocol/v1/";

  /** The future-feature capability the vectors use to show unknown capabilities. */
  public static final Capability FUTURE = new Capability("example:future_feature");

  /** The season of the context vectors. */
  public static final SeasonId SEASON =
      SeasonId.of(UUID.fromString("5ea50003-0000-4000-8000-000000000003"));

  /** A golden vector: a hello or a context with its file name. */
  public sealed interface Vector {
    /** File stem under {@link #GOLDEN_DIRECTORY}. */
    String name();

    /** The channel the payload travels on. */
    String channel();
  }

  /**
   * A hello vector.
   *
   * @param name file stem
   * @param message the value
   */
  public record Hello(String name, HelloMessage message) implements Vector {
    @Override
    public String channel() {
      return "mconeworld:hello";
    }
  }

  /**
   * A context vector.
   *
   * @param name file stem
   * @param message the value
   */
  public record Context(String name, ContextMessage message) implements Vector {
    @Override
    public String channel() {
      return "mconeworld:context";
    }
  }

  private ProtocolFixtures() {}

  /** Every golden vector. */
  public static List<Vector> vectors() {
    return List.of(
        new Hello(
            "hello-minimal",
            new HelloMessage(VersionRange.only(1), CapabilitySet.EMPTY, null, Extensions.NONE)),
        new Hello("hello-v1", HelloMessage.v1("1.0.0+fabric")),
        new Hello(
            "hello-future-client",
            new HelloMessage(
                new VersionRange(1, 3),
                CapabilitySet.of(Capability.SEASON_CONTEXT, FUTURE),
                "3.1.4",
                Extensions.of(
                    List.of(new Extension("example:hint", new byte[] {0x01, (byte) 0xFF}))))),
        new Context(
            "context-season",
            new ContextMessage.Accepted(
                1,
                CapabilitySet.of(Capability.SEASON_CONTEXT),
                SeasonContext.of(
                    SEASON,
                    DisplayName.of("Season 3 — Frühling ✦"),
                    300,
                    List.of(
                        new DimensionOrigin(DimensionKey.OVERWORLD, 1_048_576, 64, -2_097_152),
                        new DimensionOrigin(DimensionKey.THE_NETHER, 131_072, 64, -262_144))),
                Extensions.NONE)),
        new Context(
            "context-season-extremes",
            new ContextMessage.Accepted(
                1,
                CapabilitySet.of(Capability.SEASON_CONTEXT),
                SeasonContext.of(
                    SEASON,
                    DisplayName.of("S"),
                    Long.MAX_VALUE,
                    List.of(
                        new DimensionOrigin(
                            new DimensionKey("custom:a/b"),
                            CoordinateLimits.MIN_BLOCK_XZ,
                            Integer.MIN_VALUE,
                            CoordinateLimits.MAX_BLOCK_XZ),
                        new DimensionOrigin(DimensionKey.THE_END, -1, Integer.MAX_VALUE, 0))),
                Extensions.NONE)),
        new Context(
            "context-no-season",
            new ContextMessage.Accepted(
                1, CapabilitySet.of(Capability.SEASON_CONTEXT), null, Extensions.NONE)),
        new Context(
            "context-incompatible",
            new ContextMessage.Incompatible(VersionRange.only(1), Extensions.NONE)));
  }

  /** The encoding of {@code vector} by the production codec. */
  public static byte[] encode(Vector vector) {
    return switch (vector) {
      case Hello h -> ProtocolCodec.encodeHello(h.message());
      case Context c -> ProtocolCodec.encodeContext(c.message());
    };
  }

  /** The pinned bytes of {@code vector}. */
  public static byte[] goldenBytes(Vector vector) {
    return resource(GOLDEN_DIRECTORY + vector.name() + ".bin");
  }

  /** The pinned {@code .meta} text of {@code vector}. */
  public static String goldenMeta(Vector vector) {
    return new String(
        resource(GOLDEN_DIRECTORY + vector.name() + ".bin.meta"), StandardCharsets.UTF_8);
  }

  /** The {@code .meta} text that describes {@code bytes} for {@code vector}. */
  public static String meta(Vector vector, byte[] bytes) {
    return "# Golden protocol vector (docs/formats/protocol-v1.md §8). Produced by"
        + " ProtocolFixtures.main; reviewed against the specification before pinning.\n"
        + "format=mconeworld-protocol\n"
        + "version=1\n"
        + "channel="
        + vector.channel()
        + "\n"
        + "length="
        + bytes.length
        + "\n"
        + "sha256="
        + sha256(bytes)
        + "\n";
  }

  /** Writes every vector's {@code .bin} and {@code .meta} into {@code args[0]} for review. */
  public static void main(String[] args) throws IOException {
    Path out = Path.of(args[0]);
    Files.createDirectories(out);
    for (Vector vector : vectors()) {
      byte[] bytes = encode(vector);
      Files.write(out.resolve(vector.name() + ".bin"), bytes);
      Files.writeString(out.resolve(vector.name() + ".bin.meta"), meta(vector, bytes));
      System.out.println(vector.name() + " " + HexFormat.ofDelimiter(" ").formatHex(bytes));
    }
  }

  private static byte[] resource(String path) {
    try (InputStream in = ProtocolFixtures.class.getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException("Missing golden resource " + path);
      }
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
