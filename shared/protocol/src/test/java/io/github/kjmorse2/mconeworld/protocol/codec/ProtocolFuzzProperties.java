// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.protocol.codec;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.DisplayName;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.coordinate.DimensionOrigin;
import io.github.kjmorse2.mconeworld.fixtures.protocol.ProtocolFixtures;
import io.github.kjmorse2.mconeworld.protocol.Capability;
import io.github.kjmorse2.mconeworld.protocol.CapabilitySet;
import io.github.kjmorse2.mconeworld.protocol.ContextMessage;
import io.github.kjmorse2.mconeworld.protocol.Extension;
import io.github.kjmorse2.mconeworld.protocol.Extensions;
import io.github.kjmorse2.mconeworld.protocol.HelloMessage;
import io.github.kjmorse2.mconeworld.protocol.ProtocolException;
import io.github.kjmorse2.mconeworld.protocol.SeasonContext;
import io.github.kjmorse2.mconeworld.protocol.VersionRange;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.statistics.Statistics;

/**
 * Fuzzing. For any input, decoding either throws {@link ProtocolException} (and nothing else) or
 * returns a value whose encoding is exactly the input: one byte string per value. Random valid
 * values round-trip.
 */
class ProtocolFuzzProperties {

  @Property(tries = 3000)
  void arbitraryBytesDecodeCanonicallyOrFailCleanly(@ForAll("noise") byte[] payload) {
    checkHello(payload);
    checkContext(payload);
  }

  @Property(tries = 3000)
  void mutatedGoldenVectorsDecodeCanonicallyOrFailCleanly(@ForAll("mutants") byte[] payload) {
    checkHello(payload);
    checkContext(payload);
  }

  @Property(tries = 500)
  void validContextsRoundTrip(@ForAll("contexts") ContextMessage context) throws ProtocolException {
    byte[] bytes = ProtocolCodec.encodeContext(context);
    assertThat(ProtocolCodec.decodeContext(bytes)).isEqualTo(context);
  }

  @Property(tries = 500)
  void validHellosRoundTrip(@ForAll("hellos") HelloMessage hello) throws ProtocolException {
    assertThat(ProtocolCodec.decodeHello(ProtocolCodec.encodeHello(hello))).isEqualTo(hello);
  }

  private static void checkHello(byte[] payload) {
    try {
      HelloMessage hello = ProtocolCodec.decodeHello(payload);
      assertThat(ProtocolCodec.encodeHello(hello)).isEqualTo(payload);
      Statistics.label("hello").collect("accepted");
    } catch (ProtocolException e) {
      Statistics.label("hello").collect(e.code().value());
    }
  }

  private static void checkContext(byte[] payload) {
    try {
      ContextMessage context = ProtocolCodec.decodeContext(payload);
      assertThat(ProtocolCodec.encodeContext(context)).isEqualTo(payload);
      Statistics.label("context").collect("accepted");
    } catch (ProtocolException e) {
      Statistics.label("context").collect(e.code().value());
    }
  }

  @Provide
  Arbitrary<byte[]> noise() {
    // Biased toward the bytes the format uses so that some inputs get past the first fields.
    Arbitrary<Byte> b =
        Arbitraries.frequencyOf(
            net.jqwik.api.Tuple.of(4, Arbitraries.of((byte) 0, (byte) 1, (byte) 2, (byte) 0x80)),
            net.jqwik.api.Tuple.of(2, Arbitraries.bytes()));
    // Most inputs start with the version 1 layout byte, so they reach the fields behind it.
    return Combinators.combine(
            Arbitraries.integers().between(0, 9), b.array(byte[].class).ofMaxSize(80))
        .as(
            (dice, body) -> {
              if (dice < 2 || body.length == 0) {
                return body;
              }
              byte[] out = body.clone();
              out[0] = 1;
              return out;
            });
  }

  @Provide
  Arbitrary<byte[]> mutants() {
    List<byte[]> golden =
        ProtocolFixtures.vectors().stream().map(ProtocolFixtures::goldenBytes).toList();
    return Combinators.combine(
            Arbitraries.of(golden),
            Arbitraries.integers().between(0, 2),
            Arbitraries.integers().between(0, 1000),
            Arbitraries.bytes())
        .as(
            (bytes, kind, where, value) -> {
              int at = where % (bytes.length + 1);
              return switch (kind) {
                case 0 -> {
                  byte[] out = bytes.clone();
                  if (at < out.length) {
                    out[at] = value;
                  }
                  yield out;
                }
                case 1 -> {
                  byte[] out = new byte[bytes.length + 1];
                  System.arraycopy(bytes, 0, out, 0, at);
                  out[at] = value;
                  System.arraycopy(bytes, at, out, at + 1, bytes.length - at);
                  yield out;
                }
                default -> {
                  if (at == bytes.length) {
                    yield bytes.clone();
                  }
                  byte[] out = Arrays.copyOf(bytes, bytes.length - 1);
                  System.arraycopy(bytes, at + 1, out, at, bytes.length - at - 1);
                  yield out;
                }
              };
            });
  }

  private static Arbitrary<Capability> capability() {
    return Arbitraries.strings()
        .withChars("abcxyz_-.")
        .ofMinLength(1)
        .ofMaxLength(10)
        .filter(s -> !s.equals(".") && !s.equals(".."))
        .map(s -> new Capability("t:" + s));
  }

  private static Arbitrary<Extensions> extensions() {
    Arbitrary<Extension> extension =
        Combinators.combine(
                Arbitraries.strings().withChars("abc").ofMinLength(1).ofMaxLength(4),
                Arbitraries.bytes()
                    .array(byte[].class)
                    .ofMaxSize(300)
                    .map(v -> Arrays.copyOf(v, Math.min(v.length, 256))))
            .as((k, v) -> new Extension("e:" + k, v));
    return extension.list().ofMaxSize(4).uniqueElements(Extension::key).map(Extensions::of);
  }

  private static Arbitrary<VersionRange> versions() {
    return Combinators.combine(
            Arbitraries.integers().between(1, 70_000), Arbitraries.integers().between(0, 70_000))
        .as((min, span) -> new VersionRange(Math.min(min, 65_535), Math.min(min + span, 65_535)));
  }

  @Provide
  Arbitrary<HelloMessage> hellos() {
    return Combinators.combine(
            versions(),
            capability().set().ofMaxSize(6).map(CapabilitySet::of),
            Arbitraries.strings()
                .withCharRange('!', '~')
                .ofMinLength(1)
                .ofMaxLength(64)
                .injectNull(0.3),
            extensions())
        .as(HelloMessage::new);
  }

  @Provide
  Arbitrary<ContextMessage> contexts() {
    Arbitrary<DimensionOrigin> origin =
        Combinators.combine(
                Arbitraries.of(
                    DimensionKey.OVERWORLD,
                    DimensionKey.THE_NETHER,
                    DimensionKey.THE_END,
                    new DimensionKey("x:y/z")),
                Arbitraries.longs().between(-(1L << 35), (1L << 35) - 1),
                Arbitraries.integers(),
                Arbitraries.longs().between(-(1L << 35), (1L << 35) - 1))
            .as(DimensionOrigin::new);
    Arbitrary<SeasonContext> season =
        Combinators.combine(
                Arbitraries.longs().filter(l -> l != 0),
                Arbitraries.of("A", "Season 1", "Été ☀", "🌲 Forest"),
                Arbitraries.longs().between(0, Long.MAX_VALUE),
                origin.list().ofMaxSize(4).uniqueElements(DimensionOrigin::dimension))
            .as(
                (id, name, rev, origins) ->
                    SeasonContext.of(
                        SeasonId.of(new UUID(id, id)), DisplayName.of(name), rev, origins));
    Arbitrary<ContextMessage> accepted =
        Combinators.combine(
                Arbitraries.integers().between(1, 65_535),
                capability().set().ofMaxSize(5),
                season.injectNull(0.3),
                extensions())
            .as(
                (version, caps, s, ext) -> {
                  java.util.Set<Capability> enabled = new java.util.TreeSet<>(caps);
                  if (s != null) {
                    enabled.add(Capability.SEASON_CONTEXT);
                  }
                  return new ContextMessage.Accepted(version, CapabilitySet.of(enabled), s, ext);
                });
    Arbitrary<ContextMessage> incompatible =
        Combinators.combine(versions(), extensions()).as(ContextMessage.Incompatible::new);
    return Arbitraries.oneOf(accepted, incompatible);
  }
}
