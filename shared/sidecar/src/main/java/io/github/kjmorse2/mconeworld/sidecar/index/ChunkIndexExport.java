// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkPosition;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProtection;
import io.github.kjmorse2.mconeworld.domain.provenance.ChunkProvenance;
import io.github.kjmorse2.mconeworld.domain.provenance.Confidence;
import io.github.kjmorse2.mconeworld.domain.provenance.EvidenceSource;
import io.github.kjmorse2.mconeworld.domain.provenance.FirstObservation;
import io.github.kjmorse2.mconeworld.domain.provenance.ProvenanceEvidence;
import io.github.kjmorse2.mconeworld.domain.provenance.SeasonActivity;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.CanonicalJsonWriter;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonReadException;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonReadLimits;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonReader;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonArray;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonBoolean;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonNull;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonNumber;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonObject;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonString;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The readable diagnostic form of an index, {@code mconeworld-chunk-index-export} 1.0 (format §9).
 * It is for people, diffs, and migration checks; it is <b>not</b> the canonical storage.
 *
 * <p>One compact canonical JSON object per line (ADR 0003): first a header, then one line per
 * record in dimension order and then {@link ChunkOrder}:
 *
 * <pre>
 * {"format":"mconeworld-chunk-index-export","generation":7,"indexId":"…","logBytes":1204,"version":"1.0"}
 * {"activity":[{"confidence":"HIGH","lastObservedAt":"…","season":"…","source":"RUNTIME_OBSERVATION"}],"dimension":"minecraft:overworld","first":{"confidence":"HIGH","legacy":false,"observedAt":"…","season":"…","source":"RUNTIME_OBSERVATION"},"protection":"NONE","x":-1,"z":7}
 * </pre>
 *
 * <p>Deterministic: the same records always export to the same bytes, so the SHA-256 of the record
 * lines is a semantic checksum of an index. Timestamps are ISO-8601 UTC in their shortest exact
 * form; legacy first observations have {@code "season":null}.
 */
public final class ChunkIndexExport {
  /** The {@code format} member of the header line. */
  public static final String FORMAT = "mconeworld-chunk-index-export";

  /** The export format version. */
  public static final String VERSION = "1.0";

  /** Longest accepted line, in bytes. */
  public static final int MAX_LINE_BYTES = 1 << 20;

  private ChunkIndexExport() {}

  /** Writes the header and every record of {@code snapshot} to {@code out}. Returns the count. */
  public static long write(ChunkIndexSnapshot snapshot, OutputStream out)
      throws ChunkIndexException, IOException {
    ChunkIndexState state = snapshot.state();
    Map<String, JsonValue> header = new LinkedHashMap<>();
    header.put("format", new JsonString(FORMAT));
    header.put("version", new JsonString(VERSION));
    header.put("indexId", new JsonString(state.indexId().toString()));
    header.put("generation", new JsonNumber(state.generation()));
    header.put("logBytes", new JsonNumber(state.logBytes()));
    line(out, new JsonObject(header));
    long count = 0;
    for (DimensionKey dimension : snapshot.dimensions()) {
      try (ChunkCursor cursor = snapshot.scan(ChunkQuery.all(dimension))) {
        for (ChunkProvenance r = cursor.next(); r != null; r = cursor.next()) {
          line(out, toJson(r));
          count++;
        }
      }
    }
    out.flush();
    return count;
  }

  /** The canonical line of one record, without its LF. */
  public static byte[] recordLine(ChunkProvenance record) {
    return CanonicalJsonWriter.writeCompact(toJson(record));
  }

  private static void line(OutputStream out, JsonValue value) throws IOException {
    out.write(CanonicalJsonWriter.writeCompact(value));
    out.write('\n');
  }

  private static JsonObject toJson(ChunkProvenance r) {
    Map<String, JsonValue> first = new LinkedHashMap<>();
    SeasonId firstSeason = r.firstObservation().knownSeason();
    first.put("legacy", new JsonBoolean(r.isLegacy()));
    first.put(
        "season", firstSeason == null ? JsonNull.INSTANCE : new JsonString(firstSeason.toString()));
    first.put("observedAt", new JsonString(r.firstObservation().observedAt().toString()));
    evidence(first, r.firstObservation().evidence());
    List<JsonValue> activity = new ArrayList<>();
    for (SeasonActivity a : r.activity()) {
      Map<String, JsonValue> entry = new LinkedHashMap<>();
      entry.put("season", new JsonString(a.season().toString()));
      entry.put("lastObservedAt", new JsonString(a.lastObservedAt().toString()));
      evidence(entry, a.evidence());
      activity.add(new JsonObject(entry));
    }
    Map<String, JsonValue> members = new LinkedHashMap<>();
    members.put("dimension", new JsonString(r.chunk().dimension().namespacedId()));
    members.put("x", new JsonNumber(r.chunk().x()));
    members.put("z", new JsonNumber(r.chunk().z()));
    members.put("first", new JsonObject(first));
    members.put("activity", new JsonArray(activity));
    members.put("protection", new JsonString(r.protection().name()));
    return new JsonObject(members);
  }

  private static void evidence(Map<String, JsonValue> into, ProvenanceEvidence evidence) {
    into.put("source", new JsonString(evidence.source().name()));
    into.put("confidence", new JsonString(evidence.confidence().name()));
  }

  // ---- reading -------------------------------------------------------------------------------

  /** Opens an export for reading; the header line is read and checked immediately. */
  public static Reader read(InputStream in) throws IOException {
    return new Reader(in);
  }

  /**
   * Streams the records of an export. Strict: unknown or missing members, wrong types, and invalid
   * values are rejected with the line number.
   */
  public static final class Reader {
    private static final JsonReader JSON =
        new JsonReader(new JsonReadLimits(MAX_LINE_BYTES, 8, 4096, 4096, 16));

    private final InputStream in;
    private final UUID indexId;
    private final long generation;
    private int lineNumber;

    private Reader(InputStream in) throws IOException {
      this.in = Objects.requireNonNull(in, "in");
      JsonObject header = next("header");
      if (header == null) {
        throw invalid("the export is empty");
      }
      expect(header, Set.of("format", "version", "indexId", "generation", "logBytes"));
      if (!FORMAT.equals(string(header, "format")) || !VERSION.equals(string(header, "version"))) {
        throw invalid("not a " + FORMAT + " " + VERSION + " export");
      }
      try {
        this.indexId = UUID.fromString(string(header, "indexId"));
      } catch (IllegalArgumentException e) {
        throw invalid("invalid indexId");
      }
      this.generation = number(header, "generation");
      number(header, "logBytes");
    }

    /** The exported index's id. */
    public UUID indexId() {
      return indexId;
    }

    /** The exported catalog generation. */
    public long generation() {
      return generation;
    }

    /** The next record, or {@code null} at the end. */
    public @Nullable ChunkProvenance next() throws IOException {
      JsonObject o = next("record");
      if (o == null) {
        return null;
      }
      expect(o, Set.of("dimension", "x", "z", "first", "activity", "protection"));
      try {
        DimensionKey dimension = DimensionKey.parse(string(o, "dimension"));
        ChunkPosition chunk =
            new ChunkPosition(dimension, toInt(number(o, "x")), toInt(number(o, "z")));
        JsonObject f = object(o, "first");
        expect(f, Set.of("legacy", "season", "observedAt", "source", "confidence"));
        Instant observedAt = Instant.parse(string(f, "observedAt"));
        ProvenanceEvidence firstEvidence = evidence(f);
        boolean legacy = bool(f, "legacy");
        JsonValue season = f.get("season");
        FirstObservation first;
        if (legacy) {
          if (!(season instanceof JsonNull)) {
            throw invalid("a legacy first observation has a season");
          }
          first = new FirstObservation.Legacy(observedAt, firstEvidence);
        } else {
          first =
              new FirstObservation.InSeason(
                  SeasonId.parse(string(f, "season")), observedAt, firstEvidence);
        }
        List<SeasonActivity> activity = new ArrayList<>();
        JsonValue list = o.get("activity");
        if (!(list instanceof JsonArray array)) {
          throw invalid("activity must be an array");
        }
        for (JsonValue element : array.elements()) {
          if (!(element instanceof JsonObject a)) {
            throw invalid("activity entries must be objects");
          }
          expect(a, Set.of("season", "lastObservedAt", "source", "confidence"));
          activity.add(
              new SeasonActivity(
                  SeasonId.parse(string(a, "season")),
                  Instant.parse(string(a, "lastObservedAt")),
                  evidence(a)));
        }
        return new ChunkProvenance(
            chunk, first, activity, ChunkProtection.valueOf(string(o, "protection")));
      } catch (RuntimeException e) {
        if (e instanceof DateTimeParseException || e instanceof IllegalArgumentException) {
          throw invalid("invalid value: " + e.getMessage());
        }
        throw e;
      }
    }

    private @Nullable JsonObject next(String what) throws IOException {
      ByteArrayOutputStream line = new ByteArrayOutputStream();
      int b;
      while ((b = in.read()) != -1 && b != '\n') {
        if (line.size() >= MAX_LINE_BYTES) {
          throw invalid(what + " line longer than " + MAX_LINE_BYTES + " bytes");
        }
        line.write(b);
      }
      if (b == -1 && line.size() == 0) {
        return null;
      }
      lineNumber++;
      if (b == -1) {
        throw invalid("the last line has no LF");
      }
      try {
        JsonValue value = JSON.read(line.toByteArray());
        if (!(value instanceof JsonObject o)) {
          throw invalid(what + " is not an object");
        }
        return o;
      } catch (JsonReadException e) {
        throw invalid(what + " is not valid JSON: " + e.getMessage());
      }
    }

    private void expect(JsonObject o, Set<String> members) throws IOException {
      if (!o.members().keySet().equals(members)) {
        throw invalid("expected members " + members + " but found " + o.members().keySet());
      }
    }

    private String string(JsonObject o, String name) throws IOException {
      if (!(o.get(name) instanceof JsonString s)) {
        throw invalid(name + " must be a string");
      }
      return s.value();
    }

    private long number(JsonObject o, String name) throws IOException {
      if (!(o.get(name) instanceof JsonNumber n)) {
        throw invalid(name + " must be an integer");
      }
      return n.value();
    }

    private boolean bool(JsonObject o, String name) throws IOException {
      if (!(o.get(name) instanceof JsonBoolean b)) {
        throw invalid(name + " must be a boolean");
      }
      return b.value();
    }

    private JsonObject object(JsonObject o, String name) throws IOException {
      if (!(o.get(name) instanceof JsonObject inner)) {
        throw invalid(name + " must be an object");
      }
      return inner;
    }

    private ProvenanceEvidence evidence(JsonObject o) throws IOException {
      return new ProvenanceEvidence(
          EvidenceSource.valueOf(string(o, "source")), Confidence.valueOf(string(o, "confidence")));
    }

    private int toInt(long value) throws IOException {
      if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
        throw invalid("coordinate out of range");
      }
      return (int) value;
    }

    private IOException invalid(String message) {
      return new IOException("export line " + lineNumber + ": " + message);
    }
  }
}
