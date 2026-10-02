// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.protocol;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;

/**
 * Stable codes carried by {@link ProtocolException}. A public contract: never rename or repurpose
 * one. Platform adapters log them; they never disconnect a player because of them (root AGENTS.md
 * §15).
 */
public final class ProtocolIssueCodes {
  /** The payload ends before a field it must contain. */
  public static final IssueCode TRUNCATED = IssueCode.of("protocol.truncated");

  /** Bytes remain after the last field. */
  public static final IssueCode TRAILING_BYTES = IssueCode.of("protocol.trailing_bytes");

  /** The payload, a string, a collection, or an extension exceeds its limit. */
  public static final IssueCode TOO_LARGE = IssueCode.of("protocol.too_large");

  /** A varint is longer than allowed or not in its shortest form. */
  public static final IssueCode MALFORMED_VARINT = IssueCode.of("protocol.malformed_varint");

  /** A string is not well-formed UTF-8 or contains forbidden characters. */
  public static final IssueCode MALFORMED_TEXT = IssueCode.of("protocol.malformed_text");

  /** An identifier (dimension, capability, extension key) breaks its grammar. */
  public static final IssueCode INVALID_IDENTIFIER = IssueCode.of("protocol.invalid_identifier");

  /** A set or map is not in strictly ascending order, or repeats an entry. */
  public static final IssueCode NOT_CANONICAL = IssueCode.of("protocol.not_canonical");

  /** A number or flag is outside its allowed range. */
  public static final IssueCode OUT_OF_RANGE = IssueCode.of("protocol.out_of_range");

  /** The payload's layout byte names a layout this decoder does not know. */
  public static final IssueCode UNSUPPORTED_LAYOUT = IssueCode.of("protocol.unsupported_layout");

  /** A context reply contradicts the hello it answers (version or capabilities). */
  public static final IssueCode UNEXPECTED_REPLY = IssueCode.of("protocol.unexpected_reply");

  private ProtocolIssueCodes() {}
}
