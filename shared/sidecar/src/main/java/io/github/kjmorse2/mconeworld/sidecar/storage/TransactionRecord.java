// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.storage;

import io.github.kjmorse2.mconeworld.domain.Checksum;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The durable intent of one storage transaction ({@code .staging/<id>/transaction.json}, format
 * {@code mconeworld-staging-transaction} version 1). Recovery relies on it to classify an
 * interrupted transaction without guessing.
 *
 * <ul>
 *   <li>{@link Phase#STAGING}: written at begin. Nothing outside the staging directory has changed.
 *   <li>{@link Phase#PREPARED}: written, and replaced atomically, once every staged file and the
 *       new manifest are durable. It is written <em>before</em> any file leaves staging, and lists
 *       every target, so orphans can be identified precisely.
 * </ul>
 *
 * @param id transaction id, equal to the staging directory name
 * @param phase how far the transaction got durably
 * @param baseManifest checksum of manifest.json at begin, or null if none existed
 * @param baseRevision revision at begin, or null if none existed
 * @param targetRevision revision the transaction will commit
 * @param files staged data files and their final targets (in PREPARED)
 * @param targetManifest checksum of the manifest to be committed (in PREPARED)
 */
record TransactionRecord(
    UUID id,
    Phase phase,
    @Nullable Checksum baseManifest,
    @Nullable Long baseRevision,
    long targetRevision,
    List<Entry> files,
    @Nullable Checksum targetManifest) {

  static final String FORMAT = "mconeworld-staging-transaction";
  static final long VERSION = 1;
  static final String FILE_NAME = "transaction.json";
  static final long MAX_BYTES = 1024 * 1024;
  private static final JsonReader READER =
      new JsonReader(new JsonReadLimits(MAX_BYTES, 8, 1024, 100_000, 64));

  TransactionRecord {
    files = List.copyOf(files);
  }

  enum Phase {
    STAGING,
    PREPARED
  }

  /**
   * One staged file: its name inside the staging directory, its target, and its content identity.
   */
  record Entry(String stagedName, SidecarPath target, Checksum checksum, long sizeBytes) {}

  byte[] encode() {
    Map<String, JsonValue> m = new LinkedHashMap<>();
    m.put("format", new JsonString(FORMAT));
    m.put("version", new JsonNumber(VERSION));
    m.put("id", new JsonString(id.toString()));
    m.put("phase", new JsonString(phase.name()));
    m.put(
        "baseManifest",
        baseManifest == null ? JsonNull.INSTANCE : new JsonString(baseManifest.toString()));
    m.put("baseRevision", baseRevision == null ? JsonNull.INSTANCE : new JsonNumber(baseRevision));
    m.put("targetRevision", new JsonNumber(targetRevision));
    List<JsonValue> entries = new ArrayList<>();
    for (Entry e : files) {
      Map<String, JsonValue> f = new LinkedHashMap<>();
      f.put("stagedName", new JsonString(e.stagedName()));
      f.put("target", new JsonString(e.target().value()));
      f.put("checksum", new JsonString(e.checksum().toString()));
      f.put("sizeBytes", new JsonNumber(e.sizeBytes()));
      entries.add(new JsonObject(f));
    }
    m.put("files", new JsonArray(entries));
    m.put(
        "targetManifest",
        targetManifest == null ? JsonNull.INSTANCE : new JsonString(targetManifest.toString()));
    return CanonicalJsonWriter.write(new JsonObject(m));
  }

  /** Returns the record, or {@code null} if the bytes are not a complete, valid record. */
  static @Nullable TransactionRecord decode(byte[] bytes) {
    try {
      if (!(READER.read(bytes) instanceof JsonObject o)
          || !(o.get("format") instanceof JsonString format)
          || !format.value().equals(FORMAT)
          || !(o.get("version") instanceof JsonNumber version)
          || version.value() != VERSION
          || !(o.get("id") instanceof JsonString id)
          || !(o.get("phase") instanceof JsonString phase)
          || !(o.get("targetRevision") instanceof JsonNumber target)
          || !(o.get("files") instanceof JsonArray files)) {
        return null;
      }
      List<Entry> entries = new ArrayList<>();
      for (JsonValue v : files.elements()) {
        if (!(v instanceof JsonObject f)
            || !(f.get("stagedName") instanceof JsonString staged)
            || !(f.get("target") instanceof JsonString targetPath)
            || !(f.get("checksum") instanceof JsonString checksum)
            || !(f.get("sizeBytes") instanceof JsonNumber size)) {
          return null;
        }
        entries.add(
            new Entry(
                staged.value(),
                SidecarPath.of(targetPath.value()),
                Checksum.parse(checksum.value()),
                size.value()));
      }
      return new TransactionRecord(
          UUID.fromString(id.value()),
          Phase.valueOf(phase.value()),
          o.get("baseManifest") instanceof JsonString b ? Checksum.parse(b.value()) : null,
          o.get("baseRevision") instanceof JsonNumber r ? r.value() : null,
          target.value(),
          entries,
          o.get("targetManifest") instanceof JsonString t ? Checksum.parse(t.value()) : null);
    } catch (JsonReadException | RuntimeException e) {
      return null;
    }
  }
}
