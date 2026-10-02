// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index.bench;

import java.util.Locale;

/** Builds one compact JSON object, members in insertion order. Enough for result lines. */
final class JsonLine {
  private final StringBuilder out = new StringBuilder("{");

  JsonLine put(String name, String value) {
    key(name);
    string(value);
    return this;
  }

  JsonLine put(String name, long value) {
    key(name);
    out.append(value);
    return this;
  }

  JsonLine put(String name, double value) {
    key(name);
    out.append(Double.isFinite(value) ? String.format(Locale.ROOT, "%.6g", value).trim() : "null");
    return this;
  }

  JsonLine putRaw(String name, String json) {
    key(name);
    out.append(json);
    return this;
  }

  @Override
  public String toString() {
    return out + "}";
  }

  private void key(String name) {
    if (out.length() > 1) {
      out.append(',');
    }
    string(name);
    out.append(':');
  }

  private void string(String s) {
    out.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '"' || c == '\\') {
        out.append('\\').append(c);
      } else if (c < 0x20) {
        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
      } else {
        out.append(c);
      }
    }
    out.append('"');
  }
}
