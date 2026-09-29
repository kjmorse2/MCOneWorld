// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.internal.json;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Immutable JSON tree restricted to the MCOneWorld profile: numbers are always {@code long}.
 * Objects keep source member order, which gives error locations, but equality ignores order, and
 * the canonical writer sorts members.
 */
public sealed interface JsonValue {

  /** A JSON object. The member map is unmodifiable and iterates in insertion order. */
  record JsonObject(Map<String, JsonValue> members) implements JsonValue {
    public JsonObject {
      members = Collections.unmodifiableMap(new LinkedHashMap<>(members));
    }

    /** Returns the member, or {@code null} if absent. JSON {@code null} is {@link JsonNull}. */
    public @Nullable JsonValue get(String name) {
      return members.get(name);
    }
  }

  /** A JSON array; the element list is unmodifiable. */
  record JsonArray(List<JsonValue> elements) implements JsonValue {
    public JsonArray {
      elements = List.copyOf(elements);
    }
  }

  /** A JSON string. The value contains no unpaired surrogates. */
  record JsonString(String value) implements JsonValue {
    public JsonString {
      Objects.requireNonNull(value, "value");
    }
  }

  /** A JSON integer within {@code long} range. */
  record JsonNumber(long value) implements JsonValue {}

  /** {@code true} or {@code false}. */
  record JsonBoolean(boolean value) implements JsonValue {}

  /** JSON {@code null}. */
  enum JsonNull implements JsonValue {
    INSTANCE
  }

  /** A short type name for error messages. */
  static String typeName(JsonValue value) {
    return switch (value) {
      case JsonObject o -> "object";
      case JsonArray a -> "array";
      case JsonString s -> "string";
      case JsonNumber n -> "integer";
      case JsonBoolean b -> "boolean";
      case JsonNull n -> "null";
    };
  }
}
