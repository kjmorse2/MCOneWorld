// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.manifest;

import io.github.kjmorse2.mconeworld.domain.DomainErrorCode;
import io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Archive-history metadata about a world-affecting feature. For example: when the global stronghold
 * placement was adopted, and which configuration version was used. The record states that something
 * happened to the world. It is not a live configuration switch.
 *
 * @param adoptedAt when the feature was adopted for this world, supplied by the caller
 * @param configurationVersion opaque version token of the adopted configuration: 1–32 characters of
 *     {@code [A-Za-z0-9._-]}, starting with a letter or digit
 */
public record FeatureRecord(Instant adoptedAt, String configurationVersion) {
  private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,31}");

  /**
   * @throws InvalidDomainValueException with {@code VALUE_INVALID} for a malformed token
   */
  public FeatureRecord {
    Objects.requireNonNull(adoptedAt, "adoptedAt");
    Objects.requireNonNull(configurationVersion, "configurationVersion");
    if (!TOKEN.matcher(configurationVersion).matches()) {
      throw new InvalidDomainValueException(
          DomainErrorCode.VALUE_INVALID, "Invalid configuration version token");
    }
  }
}
