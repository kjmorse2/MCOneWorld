// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.validation;

/**
 * How serious a validation issue is. Declaration order is most severe first, and {@link
 * ValidationResult} sorts by it.
 */
public enum Severity {
  /** The data is unusable as-is. Mutating features must not proceed. */
  ERROR,
  /** The data is usable, but something deserves attention (for example a misaligned origin). */
  WARNING,
  /** Informational context with no action required. */
  INFO
}
