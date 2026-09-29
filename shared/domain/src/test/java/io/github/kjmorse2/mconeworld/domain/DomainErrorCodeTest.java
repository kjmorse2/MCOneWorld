// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pins every error code string. Codes are a public contract, so a rename must fail here; adding a
 * code means extending this table.
 */
class DomainErrorCodeTest {

  @Test
  void codesAreStable() {
    Map<DomainErrorCode, String> expected = new LinkedHashMap<>();
    expected.put(DomainErrorCode.IDENTIFIER_MALFORMED, "domain.identifier.malformed");
    expected.put(DomainErrorCode.IDENTIFIER_NIL, "domain.identifier.nil");
    expected.put(DomainErrorCode.DIMENSION_MALFORMED, "domain.dimension.malformed");
    expected.put(DomainErrorCode.DIMENSION_MISMATCH, "domain.dimension.mismatch");
    expected.put(DomainErrorCode.COORDINATE_OUT_OF_RANGE, "domain.coordinate.out_of_range");
    expected.put(
        DomainErrorCode.SCALING_UNSUPPORTED_DIMENSION, "domain.scaling.unsupported_dimension");
    expected.put(DomainErrorCode.SCALING_NOT_EXACT, "domain.scaling.not_exact");
    expected.put(DomainErrorCode.ALIGNMENT_INVALID, "domain.alignment.invalid");
    expected.put(DomainErrorCode.BOUNDS_INVALID, "domain.bounds.invalid");

    assertThat(DomainErrorCode.values()).containsExactlyInAnyOrderElementsOf(expected.keySet());
    expected.forEach((code, text) -> assertThat(code.code()).isEqualTo(text));
  }

  @Test
  void codesAreUnique() {
    assertThat(Arrays.stream(DomainErrorCode.values()).map(DomainErrorCode::code))
        .doesNotHaveDuplicates();
  }
}
