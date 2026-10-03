// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.backup;

import io.github.kjmorse2.mconeworld.domain.BackupId;
import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.CanonicalJsonWriter;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonReadException;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonReadLimits;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonReader;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonArray;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonNull;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonNumber;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonObject;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonString;
import io.github.kjmorse2.mconeworld.sidecar.storage.BackupEntryPath;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Reads and writes {@code backup.json}, format {@code mconeworld-backup} 1.0 ({@code
 * backup-v1.md}). Writing is canonical JSON (ADR 0003), so equal manifests give identical bytes and
 * the SHA-256 that the sidecar manifest records is stable. Reading is strict:
 *
 * <ul>
 *   <li>1.0: exactly the documented members
 *   <li>1.N, N &gt; 0: documented members validated, unknown ones ignored (a backup is never
 *       rewritten, so nothing needs preserving)
 *   <li>any other major: refused ({@code sidecar.backup.unsupported_version})
 * </ul>
 */
public final class BackupManifestCodec {
  public static final String FORMAT = "mconeworld-backup";

  public static final String VERSION = "1.0";

  /** Largest {@code backup.json} read: the JSON layer's hard maximum. */
  public static final long MAX_BYTES = JsonReadLimits.HARD_MAX_DOCUMENT_BYTES;

  private static final Pattern VERSION_PATTERN =
      Pattern.compile("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})");
  private static final Pattern TIMESTAMP =
      Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?Z");
  private static final Instant MIN_INSTANT = Instant.parse("0000-01-01T00:00:00Z");
  private static final Instant MAX_INSTANT = Instant.parse("9999-12-31T23:59:59.999999999Z");
  private static final Set<String> ROOT =
      Set.of(
          "format",
          "version",
          "id",
          "kind",
          "createdAt",
          "dimension",
          "sourceDataVersion",
          "sidecarRevision",
          "totalBytes",
          "entries");
  private static final Set<String> ENTRY = Set.of("path", "sizeBytes", "sha256");

  private final JsonReader reader =
      new JsonReader(
          new JsonReadLimits(MAX_BYTES, 8, 8192, JsonReadLimits.HARD_MAX_ARRAY_ELEMENTS, 64));

  /** The canonical bytes of {@code manifest}. */
  public byte[] encode(BackupManifest manifest) {
    if (manifest.createdAt().isBefore(MIN_INSTANT) || manifest.createdAt().isAfter(MAX_INSTANT)) {
      throw new IllegalArgumentException("createdAt is outside years 0000-9999");
    }
    Map<String, JsonValue> root = new LinkedHashMap<>();
    root.put("format", new JsonString(FORMAT));
    root.put("version", new JsonString(VERSION));
    root.put("id", new JsonString(manifest.id().toString()));
    root.put("kind", new JsonString(manifest.kind().name()));
    root.put(
        "createdAt", new JsonString(DateTimeFormatter.ISO_INSTANT.format(manifest.createdAt())));
    DimensionKey dimension = manifest.dimension();
    root.put(
        "dimension", dimension == null ? JsonNull.INSTANCE : new JsonString(dimension.toString()));
    Integer dataVersion = manifest.sourceDataVersion();
    root.put(
        "sourceDataVersion", dataVersion == null ? JsonNull.INSTANCE : new JsonNumber(dataVersion));
    Long revision = manifest.sidecarRevision();
    root.put("sidecarRevision", revision == null ? JsonNull.INSTANCE : new JsonNumber(revision));
    root.put("totalBytes", new JsonNumber(manifest.totalBytes()));
    List<JsonValue> entries = new ArrayList<>(manifest.entries().size());
    for (BackupEntry entry : manifest.entries()) {
      Map<String, JsonValue> e = new LinkedHashMap<>();
      e.put("path", new JsonString(entry.path().value()));
      e.put("sizeBytes", new JsonNumber(entry.sizeBytes()));
      e.put("sha256", new JsonString(entry.checksum().toString()));
      entries.add(new JsonObject(e));
    }
    root.put("entries", new JsonArray(entries));
    return CanonicalJsonWriter.write(new JsonObject(root));
  }

  /**
   * Decodes {@code bytes}.
   *
   * @throws BackupFormatException with a {@link BackupIssueCodes} code at the first problem
   */
  public BackupManifest decode(byte[] bytes) throws BackupFormatException {
    JsonValue tree;
    try {
      tree = reader.read(bytes);
    } catch (JsonReadException e) {
      throw new BackupFormatException(
          BackupIssueCodes.INVALID, "Not valid JSON: " + e.getMessage(), e);
    }
    if (!(tree instanceof JsonObject root)
        || !(root.get("format") instanceof JsonString format)
        || !format.value().equals(FORMAT)) {
      throw new BackupFormatException(
          BackupIssueCodes.NOT_A_BACKUP, "Not a " + FORMAT + " manifest");
    }
    String versionText = string(root, "version");
    Matcher version = VERSION_PATTERN.matcher(versionText);
    if (!version.matches()) {
      throw invalid("malformed version \"" + versionText + "\"");
    }
    if (!version.group(1).equals("1")) {
      throw new BackupFormatException(
          BackupIssueCodes.UNSUPPORTED_VERSION,
          "Backup manifest version " + versionText + " is not supported");
    }
    boolean strict = version.group(2).equals("0");
    members(root, ROOT, strict, "the manifest");
    try {
      BackupId id = BackupId.parse(string(root, "id"));
      BackupKind kind = kind(string(root, "kind"));
      Instant createdAt = instant(string(root, "createdAt"));
      String dimension = nullableString(root, "dimension");
      Long dataVersion = nullableInteger(root, "sourceDataVersion");
      Long revision = nullableInteger(root, "sidecarRevision");
      if (dataVersion != null && (dataVersion < 0 || dataVersion > Integer.MAX_VALUE)) {
        throw invalid("sourceDataVersion out of range");
      }
      long totalBytes = integer(root, "totalBytes");
      if (!(root.get("entries") instanceof JsonArray array)) {
        throw invalid("entries must be an array");
      }
      List<BackupEntry> entries = new ArrayList<>(array.elements().size());
      // Domain and path exceptions are IllegalArgumentExceptions, caught below.
      String previous = null;
      for (int i = 0; i < array.elements().size(); i++) {
        if (!(array.elements().get(i) instanceof JsonObject e)) {
          throw invalid("entries[" + i + "] must be an object");
        }
        members(e, ENTRY, strict, "entries[" + i + "]");
        String path = string(e, "path");
        if (previous != null && CanonicalJsonWriter.CODE_POINT_ORDER.compare(previous, path) >= 0) {
          throw invalid("entries are not in strictly ascending path order at " + i);
        }
        previous = path;
        entries.add(
            new BackupEntry(
                BackupEntryPath.of(path),
                integer(e, "sizeBytes"),
                Checksum.parse(string(e, "sha256"))));
      }
      BackupManifest manifest =
          new BackupManifest(
              id,
              kind,
              createdAt,
              dimension == null ? null : DimensionKey.parse(dimension),
              dataVersion == null ? null : dataVersion.intValue(),
              revision,
              entries);
      if (manifest.totalBytes() != totalBytes) {
        throw invalid(
            "totalBytes is " + totalBytes + " but the entries sum to " + manifest.totalBytes());
      }
      return manifest;
    } catch (IllegalArgumentException | ArithmeticException e) {
      throw new BackupFormatException(
          BackupIssueCodes.INVALID, "Invalid value: " + e.getMessage(), e);
    }
  }

  private static void members(JsonObject object, Set<String> known, boolean strict, String what)
      throws BackupFormatException {
    for (String name : known) {
      if (object.get(name) == null) {
        throw invalid(what + " has no \"" + name + "\"");
      }
    }
    if (strict) {
      for (String name : object.members().keySet()) {
        if (!known.contains(name)) {
          throw invalid(what + " has an unknown member \"" + name + "\"");
        }
      }
    }
  }

  private static String string(JsonObject o, String name) throws BackupFormatException {
    if (o.get(name) instanceof JsonString s) {
      return s.value();
    }
    throw invalid("\"" + name + "\" must be a string");
  }

  private static @Nullable String nullableString(JsonObject o, String name)
      throws BackupFormatException {
    return o.get(name) == JsonNull.INSTANCE ? null : string(o, name);
  }

  private static long integer(JsonObject o, String name) throws BackupFormatException {
    if (o.get(name) instanceof JsonNumber n && n.value() >= 0) {
      return n.value();
    }
    throw invalid("\"" + name + "\" must be a non-negative integer");
  }

  private static @Nullable Long nullableInteger(JsonObject o, String name)
      throws BackupFormatException {
    return o.get(name) == JsonNull.INSTANCE ? null : integer(o, name);
  }

  private static BackupKind kind(String text) throws BackupFormatException {
    for (BackupKind kind : BackupKind.values()) {
      if (kind.name().equals(text)) {
        return kind;
      }
    }
    throw invalid("unknown kind \"" + text + "\"");
  }

  private static Instant instant(String text) throws BackupFormatException {
    if (TIMESTAMP.matcher(text).matches()) {
      try {
        return Instant.parse(text);
      } catch (DateTimeParseException e) {
        // fall through: the pattern matched a date that does not exist
      }
    }
    throw invalid("timestamp must be UTC ISO-8601, like 2026-01-31T12:00:00Z");
  }

  private static BackupFormatException invalid(String message) {
    return new BackupFormatException(BackupIssueCodes.INVALID, message);
  }
}
