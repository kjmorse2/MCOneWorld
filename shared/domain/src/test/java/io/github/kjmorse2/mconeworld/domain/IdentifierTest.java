// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Covers SeasonId and OperationId, which share the canonical-UUID rules. */
class IdentifierTest {
  private static final String CANONICAL = "3f2504e0-4f89-41d3-9a0c-0305e82c3301";

  static List<Function<String, Object>> parsers() {
    return List.of(
        SeasonId::parse,
        ArchiveId::parse,
        OperationId::parse,
        BackupId::parse,
        PortalMarkerId::parse,
        ProtectedRegionId::parse,
        PlayerArchiveId::parse,
        PlayerId::parse);
  }

  @ParameterizedTest
  @MethodSource("parsers")
  void parsesCanonicalFormAndRoundTripsText(Function<String, Object> parse) {
    Object id = parse.apply(CANONICAL);
    assertThat(id.toString()).isEqualTo(CANONICAL);
    assertThat(parse.apply(id.toString())).isEqualTo(id);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        " 3f2504e0-4f89-41d3-9a0c-0305e82c3301",
        "3F2504E0-4F89-41D3-9A0C-0305E82C3301", // upper case is not canonical
        "1-1-1-1-1", // accepted by UUID.fromString, rejected here
        "3f2504e04f8941d39a0c0305e82c3301", // no hyphens
        "3f2504e0-4f89-41d3-9a0c-0305e82c330g",
        "{3f2504e0-4f89-41d3-9a0c-0305e82c3301}"
      })
  void rejectsNonCanonicalText(String text) {
    for (Function<String, Object> parse : parsers()) {
      assertThatThrownBy(() -> parse.apply(text))
          .isInstanceOfSatisfying(
              InvalidDomainValueException.class,
              e -> assertThat(e.code()).isEqualTo(DomainErrorCode.IDENTIFIER_MALFORMED));
    }
  }

  @ParameterizedTest
  @MethodSource("parsers")
  void rejectsNilUuidText(Function<String, Object> parse) {
    assertThatThrownBy(() -> parse.apply("00000000-0000-0000-0000-000000000000"))
        .isInstanceOfSatisfying(
            InvalidDomainValueException.class,
            e -> assertThat(e.code()).isEqualTo(DomainErrorCode.IDENTIFIER_NIL));
  }

  @org.junit.jupiter.api.Test
  void rejectsNilUuidValue() {
    UUID nil = new UUID(0, 0);
    assertThatThrownBy(() -> SeasonId.of(nil)).isInstanceOf(InvalidDomainValueException.class);
    assertThatThrownBy(() -> OperationId.of(nil)).isInstanceOf(InvalidDomainValueException.class);
    assertThatThrownBy(() -> PlayerId.of(nil)).isInstanceOf(InvalidDomainValueException.class);
  }

  @org.junit.jupiter.api.Test
  void equalityIsByValueAndIndependentOfConstructionPath() {
    UUID uuid = UUID.fromString(CANONICAL);
    assertThat(SeasonId.of(uuid)).isEqualTo(SeasonId.parse(CANONICAL));
    assertThat(SeasonId.of(uuid)).hasSameHashCodeAs(SeasonId.parse(CANONICAL));
    // Same UUID, different identifier kinds: never equal.
    assertThat((Object) SeasonId.of(uuid)).isNotEqualTo(OperationId.of(uuid));
  }

  @org.junit.jupiter.api.Test
  void orderingFollowsCanonicalText() {
    // UUID.compareTo compares signed longs and would order "8..." before "1..."; text order must
    // not.
    SeasonId low = SeasonId.parse("10000000-0000-0000-0000-000000000000");
    SeasonId high = SeasonId.parse("80000000-0000-0000-0000-000000000000");
    assertThat(low).isLessThan(high);
    assertThat(low.value().compareTo(high.value())).isPositive();
  }

  @org.junit.jupiter.api.Test
  void messagesDoNotEchoUnboundedInput() {
    String huge = "x".repeat(100_000);
    assertThatThrownBy(() -> SeasonId.parse(huge))
        .hasMessageContaining("100000 chars")
        .satisfies(e -> assertThat(e.getMessage()).hasSizeLessThan(300));
  }
}
