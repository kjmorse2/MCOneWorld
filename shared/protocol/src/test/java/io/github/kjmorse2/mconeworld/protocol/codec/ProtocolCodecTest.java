// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.protocol.codec;

import static io.github.kjmorse2.mconeworld.protocol.codec.Payloads.raw;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.DisplayName;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.coordinate.DimensionOrigin;
import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import io.github.kjmorse2.mconeworld.fixtures.protocol.ProtocolFixtures;
import io.github.kjmorse2.mconeworld.protocol.Capability;
import io.github.kjmorse2.mconeworld.protocol.CapabilitySet;
import io.github.kjmorse2.mconeworld.protocol.ContextMessage;
import io.github.kjmorse2.mconeworld.protocol.Extension;
import io.github.kjmorse2.mconeworld.protocol.Extensions;
import io.github.kjmorse2.mconeworld.protocol.HelloMessage;
import io.github.kjmorse2.mconeworld.protocol.ProtocolException;
import io.github.kjmorse2.mconeworld.protocol.ProtocolIssueCodes;
import io.github.kjmorse2.mconeworld.protocol.ProtocolLimits;
import io.github.kjmorse2.mconeworld.protocol.SeasonContext;
import io.github.kjmorse2.mconeworld.protocol.VersionRange;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The codec's rules, one rejection at a time: round trips, determinism, the exact minimum and
 * maximum sizes, and every class of malformed input with its stable code.
 */
class ProtocolCodecTest {
  private static final String SC = "mconeworld:season_context";
  private static final long UUID_HIGH = 0x5ea5000300004000L;
  private static final long UUID_LOW = 0x8000000000000003L;

  /** Accepted context, version 1, season context enabled, a season with the given tail. */
  private static byte[] seasonContext(Object... tail) {
    List<Object> parts = new ArrayList<>(List.of(1, 1, 1, 1, SC, 1));
    parts.addAll(Arrays.asList(tail));
    return Payloads.of(parts.toArray());
  }

  private static byte[] origins(Object... originsAndExtensions) {
    List<Object> parts = new ArrayList<>(List.of(UUID_HIGH, UUID_LOW, "S", 0));
    parts.addAll(Arrays.asList(originsAndExtensions));
    return seasonContext(parts.toArray());
  }

  private static void rejectsHello(byte[] payload, IssueCode code) {
    assertThatThrownBy(() -> ProtocolCodec.decodeHello(payload))
        .isInstanceOfSatisfying(ProtocolException.class, e -> assertThat(e.code()).isEqualTo(code));
  }

  private static void rejectsContext(byte[] payload, IssueCode code) {
    assertThatThrownBy(() -> ProtocolCodec.decodeContext(payload))
        .isInstanceOfSatisfying(ProtocolException.class, e -> assertThat(e.code()).isEqualTo(code));
  }

  @Test
  void handWrittenPayloadsDecode() throws ProtocolException {
    assertThat(ProtocolCodec.decodeHello(Payloads.of(1, 1, 1, 0, 0, 0)))
        .isEqualTo(
            new HelloMessage(VersionRange.only(1), CapabilitySet.EMPTY, null, Extensions.NONE));
    ContextMessage context =
        ProtocolCodec.decodeContext(
            origins(1, "minecraft:overworld", 16L, raw(0, 0, 0, 64), -16L, 0));
    assertThat(context)
        .isEqualTo(
            new ContextMessage.Accepted(
                1,
                CapabilitySet.of(Capability.SEASON_CONTEXT),
                SeasonContext.of(
                    SeasonId.of(new UUID(UUID_HIGH, UUID_LOW)),
                    DisplayName.of("S"),
                    0,
                    List.of(new DimensionOrigin(DimensionKey.OVERWORLD, 16, 64, -16))),
                Extensions.NONE));
  }

  @Test
  void encodingIsIndependentOfInsertionOrder() {
    DimensionOrigin o = new DimensionOrigin(DimensionKey.OVERWORLD, 1, 2, 3);
    DimensionOrigin n = new DimensionOrigin(DimensionKey.THE_NETHER, 4, 5, 6);
    DimensionOrigin e = new DimensionOrigin(DimensionKey.THE_END, 7, 8, 9);
    Capability f = new Capability("a:first");
    Extension x = new Extension("x:one", new byte[] {1});
    Extension y = new Extension("y:two", new byte[] {2});
    SeasonId id = ProtocolFixtures.SEASON;
    ContextMessage first =
        new ContextMessage.Accepted(
            1,
            CapabilitySet.of(Capability.SEASON_CONTEXT, f),
            SeasonContext.of(id, DisplayName.of("A"), 5, List.of(o, n, e)),
            Extensions.of(List.of(x, y)));
    ContextMessage second =
        new ContextMessage.Accepted(
            1,
            CapabilitySet.of(f, Capability.SEASON_CONTEXT),
            SeasonContext.of(id, DisplayName.of("A"), 5, List.of(e, o, n)),
            Extensions.of(List.of(y, x)));
    assertThat(ProtocolCodec.encodeContext(second)).isEqualTo(ProtocolCodec.encodeContext(first));
    HelloMessage h1 =
        new HelloMessage(
            VersionRange.only(1),
            CapabilitySet.of(f, Capability.SEASON_CONTEXT),
            null,
            Extensions.of(List.of(y, x)));
    HelloMessage h2 =
        new HelloMessage(
            VersionRange.only(1),
            CapabilitySet.of(Capability.SEASON_CONTEXT, f),
            null,
            Extensions.of(List.of(x, y)));
    assertThat(ProtocolCodec.encodeHello(h1)).isEqualTo(ProtocolCodec.encodeHello(h2));
  }

  @Test
  void theMinimumPayloadsAreSixAndFiveBytes() throws ProtocolException {
    byte[] hello = Payloads.of(1, 1, 1, 0, 0, 0);
    byte[] context = Payloads.of(1, 0, 1, 1, 0);
    assertThat(ProtocolCodec.encodeHello(ProtocolCodec.decodeHello(hello))).isEqualTo(hello);
    assertThat(ProtocolCodec.encodeContext(ProtocolCodec.decodeContext(context)))
        .isEqualTo(context);
  }

  @Test
  void theLargestValidHelloFitsItsLimitAndRoundTrips() throws ProtocolException {
    HelloMessage largest =
        new HelloMessage(
            new VersionRange(16_384, ProtocolLimits.MAX_PROTOCOL_VERSION),
            CapabilitySet.of(Limits.capabilities(ProtocolLimits.MAX_CAPABILITIES)),
            "v".repeat(ProtocolLimits.MAX_MOD_VERSION_BYTES),
            Limits.largestExtensions());
    byte[] bytes = ProtocolCodec.encodeHello(largest);
    assertThat(bytes).hasSize(7_323);
    assertThat(bytes.length).isLessThanOrEqualTo(ProtocolLimits.MAX_HELLO_BYTES);
    assertThat(ProtocolCodec.decodeHello(bytes)).isEqualTo(largest);
  }

  @Test
  void theLargestValidContextFitsItsLimitAndRoundTrips() throws ProtocolException {
    List<Capability> capabilities = new ArrayList<>(Limits.capabilities(31));
    capabilities.add(Capability.SEASON_CONTEXT);
    ContextMessage largest =
        new ContextMessage.Accepted(
            ProtocolLimits.MAX_PROTOCOL_VERSION,
            CapabilitySet.of(capabilities),
            SeasonContext.of(
                ProtocolFixtures.SEASON,
                DisplayName.of("😀".repeat(DisplayName.MAX_CODE_POINTS)),
                Long.MAX_VALUE,
                Limits.largestOrigins()),
            Limits.largestExtensions());
    byte[] bytes = ProtocolCodec.encodeContext(largest);
    assertThat(bytes).hasSize(25_293);
    assertThat(bytes.length).isLessThanOrEqualTo(ProtocolLimits.MAX_CONTEXT_BYTES);
    assertThat(ProtocolCodec.decodeContext(bytes)).isEqualTo(largest);
  }

  @Test
  void everyProperPrefixOfAGoldenVectorIsTruncated() {
    for (ProtocolFixtures.Vector vector : ProtocolFixtures.vectors()) {
      byte[] golden = ProtocolFixtures.goldenBytes(vector);
      for (int length = 0; length < golden.length; length++) {
        byte[] prefix = Arrays.copyOf(golden, length);
        if (vector instanceof ProtocolFixtures.Hello) {
          rejectsHello(prefix, ProtocolIssueCodes.TRUNCATED);
        } else {
          rejectsContext(prefix, ProtocolIssueCodes.TRUNCATED);
        }
      }
    }
  }

  @Test
  void trailingBytesAreRejected() {
    for (ProtocolFixtures.Vector vector : ProtocolFixtures.vectors()) {
      byte[] golden = ProtocolFixtures.goldenBytes(vector);
      byte[] longer = Arrays.copyOf(golden, golden.length + 1);
      if (vector instanceof ProtocolFixtures.Hello) {
        rejectsHello(longer, ProtocolIssueCodes.TRAILING_BYTES);
      } else {
        rejectsContext(longer, ProtocolIssueCodes.TRAILING_BYTES);
      }
    }
  }

  @Test
  void oversizedPayloadsAreRejectedBeforeParsing() {
    rejectsHello(new byte[ProtocolLimits.MAX_HELLO_BYTES + 1], ProtocolIssueCodes.TOO_LARGE);
    rejectsContext(new byte[ProtocolLimits.MAX_CONTEXT_BYTES + 1], ProtocolIssueCodes.TOO_LARGE);
    assertThatThrownBy(
            () ->
                ProtocolCodec.decodeHello(ByteBuffer.allocate(ProtocolLimits.MAX_HELLO_BYTES + 1)))
        .isInstanceOf(ProtocolException.class);
  }

  @Test
  void layoutsAndKindsOutsideVersionOneAreRejected() {
    rejectsHello(Payloads.of(2, 1, 1, 0, 0, 0), ProtocolIssueCodes.UNSUPPORTED_LAYOUT);
    rejectsHello(Payloads.of(0, 1, 1, 0, 0, 0), ProtocolIssueCodes.UNSUPPORTED_LAYOUT);
    rejectsContext(Payloads.of(2, 0, 1, 1, 0), ProtocolIssueCodes.UNSUPPORTED_LAYOUT);
    rejectsContext(Payloads.of(1, 2, 1, 1, 0), ProtocolIssueCodes.OUT_OF_RANGE);
  }

  @Test
  void versionsMustFormANonEmptyRangeWithinBounds() {
    rejectsHello(Payloads.of(1, 0, 1, 0, 0, 0), ProtocolIssueCodes.OUT_OF_RANGE);
    rejectsHello(Payloads.of(1, 2, 1, 0, 0, 0), ProtocolIssueCodes.OUT_OF_RANGE);
    rejectsHello(
        Payloads.of(1, 1, raw(0x80, 0x80, 0x04), 0, 0, 0), ProtocolIssueCodes.OUT_OF_RANGE);
    rejectsContext(Payloads.of(1, 1, 0, 0, 0, 0), ProtocolIssueCodes.OUT_OF_RANGE);
    rejectsContext(Payloads.of(1, 0, 3, 2, 0), ProtocolIssueCodes.OUT_OF_RANGE);
  }

  @Test
  void varintsMustBeShortestAndShortEnough() {
    rejectsHello(Payloads.of(1, raw(0x81, 0x00), 1, 0, 0, 0), ProtocolIssueCodes.NOT_CANONICAL);
    rejectsHello(Payloads.of(1, raw(0x80, 0x00), 1, 0, 0, 0), ProtocolIssueCodes.NOT_CANONICAL);
    rejectsHello(
        Payloads.of(1, raw(0x81, 0x80, 0x80, 0x00), 1, 0, 0, 0),
        ProtocolIssueCodes.MALFORMED_VARINT);
    rejectsContext(
        origins(raw(0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x01)),
        ProtocolIssueCodes.MALFORMED_VARINT);
    // The revision varint has nine bytes at most, and nine hold exactly 63 bits.
    rejectsContext(
        seasonContext(
            UUID_HIGH,
            UUID_LOW,
            "S",
            raw(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x00)),
        ProtocolIssueCodes.MALFORMED_VARINT);
  }

  @Test
  void flagsAreZeroOrOne() {
    rejectsHello(Payloads.of(1, 1, 1, 2, 0, 0), ProtocolIssueCodes.OUT_OF_RANGE);
    rejectsContext(Payloads.of(1, 1, 1, 1, SC, 2, 0), ProtocolIssueCodes.OUT_OF_RANGE);
  }

  @Test
  void clientVersionIsPrintableAsciiWithinItsLimit() {
    rejectsHello(Payloads.of(1, 1, 1, 1, "", 0, 0), ProtocolIssueCodes.MALFORMED_TEXT);
    rejectsHello(Payloads.of(1, 1, 1, 1, "1 0", 0, 0), ProtocolIssueCodes.MALFORMED_TEXT);
    rejectsHello(Payloads.of(1, 1, 1, 1, "é", 0, 0), ProtocolIssueCodes.MALFORMED_TEXT);
    rejectsHello(Payloads.of(1, 1, 1, 1, "v".repeat(65), 0, 0), ProtocolIssueCodes.TOO_LARGE);
  }

  @Test
  void capabilitiesAreValidSortedUniqueAndBounded() {
    rejectsHello(Payloads.of(1, 1, 1, 0, 1, "Bad:cap", 0), ProtocolIssueCodes.INVALID_IDENTIFIER);
    rejectsHello(Payloads.of(1, 1, 1, 0, 1, "nocolon", 0), ProtocolIssueCodes.INVALID_IDENTIFIER);
    rejectsHello(Payloads.of(1, 1, 1, 0, 1, "a:../b", 0), ProtocolIssueCodes.INVALID_IDENTIFIER);
    rejectsHello(Payloads.of(1, 1, 1, 0, 2, "b:x", "a:x", 0), ProtocolIssueCodes.NOT_CANONICAL);
    rejectsHello(Payloads.of(1, 1, 1, 0, 2, "a:x", "a:x", 0), ProtocolIssueCodes.NOT_CANONICAL);
    rejectsHello(Payloads.of(1, 1, 1, 0, 33), ProtocolIssueCodes.TOO_LARGE);
    rejectsHello(
        Payloads.of(1, 1, 1, 0, 1, "a:" + "x".repeat(63), 0), ProtocolIssueCodes.TOO_LARGE);
  }

  @Test
  void extensionsAreValidSortedUniqueAndBounded() throws ProtocolException {
    HelloMessage kept =
        ProtocolCodec.decodeHello(Payloads.of(1, 1, 1, 0, 0, 1, "z:future", 2, 7, 8));
    assertThat(kept.extensions().values().getFirst().value()).containsExactly(7, 8);
    rejectsHello(
        Payloads.of(1, 1, 1, 0, 0, 2, "b:x", 0, "a:x", 0), ProtocolIssueCodes.NOT_CANONICAL);
    rejectsHello(
        Payloads.of(1, 1, 1, 0, 0, 2, "a:x", 0, "a:x", 0), ProtocolIssueCodes.NOT_CANONICAL);
    rejectsHello(Payloads.of(1, 1, 1, 0, 0, 17), ProtocolIssueCodes.TOO_LARGE);
    rejectsHello(
        Payloads.of(1, 1, 1, 0, 0, 1, "a:x", raw(0x81, 0x02), new byte[257]),
        ProtocolIssueCodes.TOO_LARGE);
    rejectsHello(Payloads.of(1, 1, 1, 0, 0, 1, "A:x", 0), ProtocolIssueCodes.INVALID_IDENTIFIER);
  }

  @Test
  void aSeasonNeedsTheSeasonContextCapabilityAndANonNilId() {
    rejectsContext(
        Payloads.of(1, 1, 1, 0, 1, UUID_HIGH, UUID_LOW, "S", 0, 0, 0),
        ProtocolIssueCodes.OUT_OF_RANGE);
    rejectsContext(seasonContext(0L, 0L, "S", 0, 0, 0), ProtocolIssueCodes.OUT_OF_RANGE);
  }

  @Test
  void seasonNamesFollowTheDisplayNameRulesInStrictUtf8() {
    // Malformed sequences: a lone continuation start, an overlong NUL, an encoded surrogate, and a
    // five-byte form. Then well-formed text the display-name rules reject.
    for (byte[] bad :
        List.of(
            raw(0xC3, 0x28),
            raw(0xC0, 0x80),
            raw(0xED, 0xA0, 0x80),
            raw(0xF8, 0x88, 0x80, 0x80, 0x80))) {
      rejectsContext(
          seasonContext(UUID_HIGH, UUID_LOW, bad.length, bad, 0, 0, 0),
          ProtocolIssueCodes.MALFORMED_TEXT);
    }
    for (String bad :
        List.of("", " S", "S\u0007", Character.toString(0x202E) + "S", "x".repeat(65))) {
      rejectsContext(
          seasonContext(UUID_HIGH, UUID_LOW, bad, 0, 0, 0), ProtocolIssueCodes.MALFORMED_TEXT);
    }
    rejectsContext(
        seasonContext(UUID_HIGH, UUID_LOW, raw(0x81, 0x02)), ProtocolIssueCodes.TOO_LARGE);
  }

  @Test
  void originsAreValidSortedUniqueBoundedAndRepresentable() {
    byte[] xyz = Payloads.of(0L, raw(0, 0, 0, 0), 0L);
    rejectsContext(
        origins(2, "minecraft:overworld", xyz, "minecraft:overworld", xyz, 0),
        ProtocolIssueCodes.NOT_CANONICAL);
    rejectsContext(
        origins(2, "minecraft:the_nether", xyz, "minecraft:overworld", xyz, 0),
        ProtocolIssueCodes.NOT_CANONICAL);
    rejectsContext(
        origins(1, "Minecraft:overworld", xyz, 0), ProtocolIssueCodes.INVALID_IDENTIFIER);
    rejectsContext(origins(1, "minecraft:../x", xyz, 0), ProtocolIssueCodes.INVALID_IDENTIFIER);
    rejectsContext(origins(1, "overworld", xyz, 0), ProtocolIssueCodes.INVALID_IDENTIFIER);
    rejectsContext(origins(65), ProtocolIssueCodes.TOO_LARGE);
    rejectsContext(
        origins(1, "minecraft:overworld", Long.MAX_VALUE, raw(0, 0, 0, 0), 0L, 0),
        ProtocolIssueCodes.OUT_OF_RANGE);
    rejectsContext(
        origins(1, "minecraft:overworld", 0L, raw(0, 0, 0, 0), Long.MIN_VALUE, 0),
        ProtocolIssueCodes.OUT_OF_RANGE);
  }

  @Test
  void hugeClaimedLengthsFailWithoutAllocatingThem() {
    rejectsHello(
        Payloads.of(1, 1, 1, 1, raw(0xFF, 0xFF, 0xFF, 0xFF, 0x07)), ProtocolIssueCodes.TOO_LARGE);
    // Within the limit but beyond the input: rejected before the string is copied.
    rejectsContext(
        seasonContext(UUID_HIGH, UUID_LOW, raw(0xC8, 0x01), raw(0x41)),
        ProtocolIssueCodes.TRUNCATED);
    // Decoding the largest adversarial input allocates a small multiple of its size at most.
    byte[] adversarial = new byte[ProtocolLimits.MAX_CONTEXT_BYTES];
    Arrays.fill(adversarial, (byte) 0x41);
    byte[] prefix = origins(64);
    System.arraycopy(prefix, 0, adversarial, 0, prefix.length);
    com.sun.management.ThreadMXBean threads =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    long before = threads.getCurrentThreadAllocatedBytes();
    for (int i = 0; i < 100; i++) {
      assertThatThrownBy(() -> ProtocolCodec.decodeContext(adversarial))
          .isInstanceOf(ProtocolException.class);
    }
    long perDecode = (threads.getCurrentThreadAllocatedBytes() - before) / 100;
    assertThat(perDecode).isLessThan(16L * ProtocolLimits.MAX_CONTEXT_BYTES);
  }

  @Test
  void decodedValuesAreImmutable() throws ProtocolException {
    ContextMessage.Accepted context =
        (ContextMessage.Accepted)
            ProtocolCodec.decodeContext(
                ProtocolFixtures.goldenBytes(ProtocolFixtures.vectors().get(3)));
    SeasonContext season = context.season();
    assertThat(season).isNotNull();
    assertThatThrownBy(() -> season.origins().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> context.enabled().values().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    HelloMessage hello = ProtocolCodec.decodeHello(Payloads.of(1, 1, 1, 0, 0, 1, "z:future", 1, 7));
    assertThatThrownBy(() -> hello.extensions().values().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    Extension extension = hello.extensions().values().getFirst();
    extension.value()[0] = 9;
    assertThat(extension.value()).containsExactly(7);
  }

  @Test
  void byteBufferInputIsConsumedExactly() throws ProtocolException {
    ByteBuffer buffer = ByteBuffer.wrap(Payloads.of(9, 1, 1, 1, 0, 0, 0)).position(1);
    assertThat(ProtocolCodec.decodeHello(buffer).versions()).isEqualTo(VersionRange.only(1));
    assertThat(buffer.hasRemaining()).isFalse();
  }
}
