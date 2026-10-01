// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.operation;

import io.github.kjmorse2.mconeworld.domain.BackupId;
import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.domain.operation.BackupReference;
import io.github.kjmorse2.mconeworld.domain.operation.ExpectedManifest;
import io.github.kjmorse2.mconeworld.domain.operation.FailureReason;
import io.github.kjmorse2.mconeworld.domain.operation.InputKey;
import io.github.kjmorse2.mconeworld.domain.operation.OperationText;
import io.github.kjmorse2.mconeworld.domain.operation.PreconditionSnapshot;
import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import io.github.kjmorse2.mconeworld.domain.validation.IssueLocation;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonReadLimits;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonNull;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonNumber;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonObject;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonString;
import io.github.kjmorse2.mconeworld.sidecar.manifest.SchemaVersion;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/** JSON encodings shared by plans and journal entries (operation-journal-v1.md §2). */
final class OperationJson {
  /** Smallest and largest instants representable as a 4-digit-year ISO-8601 timestamp. */
  private static final Instant MIN_INSTANT = Instant.parse("0000-01-01T00:00:00Z");

  private static final Instant MAX_INSTANT = Instant.parse("9999-12-31T23:59:59.999999999Z");

  private OperationJson() {}

  /** Read limits: generous for the largest legal plan, tight enough to bound memory. */
  static JsonReadLimits limits(long maxBytes) {
    return new JsonReadLimits(maxBytes, 16, 8192, OperationPlanLimits.MAX_ARRAY, 4096);
  }

  static JsonString str(Object value) {
    return new JsonString(value.toString());
  }

  static JsonValue nullable(@Nullable Object value) {
    return value == null ? JsonNull.INSTANCE : str(value);
  }

  static JsonNumber num(long value) {
    return new JsonNumber(value);
  }

  static JsonValue nullableNum(@Nullable Integer value) {
    return value == null ? JsonNull.INSTANCE : num(value);
  }

  /**
   * @throws IllegalArgumentException if the instant is outside years 0000–9999
   */
  static JsonString instant(Instant instant) {
    if (instant.isBefore(MIN_INSTANT) || instant.isAfter(MAX_INSTANT)) {
      throw new IllegalArgumentException("Timestamp " + instant + " is outside years 0000-9999");
    }
    return new JsonString(instant.toString());
  }

  /**
   * Checks {@code format} and {@code schemaVersion}: rejects another format or major version, and
   * marks a newer minor version so unknown members are tolerated.
   */
  static SchemaVersion header(
      JsonFields root, String format, SchemaVersion current, JsonFields.Context context)
      throws OperationFormatException {
    if (!root.string("format").equals(format)) {
      throw new OperationFormatException(
          JournalIssueCodes.WRONG_FORMAT, root.at("format"), "Expected format \"" + format + "\"");
    }
    SchemaVersion version = root.parse("schemaVersion", SchemaVersion::parse);
    if (version.major() != current.major()) {
      throw new OperationFormatException(
          JournalIssueCodes.UNSUPPORTED_VERSION,
          root.at("schemaVersion"),
          "Schema version " + version + " is not supported; this reader implements " + current);
    }
    context.newerMinor = version.minor() > current.minor();
    return version;
  }

  // --- precondition snapshot ----------------------------------------------------------------

  static JsonObject snapshot(PreconditionSnapshot s) {
    Map<String, JsonValue> m = new LinkedHashMap<>();
    m.put("manifest", manifest(s.manifest()));
    Map<String, JsonValue> checksums = new LinkedHashMap<>();
    s.checksums().forEach((k, v) -> checksums.put(k.value(), str(v)));
    m.put("checksums", new JsonObject(checksums));
    Map<String, JsonValue> facts = new LinkedHashMap<>();
    s.facts().forEach((k, v) -> facts.put(k.value(), str(v)));
    m.put("facts", new JsonObject(facts));
    return new JsonObject(m);
  }

  static PreconditionSnapshot snapshot(JsonFields o) throws OperationFormatException {
    ExpectedManifest manifest = manifest(o.object("manifest"));
    TreeMap<InputKey, Checksum> checksums = new TreeMap<>();
    for (Map.Entry<String, JsonValue> e : o.rawObject("checksums").members().entrySet()) {
      IssueLocation at = o.at("checksums").key(e.getKey());
      checksums.put(
          JsonFields.parseValue(at, e.getKey(), InputKey::of),
          JsonFields.parseValue(at, text(at, e.getValue()), Checksum::parse));
    }
    TreeMap<InputKey, String> facts = new TreeMap<>();
    for (Map.Entry<String, JsonValue> e : o.rawObject("facts").members().entrySet()) {
      IssueLocation at = o.at("facts").key(e.getKey());
      facts.put(JsonFields.parseValue(at, e.getKey(), InputKey::of), text(at, e.getValue()));
    }
    o.finish();
    TreeMap<InputKey, Checksum> c = checksums;
    TreeMap<InputKey, String> f = facts;
    return JsonFields.construct(o.at("facts"), () -> new PreconditionSnapshot(manifest, c, f));
  }

  private static JsonObject manifest(ExpectedManifest manifest) {
    Map<String, JsonValue> m = new LinkedHashMap<>();
    switch (manifest) {
      case ExpectedManifest.Absent a -> m.put("state", str("ABSENT"));
      case ExpectedManifest.AtRevision r -> {
        m.put("state", str("AT_REVISION"));
        m.put("revision", num(r.revision()));
        m.put("checksum", str(r.checksum()));
      }
    }
    return new JsonObject(m);
  }

  private static ExpectedManifest manifest(JsonFields o) throws OperationFormatException {
    String state = o.string("state");
    ExpectedManifest result =
        switch (state) {
          case "ABSENT" -> new ExpectedManifest.Absent();
          case "AT_REVISION" -> {
            long revision = o.integer("revision");
            Checksum checksum = o.parse("checksum", Checksum::parse);
            yield JsonFields.construct(
                o.at("revision"), () -> new ExpectedManifest.AtRevision(revision, checksum));
          }
          default ->
              throw new OperationFormatException(
                  JournalIssueCodes.INVALID_VALUE, o.at("state"), "Expected ABSENT or AT_REVISION");
        };
    o.finish();
    return result;
  }

  // --- small records --------------------------------------------------------------------------

  static JsonObject backup(BackupReference b) {
    Map<String, JsonValue> m = new LinkedHashMap<>();
    m.put("id", str(b.id()));
    m.put("checksum", str(b.checksum()));
    return new JsonObject(m);
  }

  static BackupReference backup(JsonFields o) throws OperationFormatException {
    BackupReference b =
        new BackupReference(o.parse("id", BackupId::parse), o.parse("checksum", Checksum::parse));
    o.finish();
    return b;
  }

  static JsonObject reason(FailureReason r) {
    Map<String, JsonValue> m = new LinkedHashMap<>();
    m.put("code", str(r.code()));
    m.put("message", str(r.message()));
    return new JsonObject(m);
  }

  static FailureReason reason(JsonFields o) throws OperationFormatException {
    FailureReason r =
        new FailureReason(o.parse("code", IssueCode::of), o.parse("message", OperationText::of));
    o.finish();
    return r;
  }

  private static String text(IssueLocation at, JsonValue value) throws OperationFormatException {
    if (value instanceof JsonString s) {
      return s.value();
    }
    throw JsonFields.wrongType(at, "string", value);
  }
}
