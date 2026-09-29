// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.manifest;

import io.github.kjmorse2.mconeworld.domain.ArchiveId;
import io.github.kjmorse2.mconeworld.domain.BackupId;
import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.DisplayName;
import io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException;
import io.github.kjmorse2.mconeworld.domain.OperationId;
import io.github.kjmorse2.mconeworld.domain.PlayerArchiveId;
import io.github.kjmorse2.mconeworld.domain.PlayerId;
import io.github.kjmorse2.mconeworld.domain.PortalMarkerId;
import io.github.kjmorse2.mconeworld.domain.ProtectedRegionId;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.archive.ArchiveCategory;
import io.github.kjmorse2.mconeworld.domain.archive.ArchiveState;
import io.github.kjmorse2.mconeworld.domain.archive.BackupRecord;
import io.github.kjmorse2.mconeworld.domain.archive.PlayerArchiveRecord;
import io.github.kjmorse2.mconeworld.domain.archive.PortalMarker;
import io.github.kjmorse2.mconeworld.domain.coordinate.BlockPosition;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkRectangle;
import io.github.kjmorse2.mconeworld.domain.coordinate.DimensionOrigin;
import io.github.kjmorse2.mconeworld.domain.provenance.ProtectedRegion;
import io.github.kjmorse2.mconeworld.domain.season.SeasonRecord;
import io.github.kjmorse2.mconeworld.domain.season.SeasonStatus;
import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import io.github.kjmorse2.mconeworld.domain.validation.IssueLocation;
import io.github.kjmorse2.mconeworld.domain.validation.Severity;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationIssue;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonArray;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonNull;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonNumber;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonObject;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonString;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarPath;
import io.github.kjmorse2.mconeworld.sidecar.storage.UnsafeSidecarPathException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Maps a parsed JSON tree to a {@link ManifestDocument}, collecting <em>every</em> problem it can
 * find rather than stopping at the first. Single use: create one per decode.
 *
 * <p>Strategy: each record is read field by field. A missing, mistyped, or invalid field records an
 * issue and yields {@code null}, and the record is skipped, but decoding continues so that later
 * problems are reported too. The document is built only if no error was found. Unknown members are
 * rejected in strict mode, or preserved when the file declares a newer minor version (see {@code
 * manifest-v1.md} §"Compatibility").
 */
final class ManifestDecoder {
  private static final Pattern TIMESTAMP =
      Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?Z");
  static final String EXTENSIONS = "extensions";

  private final List<ValidationIssue> issues = new ArrayList<>();
  private final Map<String, JsonObject> preserved = new TreeMap<>();
  private boolean preserveUnknown;
  private int errors;

  List<ValidationIssue> issues() {
    return issues;
  }

  /** Returns the document, or {@code null} if any error was recorded. */
  @Nullable ManifestDocument decode(JsonValue root) {
    IssueLocation at = IssueLocation.root();
    if (!(root instanceof JsonObject json)
        || !(json.get("format") instanceof JsonString format)
        || !format.value().equals(ManifestCodec.FORMAT)) {
      error(
          ManifestIssueCodes.NOT_A_MANIFEST,
          at.field("format"),
          "Not an MCOneWorld manifest: \"format\" must be \"" + ManifestCodec.FORMAT + "\"");
      return null;
    }
    Obj r = new Obj(json, at, "$");
    r.consume("format");
    String versionText = r.string("schemaVersion");
    if (versionText == null) {
      return null;
    }
    SchemaVersion version;
    try {
      version = SchemaVersion.parse(versionText);
    } catch (IllegalArgumentException e) {
      error(
          ManifestIssueCodes.INVALID_VALUE,
          at.field("schemaVersion"),
          "Malformed schema version; expected <major>.<minor>");
      return null;
    }
    if (version.major() != ManifestCodec.CURRENT.major()) {
      error(
          ManifestIssueCodes.UNSUPPORTED_VERSION,
          at.field("schemaVersion"),
          "Schema version "
              + version
              + " is not supported; this reader implements major "
              + ManifestCodec.CURRENT.major());
      return null;
    }
    if (version.minor() > ManifestCodec.CURRENT.minor()) {
      preserveUnknown = true;
      issues.add(
          ValidationIssue.warning(
                  ManifestIssueCodes.NEWER_MINOR_VERSION,
                  at.field("schemaVersion"),
                  "Written by schema "
                      + version
                      + "; this reader implements "
                      + ManifestCodec.CURRENT
                      + ". Unknown members are preserved but not interpreted.")
              .withRemediation("Upgrade MCOneWorld to interpret every member."));
    }

    ArchiveId archiveId = r.convert("archiveId", ArchiveId::parse);
    Instant createdAt = r.instant("createdAt");
    Long revision = r.integer("revision");
    if (revision != null && revision < 0) {
      error(
          ManifestIssueCodes.INVALID_VALUE, at.field("revision"), "Revision must not be negative");
    }
    Field<SeasonId> activeSeason = r.nullable("activeSeason", SeasonId::parse);

    List<SeasonRecord> seasons = r.list("seasons", this::season);
    List<ProtectedRegion> regions = r.list("protectedRegions", this::protectedRegion);
    List<PortalMarker> markers = r.list("portalMarkers", this::portalMarker);
    SortedMap<BackupId, SidecarPath> backupPaths = new TreeMap<>();
    List<BackupRecord> backups = r.list("backups", o -> backup(o, backupPaths));
    List<PlayerArchiveRecord> archives = r.list("playerArchives", this::playerArchive);
    List<FileReference> files = r.list("files", this::file);
    SortedMap<FeatureKey, FeatureRecord> features = features(r);
    r.finish(true);

    if (errors > 0
        || archiveId == null
        || createdAt == null
        || revision == null
        || !activeSeason.ok()) {
      return null;
    }
    ArchiveState state =
        new ArchiveState(seasons, activeSeason.value(), regions, markers, backups, archives);
    ManifestDocument document =
        new ManifestDocument(
            version,
            archiveId,
            createdAt,
            revision,
            state,
            backupPaths,
            files,
            features,
            new PreservedMembers(preserved));
    for (ValidationIssue issue : document.validate().issues()) {
      add(issue);
    }
    return errors > 0 ? null : document;
  }

  // --- records -----------------------------------------------------------------------------

  private @Nullable SeasonRecord season(Obj o) {
    String rawId = o.rawString("id");
    o.key = "seasons/" + rawId;
    SeasonId id = o.convert("id", SeasonId::parse);
    DisplayName name = o.convert("name", DisplayName::of);
    Instant createdAt = o.instant("createdAt");
    SeasonStatus status = o.enumValue("status", SeasonStatus.class);
    TreeMap<DimensionKey, DimensionOrigin> origins = new TreeMap<>();
    JsonObject originsJson = o.object("origins");
    if (originsJson != null) {
      for (Map.Entry<String, JsonValue> entry : originsJson.members().entrySet()) {
        IssueLocation at = o.at.field("origins").key(entry.getKey());
        DimensionKey dimension = domain(at, () -> DimensionKey.parse(entry.getKey()));
        if (!(entry.getValue() instanceof JsonObject originJson)) {
          wrongType(at, "object", entry.getValue());
          continue;
        }
        Obj origin = new Obj(originJson, at, o.key + "/origins/" + entry.getKey());
        Long x = origin.integer("x");
        Integer y = origin.intValue("y");
        Long z = origin.integer("z");
        origin.finish(false);
        if (dimension != null && x != null && y != null && z != null) {
          DimensionOrigin value = domain(at, () -> new DimensionOrigin(dimension, x, y, z));
          if (value != null) {
            origins.put(dimension, value);
          }
        }
      }
    }
    BlockPosition spawn = o.position("spawn", o.key + "/spawn");
    o.finish(true);
    if (id == null
        || name == null
        || createdAt == null
        || status == null
        || spawn == null
        || o.failed) {
      return null;
    }
    return domain(o.at, () -> new SeasonRecord(id, name, createdAt, status, origins, spawn));
  }

  private @Nullable ProtectedRegion protectedRegion(Obj o) {
    o.key = "protectedRegions/" + o.rawString("id");
    ProtectedRegionId id = o.convert("id", ProtectedRegionId::parse);
    DisplayName name = o.convert("name", DisplayName::of);
    Instant createdAt = o.instant("createdAt");
    ChunkRectangle area = null;
    JsonObject areaJson = o.object("area");
    if (areaJson != null) {
      Obj a = new Obj(areaJson, o.at.field("area"), o.key + "/area");
      DimensionKey dimension = a.convert("dimension", DimensionKey::parse);
      Integer minX = a.intValue("minX");
      Integer minZ = a.intValue("minZ");
      Integer maxX = a.intValue("maxX");
      Integer maxZ = a.intValue("maxZ");
      a.finish(false);
      if (dimension != null && minX != null && minZ != null && maxX != null && maxZ != null) {
        area = domain(a.at, () -> new ChunkRectangle(dimension, minX, minZ, maxX, maxZ));
      }
    }
    o.finish(true);
    ChunkRectangle finalArea = area;
    if (id == null || name == null || createdAt == null || finalArea == null || o.failed) {
      return null;
    }
    return domain(o.at, () -> new ProtectedRegion(id, name, finalArea, createdAt));
  }

  private @Nullable PortalMarker portalMarker(Obj o) {
    o.key = "portalMarkers/" + o.rawString("id");
    PortalMarkerId id = o.convert("id", PortalMarkerId::parse);
    DisplayName label = o.convert("label", DisplayName::of);
    BlockPosition destination = o.position("destination", o.key + "/destination");
    Field<SeasonId> season = o.nullable("destinationSeason", SeasonId::parse);
    Instant createdAt = o.instant("createdAt");
    o.finish(true);
    if (id == null
        || label == null
        || destination == null
        || !season.ok()
        || createdAt == null
        || o.failed) {
      return null;
    }
    return domain(o.at, () -> new PortalMarker(id, label, destination, season.value(), createdAt));
  }

  private @Nullable BackupRecord backup(Obj o, Map<BackupId, SidecarPath> paths) {
    o.key = "backups/" + o.rawString("id");
    BackupId id = o.convert("id", BackupId::parse);
    Instant createdAt = o.instant("createdAt");
    Checksum checksum = o.convert("checksum", Checksum::parse);
    Long size = o.integer("sizeBytes");
    Field<OperationId> operation = o.nullable("operation", OperationId::parse);
    Field<SeasonId> activeSeason = o.nullable("activeSeason", SeasonId::parse);
    SidecarPath path = o.convert("path", SidecarPath::of);
    o.finish(true);
    if (id == null
        || createdAt == null
        || checksum == null
        || size == null
        || !operation.ok()
        || !activeSeason.ok()
        || path == null
        || o.failed) {
      return null;
    }
    BackupRecord record =
        domain(
            o.at,
            () ->
                new BackupRecord(
                    id, createdAt, checksum, size, operation.value(), activeSeason.value()));
    if (record != null) {
      paths.put(id, path);
    }
    return record;
  }

  private @Nullable PlayerArchiveRecord playerArchive(Obj o) {
    o.key = "playerArchives/" + o.rawString("id");
    PlayerArchiveId id = o.convert("id", PlayerArchiveId::parse);
    PlayerId player = o.convert("player", PlayerId::parse);
    SeasonId season = o.convert("season", SeasonId::parse);
    Instant archivedAt = o.instant("archivedAt");
    Set<ArchiveCategory> categories = EnumSet.noneOf(ArchiveCategory.class);
    JsonArray categoriesJson = o.array("categories");
    if (categoriesJson != null) {
      for (int i = 0; i < categoriesJson.elements().size(); i++) {
        IssueLocation at = o.at.field("categories").index(i);
        ArchiveCategory category =
            enumFrom(categoriesJson.elements().get(i), ArchiveCategory.class, at);
        if (category != null && !categories.add(category)) {
          error(ManifestIssueCodes.INVALID_VALUE, at, "Category " + category + " is listed twice");
        }
      }
    }
    List<BlockPosition> containers = new ArrayList<>();
    JsonArray containersJson = o.array("physicalContainers");
    if (containersJson != null) {
      for (int i = 0; i < containersJson.elements().size(); i++) {
        IssueLocation at = o.at.field("physicalContainers").index(i);
        BlockPosition p =
            positionFrom(containersJson.elements().get(i), at, o.key + "/physicalContainers/" + i);
        if (p != null) {
          containers.add(p);
        }
      }
    }
    o.finish(true);
    if (id == null || player == null || season == null || archivedAt == null || o.failed) {
      return null;
    }
    return domain(
        o.at,
        () -> new PlayerArchiveRecord(id, player, season, archivedAt, categories, containers));
  }

  private @Nullable FileReference file(Obj o) {
    o.key = "files/" + o.rawString("path");
    String role = o.string("role");
    Field<DimensionKey> dimension = o.nullable("dimension", DimensionKey::parse);
    SidecarPath path = o.convert("path", SidecarPath::of);
    Checksum checksum = o.convert("checksum", Checksum::parse);
    Long size = o.integer("sizeBytes");
    o.finish(true);
    if (role == null
        || !dimension.ok()
        || path == null
        || checksum == null
        || size == null
        || o.failed) {
      return null;
    }
    return domain(o.at, () -> new FileReference(role, dimension.value(), path, checksum, size));
  }

  private SortedMap<FeatureKey, FeatureRecord> features(Obj root) {
    SortedMap<FeatureKey, FeatureRecord> features = new TreeMap<>();
    JsonObject json = root.object("features");
    if (json == null) {
      return features;
    }
    for (Map.Entry<String, JsonValue> entry : json.members().entrySet()) {
      IssueLocation at = root.at.field("features").key(entry.getKey());
      FeatureKey key = domain(at, () -> new FeatureKey(entry.getKey()));
      if (!(entry.getValue() instanceof JsonObject value)) {
        wrongType(at, "object", entry.getValue());
        continue;
      }
      Obj o = new Obj(value, at, "features/" + entry.getKey());
      Instant adoptedAt = o.instant("adoptedAt");
      String configurationVersion = o.string("configurationVersion");
      o.finish(true);
      if (key != null && adoptedAt != null && configurationVersion != null && !o.failed) {
        FeatureRecord record = domain(at, () -> new FeatureRecord(adoptedAt, configurationVersion));
        if (record != null) {
          features.put(key, record);
        }
      }
    }
    return features;
  }

  // --- shared helpers ------------------------------------------------------------------------

  private @Nullable BlockPosition positionFrom(JsonValue value, IssueLocation at, String key) {
    if (!(value instanceof JsonObject json)) {
      wrongType(at, "object", value);
      return null;
    }
    Obj p = new Obj(json, at, key);
    DimensionKey dimension = p.convert("dimension", DimensionKey::parse);
    Long x = p.integer("x");
    Integer y = p.intValue("y");
    Long z = p.integer("z");
    p.finish(false);
    if (dimension == null || x == null || y == null || z == null) {
      return null;
    }
    return domain(at, () -> new BlockPosition(dimension, x, y, z));
  }

  private <E extends Enum<E>> @Nullable E enumFrom(
      JsonValue value, Class<E> type, IssueLocation at) {
    if (!(value instanceof JsonString s)) {
      wrongType(at, "string", value);
      return null;
    }
    for (E constant : type.getEnumConstants()) {
      if (constant.name().equals(s.value())) {
        return constant;
      }
    }
    error(ManifestIssueCodes.INVALID_VALUE, at, "Unknown " + type.getSimpleName() + " value");
    return null;
  }

  /** Runs a domain constructor; a rejected value becomes an issue with the domain's own code. */
  private <T> @Nullable T domain(IssueLocation at, Supplier<T> construct) {
    try {
      return construct.get();
    } catch (InvalidDomainValueException e) {
      add(ValidationIssue.fromException(at, e));
    } catch (UnsafeSidecarPathException e) {
      add(
          ValidationIssue.error(
              UnsafeSidecarPathException.CODE, at, String.valueOf(e.getMessage())));
    }
    return null;
  }

  private void wrongType(IssueLocation at, String expected, JsonValue actual) {
    error(
        ManifestIssueCodes.WRONG_TYPE,
        at,
        "Expected " + expected + ", found " + JsonValue.typeName(actual));
  }

  private void error(IssueCode code, IssueLocation at, String message) {
    add(ValidationIssue.error(code, at, message));
  }

  private void add(ValidationIssue issue) {
    if (issue.severity() == Severity.ERROR) {
      errors++;
    }
    issues.add(issue);
  }

  /** A field read that may legitimately hold JSON {@code null}: {@code ok} is false on error. */
  private record Field<T>(boolean ok, @Nullable T value) {}

  /** A view over one JSON object that tracks which members were consumed. */
  private final class Obj {
    final JsonObject json;
    final IssueLocation at;
    String key;
    boolean failed;
    private final Set<String> consumed = new HashSet<>();

    Obj(JsonObject json, IssueLocation at, String key) {
      this.json = json;
      this.at = at;
      this.key = key;
    }

    void consume(String name) {
      consumed.add(name);
    }

    /** Raw text of a member for use in a preservation key; never reports errors. */
    String rawString(String name) {
      return json.get(name) instanceof JsonString s ? s.value() : "?";
    }

    @Nullable JsonValue member(String name) {
      consumed.add(name);
      JsonValue value = json.get(name);
      if (value == null) {
        error(
            ManifestIssueCodes.MISSING_MEMBER,
            at.field(name),
            "Missing required member \"" + name + "\"");
        failed = true;
      }
      return value;
    }

    @Nullable String string(String name) {
      JsonValue value = member(name);
      if (value == null) {
        return null;
      }
      if (value instanceof JsonString s) {
        return s.value();
      }
      wrongType(at.field(name), "string", value);
      failed = true;
      return null;
    }

    @Nullable Long integer(String name) {
      JsonValue value = member(name);
      if (value == null) {
        return null;
      }
      if (value instanceof JsonNumber n) {
        return n.value();
      }
      wrongType(at.field(name), "integer", value);
      failed = true;
      return null;
    }

    @Nullable Integer intValue(String name) {
      Long value = integer(name);
      if (value == null) {
        return null;
      }
      if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
        error(
            ManifestIssueCodes.INVALID_VALUE,
            at.field(name),
            "Value outside the 32-bit integer range");
        failed = true;
        return null;
      }
      return value.intValue();
    }

    @Nullable JsonObject object(String name) {
      JsonValue value = member(name);
      if (value == null) {
        return null;
      }
      if (value instanceof JsonObject o) {
        return o;
      }
      wrongType(at.field(name), "object", value);
      failed = true;
      return null;
    }

    @Nullable JsonArray array(String name) {
      JsonValue value = member(name);
      if (value == null) {
        return null;
      }
      if (value instanceof JsonArray a) {
        return a;
      }
      wrongType(at.field(name), "array", value);
      failed = true;
      return null;
    }

    <T> @Nullable T convert(String name, Function<String, T> parse) {
      String text = string(name);
      if (text == null) {
        return null;
      }
      T value = domain(at.field(name), () -> parse.apply(text));
      if (value == null) {
        failed = true;
      }
      return value;
    }

    <T> Field<T> nullable(String name, Function<String, T> parse) {
      JsonValue value = member(name);
      if (value == null) {
        return new Field<>(false, null);
      }
      if (value == JsonNull.INSTANCE) {
        return new Field<>(true, null);
      }
      if (!(value instanceof JsonString s)) {
        wrongType(at.field(name), "string or null", value);
        failed = true;
        return new Field<>(false, null);
      }
      T parsed = domain(at.field(name), () -> parse.apply(s.value()));
      if (parsed == null) {
        failed = true;
      }
      return new Field<>(parsed != null, parsed);
    }

    @Nullable Instant instant(String name) {
      String text = string(name);
      if (text == null) {
        return null;
      }
      if (TIMESTAMP.matcher(text).matches()) {
        try {
          return Instant.parse(text);
        } catch (DateTimeParseException e) {
          // fall through: pattern matched but the date does not exist (e.g. 2026-02-30)
        }
      }
      error(
          ManifestIssueCodes.INVALID_VALUE,
          at.field(name),
          "Timestamp must be UTC ISO-8601 like 2026-01-31T12:00:00Z");
      failed = true;
      return null;
    }

    <E extends Enum<E>> @Nullable E enumValue(String name, Class<E> type) {
      JsonValue value = member(name);
      if (value == null) {
        return null;
      }
      E result = enumFrom(value, type, at.field(name));
      if (result == null) {
        failed = true;
      }
      return result;
    }

    @Nullable BlockPosition position(String name, String preserveKey) {
      JsonValue value = member(name);
      if (value == null) {
        return null;
      }
      BlockPosition p = positionFrom(value, at.field(name), preserveKey);
      if (p == null) {
        failed = true;
      }
      return p;
    }

    <T> List<T> list(String name, RecordReader<T> reader) {
      List<T> out = new ArrayList<>();
      JsonArray array = array(name);
      if (array == null) {
        return out;
      }
      for (int i = 0; i < array.elements().size(); i++) {
        JsonValue element = array.elements().get(i);
        IssueLocation elementAt = at.field(name).index(i);
        if (!(element instanceof JsonObject object)) {
          wrongType(elementAt, "object", element);
          continue;
        }
        T record = reader.read(new Obj(object, elementAt, name + "/" + i));
        if (record != null) {
          out.add(record);
        }
      }
      return out;
    }

    /** Handles members that were never consumed: preserve or reject, per the version policy. */
    void finish(boolean extensionsAllowed) {
      Map<String, JsonValue> unknown = new LinkedHashMap<>();
      for (Map.Entry<String, JsonValue> entry : json.members().entrySet()) {
        String name = entry.getKey();
        if (consumed.contains(name)) {
          continue;
        }
        if (extensionsAllowed && name.equals(EXTENSIONS)) {
          extensions(entry.getValue());
        } else if (preserveUnknown) {
          unknown.put(name, entry.getValue());
        } else {
          error(
              ManifestIssueCodes.UNKNOWN_MEMBER,
              at.field(safeField(name)),
              "Unknown member \"" + name + "\" for schema " + ManifestCodec.CURRENT);
          failed = true;
        }
      }
      if (!unknown.isEmpty()) {
        preserved.put(key, new JsonObject(unknown));
      }
    }

    private void extensions(JsonValue value) {
      IssueLocation extAt = at.field(EXTENSIONS);
      if (!(value instanceof JsonObject ext)) {
        wrongType(extAt, "object", value);
        failed = true;
        return;
      }
      for (String extKey : ext.members().keySet()) {
        if (!FeatureKey.isValid(extKey)) {
          error(
              ManifestIssueCodes.INVALID_VALUE,
              extAt.key(extKey),
              "Extension keys must be namespaced, like vendor:name");
          failed = true;
        }
      }
      if (!ext.members().isEmpty()) {
        preserved.put(key + "#" + EXTENSIONS, ext);
      }
    }
  }

  @FunctionalInterface
  private interface RecordReader<T> {
    @Nullable T read(Obj object);
  }

  private static String safeField(String name) {
    return name.matches("[a-zA-Z][a-zA-Z0-9_]*") ? name : "_unknown_";
  }
}
