// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain;

import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A validated, namespaced dimension identifier such as {@code minecraft:overworld} or {@code
 * mymod:mining/deep}.
 *
 * <p>Custom dimensions are first-class. This is deliberately not an enum of the three vanilla
 * dimensions. Platform modules convert to and from Paper {@code NamespacedKey} and Minecraft {@code
 * Identifier}. Those types never appear here.
 *
 * <h2>Accepted grammar</h2>
 *
 * <p>The grammar is checked against the Minecraft 26.2 {@code net.minecraft.resources.Identifier}
 * character rules:
 *
 * <pre>
 *   key       = namespace ":" path
 *   namespace = 1*( a-z / 0-9 / "_" / "-" / "." )
 *   path      = segment *( "/" segment )
 *   segment   = 1*( a-z / 0-9 / "_" / "-" / "." )
 * </pre>
 *
 * <p>This is <em>stricter</em> than Minecraft in four deliberate ways:
 *
 * <ul>
 *   <li>The namespace is mandatory. There is no implicit {@code minecraft:}, so the persisted form
 *       is always the canonical form.
 *   <li>Path segments may not be empty, {@code "."}, or {@code ".."}. Neither may the namespace.
 *       Platform code maps dimension keys to directories, so these would permit path traversal or
 *       ambiguous normalization.
 *   <li>Length is bounded by {@link #MAX_LENGTH} at this trust boundary.
 *   <li>Upper case and whitespace are rejected, as in Minecraft, never lower-cased or trimmed.
 * </ul>
 *
 * <p>A real dimension whose identifier violates these extra rules is unsupported. It is reported,
 * never silently rewritten.
 *
 * <p>Ordering is by the full identifier text, which gives deterministic serialization order.
 *
 * @param namespacedId the canonical {@code namespace:path} text
 */
public record DimensionKey(String namespacedId) implements Comparable<DimensionKey> {

  /**
   * Maximum identifier length accepted, in characters. Minecraft itself imposes no explicit limit,
   * so this is an MCOneWorld trust-boundary bound. It is generous for real dimension names.
   */
  public static final int MAX_LENGTH = 256;

  /** The vanilla Overworld, {@code minecraft:overworld}. */
  public static final DimensionKey OVERWORLD = new DimensionKey("minecraft:overworld");

  /** The vanilla Nether, {@code minecraft:the_nether}. */
  public static final DimensionKey THE_NETHER = new DimensionKey("minecraft:the_nether");

  /** The vanilla End, {@code minecraft:the_end}. */
  public static final DimensionKey THE_END = new DimensionKey("minecraft:the_end");

  /**
   * @throws InvalidDomainValueException with {@link DomainErrorCode#DIMENSION_MALFORMED} if the
   *     text does not match the grammar in the class documentation
   */
  public DimensionKey {
    Objects.requireNonNull(namespacedId, "namespacedId");
    String problem = findProblem(namespacedId);
    if (problem != null) {
      throw new InvalidDomainValueException(
          DomainErrorCode.DIMENSION_MALFORMED,
          "Invalid dimension identifier \""
              + DomainText.truncateForMessage(namespacedId)
              + "\": "
              + problem);
    }
  }

  /** Parses a canonical identifier; equivalent to the constructor. */
  public static DimensionKey parse(String text) {
    return new DimensionKey(text);
  }

  /** Returns whether {@code text} would be accepted by {@link #parse(String)}. */
  public static boolean isValid(String text) {
    return findProblem(text) == null;
  }

  /** Returns the part before the {@code ':'}, for example {@code minecraft}. */
  public String namespace() {
    return namespacedId.substring(0, namespacedId.indexOf(':'));
  }

  /** Returns the part after the {@code ':'}, for example {@code overworld}. */
  public String path() {
    return namespacedId.substring(namespacedId.indexOf(':') + 1);
  }

  @Override
  public int compareTo(DimensionKey other) {
    return namespacedId.compareTo(other.namespacedId);
  }

  /** Returns the canonical {@code namespace:path} text. */
  @Override
  public String toString() {
    return namespacedId;
  }

  /** Returns a description of the first violation, or {@code null} if {@code text} is valid. */
  private static @Nullable String findProblem(String text) {
    if (text.isEmpty()) {
      return "empty";
    }
    if (text.length() > MAX_LENGTH) {
      return "longer than " + MAX_LENGTH + " characters";
    }
    int separator = text.indexOf(':');
    if (separator < 0) {
      return "missing ':' namespace separator (the namespace is mandatory)";
    }
    if (text.indexOf(':', separator + 1) >= 0) {
      return "more than one ':'";
    }
    String namespace = text.substring(0, separator);
    String path = text.substring(separator + 1);
    String namespaceProblem = segmentProblem(namespace, "namespace");
    if (namespaceProblem != null) {
      return namespaceProblem;
    }
    if (path.isEmpty()) {
      return "empty path";
    }
    // split with limit -1 keeps trailing empty segments, so "a/" is rejected.
    for (String segment : path.split("/", -1)) {
      String segmentProblem = segmentProblem(segment, "path segment");
      if (segmentProblem != null) {
        return segmentProblem;
      }
    }
    return null;
  }

  private static @Nullable String segmentProblem(String segment, String what) {
    if (segment.isEmpty()) {
      return "empty " + what;
    }
    if (segment.equals(".") || segment.equals("..")) {
      return what + " \"" + segment + "\" is not allowed";
    }
    for (int i = 0; i < segment.length(); i++) {
      char c = segment.charAt(i);
      boolean allowed =
          (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
      if (!allowed) {
        String hex = Integer.toHexString(c).toUpperCase(Locale.ROOT);
        return what + " contains disallowed character U+" + "0".repeat(4 - hex.length()) + hex;
      }
    }
    return null;
  }
}
