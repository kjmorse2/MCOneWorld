// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.migration;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import io.github.kjmorse2.mconeworld.domain.validation.IssueLocation;
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
import io.github.kjmorse2.mconeworld.sidecar.manifest.SchemaVersion;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * <b>Test-only</b> migration from the synthetic, never-released manifest 0.9 ({@code
 * docs/formats/manifest-v0.9-synthetic.md}) to 1.0. It exists to exercise the framework.
 */
final class SyntheticManifest09To10 implements ManifestMigration {
  static final MigrationStep STEP =
      new MigrationStep(
          new MigrationId("manifest-0.9-to-1.0"),
          new SchemaVersion(0, 9),
          new SchemaVersion(1, 0),
          List.of(
              "Only documented 0.9 members are present",
              "experimental is absent or empty",
              "Each season lists each dimension's origin at most once",
              "extensions does not already contain mconeworld:legacy_notes when notes is present"));

  static final IssueCode NO_ACTIVE_SEASON =
      IssueCode.of("sidecar.migration.warning.no_active_season");
  private static final Set<String> DOCUMENTED =
      Set.of(
          "format",
          "schemaVersion",
          "archive",
          "created",
          "revision",
          "active",
          "seasons",
          "portals",
          "notes",
          "extensions",
          "experimental");
  private static final String NOTES_KEY = "mconeworld:legacy_notes";
  private static final JsonReader READER =
      new JsonReader(new JsonReadLimits(1 << 24, 32, 8192, 100_000, 10_000));

  @Override
  public MigrationStep step() {
    return STEP;
  }

  @Override
  public byte[] migrate(byte[] source, MigrationRecorder recorder) throws MigrationException {
    JsonObject in;
    try {
      in = (JsonObject) READER.read(source);
    } catch (JsonReadException | ClassCastException e) {
      throw recorder.prerequisiteFailed("source is not a JSON object");
    }
    for (String name : in.members().keySet()) {
      if (!DOCUMENTED.contains(name)) {
        throw recorder.prerequisiteFailed(
            "undocumented member \"" + name + "\" cannot be interpreted");
      }
    }
    if (in.get("experimental") instanceof JsonObject experimental
        && !experimental.members().isEmpty()) {
      recorder.unsupported("$.experimental", "experimental settings have no 1.0 representation");
    }

    Map<String, JsonValue> out = new LinkedHashMap<>();
    out.put("format", in.members().get("format"));
    out.put("schemaVersion", new JsonString("1.0"));
    recorder.transformed("$.schemaVersion", "0.9 -> 1.0");
    out.put("archiveId", in.members().get("archive"));
    recorder.transformed("$.archive", "renamed to $.archiveId");
    out.put("createdAt", iso(in.get("created")));
    recorder.transformed("$.created", "epoch milliseconds -> ISO-8601 UTC $.createdAt");
    out.put("revision", new JsonNumber(((JsonNumber) in.members().get("revision")).value() + 1));
    recorder.transformed("$.revision", "incremented by 1 for the migration commit");
    JsonValue active = in.get("active");
    out.put("activeSeason", active == null ? JsonNull.INSTANCE : active);
    recorder.transformed("$.active", "renamed to $.activeSeason; absent becomes null");
    if (active == null) {
      recorder.warning(
          NO_ACTIVE_SEASON,
          IssueLocation.root().field("active"),
          "The legacy manifest selects no active season");
    }

    List<JsonValue> seasons = new ArrayList<>();
    JsonArray inSeasons = (JsonArray) in.members().get("seasons");
    for (int i = 0; i < inSeasons.elements().size(); i++) {
      JsonObject s = (JsonObject) inSeasons.elements().get(i);
      Map<String, JsonValue> season = new LinkedHashMap<>();
      season.put("id", s.members().get("id"));
      season.put("name", s.members().get("name"));
      season.put("createdAt", iso(s.get("created")));
      season.put(
          "status",
          new JsonString(((JsonString) s.members().get("state")).value().toUpperCase(Locale.ROOT)));
      season.put("spawn", s.members().get("spawn"));
      Map<String, JsonValue> origins = new LinkedHashMap<>();
      for (JsonValue o : ((JsonArray) s.members().get("origins")).elements()) {
        JsonObject origin = (JsonObject) o;
        String dimension = ((JsonString) origin.members().get("dimension")).value();
        if (origins.containsKey(dimension)) {
          recorder.unsupported(
              "$.seasons[" + i + "].origins",
              "two origins for " + dimension + "; 1.0 allows one per dimension");
        }
        Map<String, JsonValue> xyz = new LinkedHashMap<>();
        xyz.put("x", origin.members().get("x"));
        xyz.put("y", origin.members().get("y"));
        xyz.put("z", origin.members().get("z"));
        origins.put(dimension, new JsonObject(xyz));
      }
      season.put("origins", new JsonObject(origins));
      seasons.add(new JsonObject(season));
    }
    out.put("seasons", new JsonArray(seasons));
    recorder.transformed(
        "$.seasons[*].created", "renamed to createdAt, epoch milliseconds -> ISO-8601 UTC");
    recorder.transformed("$.seasons[*].state", "renamed to status, lower -> upper case");
    recorder.transformed("$.seasons[*].origins", "array -> object keyed by dimension");

    List<JsonValue> markers = new ArrayList<>();
    JsonValue portals = in.get("portals");
    if (portals instanceof JsonArray portalArray) {
      for (JsonValue p : portalArray.elements()) {
        JsonObject portal = (JsonObject) p;
        Map<String, JsonValue> marker = new LinkedHashMap<>();
        marker.put("id", portal.members().get("id"));
        marker.put("label", portal.members().get("label"));
        marker.put("destination", portal.members().get("destination"));
        marker.put("destinationSeason", JsonNull.INSTANCE);
        marker.put("createdAt", iso(portal.get("created")));
        markers.add(new JsonObject(marker));
      }
    }
    out.put("portalMarkers", new JsonArray(markers));
    recorder.transformed(
        "$.portals", "renamed to $.portalMarkers; destinationSeason = null; created -> createdAt");

    for (String empty : List.of("protectedRegions", "backups", "playerArchives", "files")) {
      out.put(empty, new JsonArray(List.of()));
      recorder.transformed("$." + empty, "added (empty); not present in 0.9");
    }
    out.put("features", new JsonObject(Map.of()));
    recorder.transformed("$.features", "added (empty); not present in 0.9");

    Map<String, JsonValue> extensions = new LinkedHashMap<>();
    if (in.get("extensions") instanceof JsonObject inExtensions) {
      for (Map.Entry<String, JsonValue> e : inExtensions.members().entrySet()) {
        extensions.put(e.getKey(), e.getValue());
        recorder.preservedExtension(e.getKey());
      }
    }
    if (in.get("notes") instanceof JsonString notes) {
      if (extensions.containsKey(NOTES_KEY)) {
        recorder.unsupported("$.notes", "extensions already contains " + NOTES_KEY);
      }
      extensions.put(NOTES_KEY, notes);
      recorder.transformed("$.notes", "moved to $.extensions[\"" + NOTES_KEY + "\"]");
    }
    if (!extensions.isEmpty()) {
      out.put("extensions", new JsonObject(extensions));
    }
    recorder.failIfUnsupported();
    return CanonicalJsonWriter.write(new JsonObject(out));
  }

  private static JsonValue iso(JsonValue epochMillis) {
    return new JsonString(
        DateTimeFormatter.ISO_INSTANT.format(
            Instant.ofEpochMilli(((JsonNumber) epochMillis).value())));
  }
}
