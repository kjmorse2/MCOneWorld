// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.operation;

import io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException;
import io.github.kjmorse2.mconeworld.domain.validation.IssueLocation;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonArray;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonBoolean;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonNull;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonNumber;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonObject;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonString;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A fail-fast reader over one JSON object: every accessor either returns a valid value or throws
 * {@link OperationFormatException} with a precise location. {@link #finish()} applies the
 * unknown-member policy: rejected at the reader's own version or older, recorded (and so preserved
 * in digests and checksums, which cover the parsed tree) for a newer minor version.
 */
final class JsonFields {
  private static final Pattern TIMESTAMP =
      Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?Z");

  /** Decoding state shared by every object of one document. */
  static final class Context {
    boolean newerMinor;
    final List<String> unknownMembers = new ArrayList<>();
  }

  /** A domain parse that may reject its input. */
  @FunctionalInterface
  interface Parser<T> {
    T parse(String text);
  }

  private final JsonObject json;
  private final IssueLocation at;
  private final Context context;
  private final Set<String> consumed = new HashSet<>();

  private JsonFields(JsonObject json, IssueLocation at, Context context) {
    this.json = json;
    this.at = at;
    this.context = context;
  }

  static JsonFields of(JsonValue value, IssueLocation at, Context context)
      throws OperationFormatException {
    if (!(value instanceof JsonObject object)) {
      throw wrongType(at, "object", value);
    }
    return new JsonFields(object, at, context);
  }

  Context context() {
    return context;
  }

  IssueLocation at(String name) {
    return at.field(name);
  }

  JsonValue member(String name) throws OperationFormatException {
    consumed.add(name);
    JsonValue value = json.get(name);
    if (value == null) {
      throw new OperationFormatException(
          JournalIssueCodes.MISSING_MEMBER, at.field(name), "Missing member \"" + name + "\"");
    }
    return value;
  }

  boolean isNull(String name) throws OperationFormatException {
    return member(name) == JsonNull.INSTANCE;
  }

  String string(String name) throws OperationFormatException {
    JsonValue value = member(name);
    if (value instanceof JsonString s) {
      return s.value();
    }
    throw wrongType(at.field(name), "string", value);
  }

  long integer(String name) throws OperationFormatException {
    JsonValue value = member(name);
    if (value instanceof JsonNumber n) {
      return n.value();
    }
    throw wrongType(at.field(name), "integer", value);
  }

  /** An integer in {@code min..max}. */
  int intIn(String name, int min, int max) throws OperationFormatException {
    long value = integer(name);
    if (value < min || value > max) {
      throw new OperationFormatException(
          JournalIssueCodes.INVALID_VALUE,
          at.field(name),
          "Must be in " + min + ".." + max + ", found " + value);
    }
    return (int) value;
  }

  /** An integer in {@code min..max}, or JSON null. */
  @Nullable Integer nullableIntIn(String name, int min, int max) throws OperationFormatException {
    return isNull(name) ? null : intIn(name, min, max);
  }

  boolean bool(String name) throws OperationFormatException {
    JsonValue value = member(name);
    if (value instanceof JsonBoolean b) {
      return b.value();
    }
    throw wrongType(at.field(name), "boolean", value);
  }

  JsonFields object(String name) throws OperationFormatException {
    return of(member(name), at.field(name), context);
  }

  /** The raw members of an object-valued member, for maps keyed by domain values. */
  JsonObject rawObject(String name) throws OperationFormatException {
    JsonValue value = member(name);
    if (value instanceof JsonObject o) {
      return o;
    }
    throw wrongType(at.field(name), "object", value);
  }

  List<JsonValue> array(String name) throws OperationFormatException {
    JsonValue value = member(name);
    if (value instanceof JsonArray a) {
      return a.elements();
    }
    throw wrongType(at.field(name), "array", value);
  }

  <T> T parse(String name, Parser<T> parser) throws OperationFormatException {
    return parseValue(at.field(name), string(name), parser);
  }

  <T> @Nullable T nullableParse(String name, Parser<T> parser) throws OperationFormatException {
    return isNull(name) ? null : parse(name, parser);
  }

  Instant instant(String name) throws OperationFormatException {
    String text = string(name);
    if (TIMESTAMP.matcher(text).matches()) {
      try {
        return Instant.parse(text);
      } catch (DateTimeParseException e) {
        // fall through: the pattern matched a date that does not exist, such as 2026-02-30
      }
    }
    throw new OperationFormatException(
        JournalIssueCodes.INVALID_VALUE,
        at.field(name),
        "Timestamp must be UTC ISO-8601 like 2026-01-31T12:00:00Z");
  }

  <E extends Enum<E>> E enumValue(String name, Class<E> type) throws OperationFormatException {
    String text = string(name);
    for (E constant : type.getEnumConstants()) {
      if (constant.name().equals(text)) {
        return constant;
      }
    }
    throw new OperationFormatException(
        JournalIssueCodes.INVALID_VALUE,
        at.field(name),
        "Unknown " + type.getSimpleName() + " value");
  }

  /** Applies the unknown-member policy to every member not read. */
  void finish() throws OperationFormatException {
    for (String name : json.members().keySet()) {
      if (consumed.contains(name)) {
        continue;
      }
      IssueLocation where = at.key(name);
      if (!context.newerMinor) {
        throw new OperationFormatException(
            JournalIssueCodes.UNKNOWN_MEMBER, where, "Unknown member for this schema version");
      }
      context.unknownMembers.add(where.path());
    }
  }

  static <T> T parseValue(IssueLocation at, String text, Parser<T> parser)
      throws OperationFormatException {
    return construct(at, () -> parser.parse(text));
  }

  /** Runs a domain constructor; a rejected value becomes a format error with the domain's code. */
  static <T> T construct(IssueLocation at, Supplier<T> constructor)
      throws OperationFormatException {
    try {
      return constructor.get();
    } catch (InvalidDomainValueException e) {
      throw new OperationFormatException(
          io.github.kjmorse2.mconeworld.domain.validation.IssueCode.of(e.code()),
          at,
          String.valueOf(e.getMessage()));
    } catch (IllegalArgumentException e) {
      throw new OperationFormatException(
          JournalIssueCodes.INVALID_VALUE, at, String.valueOf(e.getMessage()));
    }
  }

  static OperationFormatException wrongType(IssueLocation at, String expected, JsonValue actual) {
    return new OperationFormatException(
        JournalIssueCodes.WRONG_TYPE,
        at,
        "Expected " + expected + ", found " + JsonValue.typeName(actual));
  }
}
