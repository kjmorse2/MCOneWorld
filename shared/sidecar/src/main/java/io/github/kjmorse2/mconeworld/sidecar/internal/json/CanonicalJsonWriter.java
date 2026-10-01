// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.internal.json;

import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonArray;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonBoolean;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonNull;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonNumber;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonObject;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonString;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Writes the single canonical byte form of a {@link JsonValue} (ADR 0003, and {@code
 * docs/formats/manifest-v1.md} §"Canonical encoding"):
 *
 * <ul>
 *   <li>UTF-8 without a BOM; LF line endings; exactly one trailing LF
 *   <li>two-space indentation; {@code "name": value}; every array element and object member on its
 *       own line; empty containers written as {@code {}} and {@code []}
 *   <li>object members sorted by Unicode code point order of their names
 *   <li>strings escape only {@code "} and {@code \}, and control characters below U+0020 (as {@code
 *       \n}, {@code \r}, {@code \t}, {@code \b}, {@code \f}, otherwise {@code \\u00xx} in lowercase
 *       hex); everything else is literal UTF-8
 *   <li>integers in minimal decimal form, so {@code -0} is written {@code 0}
 * </ul>
 *
 * <p>{@link #writeCompact} writes the same canonical form without any whitespace, on one line and
 * without a trailing LF: the form of one line of an append-only journal. Member order, string
 * escaping, and numbers are identical in both forms.
 *
 * <p>Output depends only on the value, never on locale, platform, or library version.
 */
public final class CanonicalJsonWriter {
  /**
   * Orders strings by Unicode code point, which differs from {@link String#compareTo} above the
   * BMP.
   */
  public static final Comparator<String> CODE_POINT_ORDER =
      (a, b) -> {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
          int ca = a.codePointAt(i);
          int cb = b.codePointAt(j);
          if (ca != cb) {
            return Integer.compare(ca, cb);
          }
          i += Character.charCount(ca);
          j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
      };

  private CanonicalJsonWriter() {}

  /** Returns the canonical UTF-8 bytes of {@code value}, ending with a single LF. */
  public static byte[] write(JsonValue value) {
    StringBuilder out = new StringBuilder();
    append(out, value, 0);
    out.append('\n');
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Returns the compact canonical UTF-8 bytes of {@code value}: no whitespace and no trailing LF.
   * The result never contains an LF byte, because strings escape it.
   */
  public static byte[] writeCompact(JsonValue value) {
    StringBuilder out = new StringBuilder();
    append(out, value, COMPACT);
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  /** Indentation level that selects the compact form. */
  private static final int COMPACT = -1;

  private static void append(StringBuilder out, JsonValue value, int indent) {
    switch (value) {
      case JsonObject o -> appendObject(out, o, indent);
      case JsonArray a -> appendArray(out, a, indent);
      case JsonString s -> appendString(out, s.value());
      case JsonNumber n -> out.append(n.value());
      case JsonBoolean b -> out.append(b.value());
      case JsonNull n -> out.append("null");
    }
  }

  private static void appendObject(StringBuilder out, JsonObject object, int indent) {
    if (object.members().isEmpty()) {
      out.append("{}");
      return;
    }
    List<Map.Entry<String, JsonValue>> members = new ArrayList<>(object.members().entrySet());
    members.sort(Map.Entry.comparingByKey(CODE_POINT_ORDER));
    if (indent == COMPACT) {
      out.append('{');
      for (int i = 0; i < members.size(); i++) {
        appendString(out, members.get(i).getKey());
        out.append(':');
        append(out, members.get(i).getValue(), COMPACT);
        out.append(i + 1 < members.size() ? "," : "");
      }
      out.append('}');
      return;
    }
    out.append("{\n");
    for (int i = 0; i < members.size(); i++) {
      indent(out, indent + 1);
      appendString(out, members.get(i).getKey());
      out.append(": ");
      append(out, members.get(i).getValue(), indent + 1);
      out.append(i + 1 < members.size() ? ",\n" : "\n");
    }
    indent(out, indent);
    out.append('}');
  }

  private static void appendArray(StringBuilder out, JsonArray array, int indent) {
    if (array.elements().isEmpty()) {
      out.append("[]");
      return;
    }
    if (indent == COMPACT) {
      out.append('[');
      for (int i = 0; i < array.elements().size(); i++) {
        append(out, array.elements().get(i), COMPACT);
        out.append(i + 1 < array.elements().size() ? "," : "");
      }
      out.append(']');
      return;
    }
    out.append("[\n");
    for (int i = 0; i < array.elements().size(); i++) {
      indent(out, indent + 1);
      append(out, array.elements().get(i), indent + 1);
      out.append(i + 1 < array.elements().size() ? ",\n" : "\n");
    }
    indent(out, indent);
    out.append(']');
  }

  private static void appendString(StringBuilder out, String s) {
    out.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        default -> {
          if (c < 0x20) {
            out.append("\\u00")
                .append(Character.forDigit(c >> 4, 16))
                .append(Character.forDigit(c & 0xF, 16));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }

  private static void indent(StringBuilder out, int level) {
    out.append("  ".repeat(level));
  }
}
