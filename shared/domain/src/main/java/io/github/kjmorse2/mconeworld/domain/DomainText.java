// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain;

/** Formatting helpers for exception messages about untrusted input. */
final class DomainText {
  /** Longest prefix of an untrusted value that is echoed into an exception message. */
  static final int MAX_ECHOED_CHARS = 64;

  private DomainText() {}

  /** Bounds how much of an untrusted string reaches logs, so huge inputs cannot flood them. */
  static String truncateForMessage(String untrusted) {
    return untrusted.length() <= MAX_ECHOED_CHARS
        ? untrusted
        : untrusted.substring(0, MAX_ECHOED_CHARS) + "…(" + untrusted.length() + " chars)";
  }
}
