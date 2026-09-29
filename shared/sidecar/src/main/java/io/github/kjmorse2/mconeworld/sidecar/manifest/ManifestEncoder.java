// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.manifest;

import io.github.kjmorse2.mconeworld.domain.archive.ArchiveCategory;
import io.github.kjmorse2.mconeworld.domain.archive.BackupRecord;
import io.github.kjmorse2.mconeworld.domain.archive.PlayerArchiveRecord;
import io.github.kjmorse2.mconeworld.domain.archive.PortalMarker;
import io.github.kjmorse2.mconeworld.domain.coordinate.BlockPosition;
import io.github.kjmorse2.mconeworld.domain.coordinate.DimensionOrigin;
import io.github.kjmorse2.mconeworld.domain.provenance.ProtectedRegion;
import io.github.kjmorse2.mconeworld.domain.season.SeasonRecord;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonArray;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonNull;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonNumber;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonObject;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonString;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarPath;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Maps a validated {@link ManifestDocument} to the JSON tree that {@code CanonicalJsonWriter}
 * serializes. Semantic array order comes from the document: seasons and records in append order,
 * files by path, categories in declaration order. Object member order is left to the canonical
 * writer, which sorts by name. Preserved members are merged back at their recorded locations, and
 * known members always win.
 */
final class ManifestEncoder {
  /** Smallest and largest instants representable as a 4-digit-year ISO-8601 timestamp. */
  static final Instant MIN_INSTANT = Instant.parse("0000-01-01T00:00:00Z");

  static final Instant MAX_INSTANT = Instant.parse("9999-12-31T23:59:59.999999999Z");

  private final PreservedMembers preserved;

  ManifestEncoder(PreservedMembers preserved) {
    this.preserved = preserved;
  }

  static boolean isEncodable(Instant instant) {
    return !instant.isBefore(MIN_INSTANT) && !instant.isAfter(MAX_INSTANT);
  }

  JsonObject encode(ManifestDocument doc, SchemaVersion writtenVersion) {
    Map<String, JsonValue> root = new LinkedHashMap<>();
    root.put("format", str(ManifestCodec.FORMAT));
    root.put("schemaVersion", str(writtenVersion.toString()));
    root.put("archiveId", str(doc.archiveId().toString()));
    root.put("createdAt", instant(doc.createdAt()));
    root.put("revision", new JsonNumber(doc.revision()));
    root.put("activeSeason", nullableStr(doc.state().activeSeasonId()));
    root.put("seasons", array(doc.state().seasons().stream().map(this::season).toList()));
    root.put(
        "protectedRegions",
        array(doc.state().protectedRegions().stream().map(this::region).toList()));
    root.put(
        "portalMarkers", array(doc.state().portalMarkers().stream().map(this::marker).toList()));
    root.put(
        "backups",
        array(
            doc.state().backups().stream()
                .map(b -> backup(b, doc.backupPaths().get(b.id())))
                .toList()));
    root.put(
        "playerArchives",
        array(doc.state().playerArchives().stream().map(this::playerArchive).toList()));
    root.put("files", array(doc.files().stream().map(this::file).toList()));
    Map<String, JsonValue> features = new LinkedHashMap<>();
    doc.features()
        .forEach(
            (key, record) -> {
              Map<String, JsonValue> f = new LinkedHashMap<>();
              f.put("adoptedAt", instant(record.adoptedAt()));
              f.put("configurationVersion", str(record.configurationVersion()));
              features.put(key.value(), object("features/" + key.value(), true, f));
            });
    root.put("features", new JsonObject(features));
    return object("$", true, root);
  }

  private JsonValue season(SeasonRecord s) {
    String key = "seasons/" + s.id();
    Map<String, JsonValue> m = new LinkedHashMap<>();
    m.put("id", str(s.id().toString()));
    m.put("name", str(s.name().value()));
    m.put("createdAt", instant(s.createdAt()));
    m.put("status", str(s.status().name()));
    Map<String, JsonValue> origins = new LinkedHashMap<>();
    for (DimensionOrigin origin : s.origins().values()) {
      Map<String, JsonValue> o = new LinkedHashMap<>();
      o.put("x", new JsonNumber(origin.x()));
      o.put("y", new JsonNumber(origin.y()));
      o.put("z", new JsonNumber(origin.z()));
      origins.put(
          origin.dimension().toString(), object(key + "/origins/" + origin.dimension(), false, o));
    }
    m.put("origins", new JsonObject(origins));
    m.put("spawn", position(s.spawn(), key + "/spawn"));
    return object(key, true, m);
  }

  private JsonValue region(ProtectedRegion r) {
    String key = "protectedRegions/" + r.id();
    Map<String, JsonValue> area = new LinkedHashMap<>();
    area.put("dimension", str(r.area().dimension().toString()));
    area.put("minX", new JsonNumber(r.area().minX()));
    area.put("minZ", new JsonNumber(r.area().minZ()));
    area.put("maxX", new JsonNumber(r.area().maxX()));
    area.put("maxZ", new JsonNumber(r.area().maxZ()));
    Map<String, JsonValue> m = new LinkedHashMap<>();
    m.put("id", str(r.id().toString()));
    m.put("name", str(r.name().value()));
    m.put("area", object(key + "/area", false, area));
    m.put("createdAt", instant(r.createdAt()));
    return object(key, true, m);
  }

  private JsonValue marker(PortalMarker p) {
    String key = "portalMarkers/" + p.id();
    Map<String, JsonValue> m = new LinkedHashMap<>();
    m.put("id", str(p.id().toString()));
    m.put("label", str(p.label().value()));
    m.put("destination", position(p.recordedDestination(), key + "/destination"));
    m.put("destinationSeason", nullableStr(p.destinationSeason()));
    m.put("createdAt", instant(p.createdAt()));
    return object(key, true, m);
  }

  private JsonValue backup(BackupRecord b, @Nullable SidecarPath path) {
    if (path == null) {
      throw new IllegalStateException("validate() guarantees every backup has a path");
    }
    String key = "backups/" + b.id();
    Map<String, JsonValue> m = new LinkedHashMap<>();
    m.put("id", str(b.id().toString()));
    m.put("createdAt", instant(b.createdAt()));
    m.put("checksum", str(b.checksum().toString()));
    m.put("sizeBytes", new JsonNumber(b.sizeBytes()));
    m.put("operation", nullableStr(b.operation()));
    m.put("activeSeason", nullableStr(b.activeSeason()));
    m.put("path", str(path.value()));
    return object(key, true, m);
  }

  private JsonValue playerArchive(PlayerArchiveRecord a) {
    String key = "playerArchives/" + a.id();
    Map<String, JsonValue> m = new LinkedHashMap<>();
    m.put("id", str(a.id().toString()));
    m.put("player", str(a.player().toString()));
    m.put("season", str(a.season().toString()));
    m.put("archivedAt", instant(a.archivedAt()));
    List<JsonValue> categories = new ArrayList<>();
    for (ArchiveCategory c : a.categories()) {
      categories.add(str(c.name()));
    }
    m.put("categories", array(categories));
    List<JsonValue> containers = new ArrayList<>();
    for (int i = 0; i < a.physicalContainers().size(); i++) {
      containers.add(position(a.physicalContainers().get(i), key + "/physicalContainers/" + i));
    }
    m.put("physicalContainers", array(containers));
    return object(key, true, m);
  }

  private JsonValue file(FileReference f) {
    String key = "files/" + f.path();
    Map<String, JsonValue> m = new LinkedHashMap<>();
    m.put("role", str(f.role()));
    m.put("dimension", nullableStr(f.dimension()));
    m.put("path", str(f.path().value()));
    m.put("checksum", str(f.checksum().toString()));
    m.put("sizeBytes", new JsonNumber(f.sizeBytes()));
    return object(key, true, m);
  }

  private JsonValue position(BlockPosition p, String key) {
    Map<String, JsonValue> m = new LinkedHashMap<>();
    m.put("dimension", str(p.dimension().toString()));
    m.put("x", new JsonNumber(p.x()));
    m.put("y", new JsonNumber(p.y()));
    m.put("z", new JsonNumber(p.z()));
    return object(key, false, m);
  }

  /** Builds an object, merging preserved unknown members and extensions recorded at {@code key}. */
  private JsonObject object(String key, boolean extensionsAllowed, Map<String, JsonValue> known) {
    Map<String, JsonValue> members = new LinkedHashMap<>();
    JsonObject unknown = preserved.at(key);
    if (unknown != null) {
      members.putAll(unknown.members());
    }
    JsonObject extensions =
        extensionsAllowed ? preserved.at(key + "#" + ManifestDecoder.EXTENSIONS) : null;
    if (extensions != null) {
      members.put(ManifestDecoder.EXTENSIONS, extensions);
    }
    members.putAll(known); // known members always win
    return new JsonObject(members);
  }

  private static JsonValue instant(Instant instant) {
    return str(DateTimeFormatter.ISO_INSTANT.format(instant));
  }

  private static JsonValue str(String s) {
    return new JsonString(s);
  }

  private static JsonValue nullableStr(@Nullable Object value) {
    return value == null ? JsonNull.INSTANCE : new JsonString(value.toString());
  }

  private static JsonArray array(List<JsonValue> elements) {
    return new JsonArray(elements);
  }
}
