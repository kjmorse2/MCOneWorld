// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.operation;

import io.github.kjmorse2.mconeworld.domain.DomainErrorCode;
import io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException;
import io.github.kjmorse2.mconeworld.domain.TextRules;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Which program created a plan, for audit: for example {@code mconeworld-tool} {@code 0.1.0}. It
 * carries no host name, user name, or path.
 *
 * @param name lower-case program name, {@code [a-z0-9][a-z0-9._-]*}, at most 64 characters
 * @param version release version, {@code [A-Za-z0-9][A-Za-z0-9.+_-]*}, at most 64 characters
 */
public record ToolIdentity(String name, String version) {
  private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
  private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.+_-]{0,63}");

  /**
   * @throws InvalidDomainValueException with {@link DomainErrorCode#KEY_MALFORMED}
   */
  public ToolIdentity {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(version, "version");
    if (!NAME.matcher(name).matches() || !VERSION.matcher(version).matches()) {
      throw new InvalidDomainValueException(
          DomainErrorCode.KEY_MALFORMED,
          "Malformed tool identity \""
              + TextRules.excerpt(name)
              + "\" \""
              + TextRules.excerpt(version)
              + "\"");
    }
  }

  /** Equivalent to the constructor. */
  public static ToolIdentity of(String name, String version) {
    return new ToolIdentity(name, version);
  }

  @Override
  public String toString() {
    return name + " " + version;
  }
}
