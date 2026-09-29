// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kjmorse2.mconeworld.domain.DomainErrorCode;
import io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ValidationTest {
  private static final IssueLocation ROOT = IssueLocation.root();

  @ParameterizedTest
  @ValueSource(strings = {"archive.season.duplicate_id", "a.b", "a1.b_2.c3"})
  void acceptsDottedLowercaseCodes(String code) {
    assertThat(IssueCode.of(code).value()).isEqualTo(code);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "single", "A.b", "a..b", ".a.b", "a.b.", "a.1b", "a-b.c", "a.b c"})
  void rejectsMalformedCodes(String code) {
    assertThatThrownBy(() -> IssueCode.of(code))
        .isInstanceOfSatisfying(
            InvalidDomainValueException.class,
            e -> assertThat(e.code()).isEqualTo(DomainErrorCode.ISSUE_CODE_MALFORMED));
  }

  @Test
  void everyDomainErrorCodeIsAValidIssueCode() {
    for (DomainErrorCode code : DomainErrorCode.values()) {
      assertThat(IssueCode.of(code).value()).isEqualTo(code.code());
    }
  }

  @Test
  void locationsRenderConsistently() {
    assertThat(ROOT.field("seasons").key("abc").field("origins").key("minecraft:the_end").index(2))
        .hasToString("$.seasons[abc].origins[minecraft:the_end][2]");
    assertThat(ROOT.key("a]b\\c")).hasToString("$[a\\]b\\\\c]");
    assertThatThrownBy(() -> ROOT.field("bad name")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ROOT.index(-1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void issueOrderIsDeterministicRegardlessOfDiscoveryOrder() {
    List<ValidationIssue> issues =
        List.of(
            ValidationIssue.warning(IssueCode.of("b.warn"), ROOT.field("z"), "w1"),
            ValidationIssue.error(IssueCode.of("b.err"), ROOT.field("z"), "e2"),
            ValidationIssue.error(IssueCode.of("a.err"), ROOT.field("z"), "e1"),
            ValidationIssue.error(IssueCode.of("c.err"), ROOT.field("a"), "e0"),
            ValidationIssue.of(IssueCode.of("a.info"), Severity.INFO, ROOT, "i"));
    List<ValidationIssue> expected = ValidationResult.of(issues).issues();

    Random random = new Random(42); // seeded: deterministic shuffles
    for (int i = 0; i < 50; i++) {
      List<ValidationIssue> shuffled = new ArrayList<>(issues);
      Collections.shuffle(shuffled, random);
      assertThat(ValidationResult.of(shuffled).issues()).containsExactlyElementsOf(expected);
    }
    // Errors first, then by location, then by code.
    assertThat(expected)
        .extracting(i -> i.code().value())
        .containsExactly("c.err", "a.err", "b.err", "b.warn", "a.info");
  }

  @Test
  void causeIsDiagnosticOnlyAndExcludedFromEquality() {
    ValidationIssue plain = ValidationIssue.error(IssueCode.of("x.y"), ROOT, "m");
    ValidationIssue withCause = plain.withCause(new IllegalStateException("stack"));
    assertThat(withCause).isEqualTo(plain).hasSameHashCodeAs(plain);
    assertThat(withCause.cause()).isInstanceOf(IllegalStateException.class);
    assertThat(plain.withRemediation("fix it")).isNotEqualTo(plain);
  }

  @Test
  void fromExceptionKeepsTheStableCode() {
    InvalidDomainValueException e =
        new InvalidDomainValueException(DomainErrorCode.DIMENSION_MALFORMED, "bad");
    ValidationIssue issue = ValidationIssue.fromException(ROOT.field("dimension"), e);
    assertThat(issue.code().value()).isEqualTo("domain.dimension.malformed");
    assertThat(issue.severity()).isEqualTo(Severity.ERROR);
    assertThat(issue.cause()).isSameAs(e);
  }

  @Test
  void textIsBounded() {
    ValidationIssue issue = ValidationIssue.error(IssueCode.of("x.y"), ROOT, "m".repeat(10_000));
    assertThat(issue.message()).hasSizeLessThanOrEqualTo(ValidationIssue.MAX_TEXT_LENGTH + 1);
  }

  @Test
  void warningsDoNotInvalidateButErrorsDo() {
    ValidationResult warningsOnly =
        ValidationResult.of(List.of(ValidationIssue.warning(IssueCode.of("x.w"), ROOT, "w")));
    assertThat(warningsOnly.isValid()).isTrue();
    assertThat(warningsOnly.requireValid()).isSameAs(warningsOnly);

    ValidationResult withError =
        warningsOnly.plus(
            ValidationResult.of(List.of(ValidationIssue.error(IssueCode.of("x.e"), ROOT, "e"))));
    assertThat(withError.isValid()).isFalse();
    assertThat(withError.has(IssueCode.of("x.e"))).isTrue();
    assertThatThrownBy(withError::requireValid)
        .isInstanceOfSatisfying(
            ValidationException.class, e -> assertThat(e.result()).isEqualTo(withError));
  }

  @Test
  void resultIsImmutableAndUnaliased() {
    List<ValidationIssue> source = new ArrayList<>();
    source.add(ValidationIssue.error(IssueCode.of("x.e"), ROOT, "e"));
    ValidationResult result = ValidationResult.of(source);
    source.clear();
    assertThat(result.issues()).hasSize(1);
    assertThatThrownBy(() -> result.issues().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
