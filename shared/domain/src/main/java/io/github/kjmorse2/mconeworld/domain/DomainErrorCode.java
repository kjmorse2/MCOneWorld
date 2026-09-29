// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain;

/**
 * Stable, machine-readable codes for rejected domain values.
 *
 * <p>The {@link #code()} strings are a public contract: automation, reports, and later structured
 * validation issues key on them, so they must never be renamed or reused for a different meaning.
 * Add new constants instead. Exception messages are for humans; these codes are for machines
 * (shared/AGENTS.md "Error handling and observability").
 */
public enum DomainErrorCode {
  /** An identifier string is not in canonical form (for UUIDs: 36-char lowercase hyphenated). */
  IDENTIFIER_MALFORMED("domain.identifier.malformed"),
  /** The nil UUID {@code 00000000-0000-0000-0000-000000000000} was used as an identifier. */
  IDENTIFIER_NIL("domain.identifier.nil"),
  /** A dimension identifier is not a valid, canonical namespaced identifier. */
  DIMENSION_MALFORMED("domain.dimension.malformed"),
  /** Two values that must refer to the same dimension refer to different dimensions. */
  DIMENSION_MISMATCH("domain.dimension.mismatch"),
  /** A coordinate lies outside the representation bounds in {@code CoordinateLimits}. */
  COORDINATE_OUT_OF_RANGE("domain.coordinate.out_of_range"),
  /** Overworld/Nether scaling was requested for a dimension it does not apply to. */
  SCALING_UNSUPPORTED_DIMENSION("domain.scaling.unsupported_dimension"),
  /** An exact Overworld-to-Nether conversion was requested for a non-multiple of 8. */
  SCALING_NOT_EXACT("domain.scaling.not_exact"),
  /** An alignment check was requested with a non-positive alignment. */
  ALIGNMENT_INVALID("domain.alignment.invalid"),
  /** World bounds are inverted, empty, or outside representable or vanilla limits. */
  BOUNDS_INVALID("domain.bounds.invalid");

  private final String code;

  DomainErrorCode(String code) {
    this.code = code;
  }

  /** Returns the stable dotted code string, e.g. {@code domain.dimension.malformed}. */
  public String code() {
    return code;
  }
}
