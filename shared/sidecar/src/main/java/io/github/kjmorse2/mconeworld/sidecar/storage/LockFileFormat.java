// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.storage;

import io.github.kjmorse2.mconeworld.sidecar.internal.json.CanonicalJsonWriter;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonReadException;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonReadLimits;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonReader;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonNumber;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonObject;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonString;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Codec for {@code writer.lock} ({@code mconeworld-writer-lock}, version 1). */
final class LockFileFormat {
  static final String FORMAT = "mconeworld-writer-lock";
  static final long VERSION = 1;
  private static final JsonReader READER = new JsonReader(new JsonReadLimits(4096, 4, 256, 16, 16));

  private LockFileFormat() {}

  static byte[] encode(LockInfo info) {
    Map<String, JsonValue> m = new LinkedHashMap<>();
    m.put("format", new JsonString(FORMAT));
    m.put("version", new JsonNumber(VERSION));
    m.put("lockId", new JsonString(info.lockId().toString()));
    m.put("tool", new JsonString(info.tool()));
    m.put("toolVersion", new JsonString(info.toolVersion()));
    m.put("host", new JsonString(info.host()));
    m.put("pid", new JsonNumber(info.pid()));
    m.put("acquiredAt", new JsonString(DateTimeFormatter.ISO_INSTANT.format(info.acquiredAt())));
    return CanonicalJsonWriter.write(new JsonObject(m));
  }

  /** Returns the metadata, or {@code null} if the content is incomplete or unrecognized. */
  static @Nullable LockInfo decode(byte[] bytes) {
    try {
      if (!(READER.read(bytes) instanceof JsonObject o)
          || !(o.get("format") instanceof JsonString format)
          || !format.value().equals(FORMAT)
          || !(o.get("version") instanceof JsonNumber version)
          || version.value() != VERSION
          || !(o.get("lockId") instanceof JsonString lockId)
          || !(o.get("tool") instanceof JsonString tool)
          || !(o.get("toolVersion") instanceof JsonString toolVersion)
          || !(o.get("host") instanceof JsonString host)
          || !(o.get("pid") instanceof JsonNumber pid)
          || !(o.get("acquiredAt") instanceof JsonString acquiredAt)) {
        return null;
      }
      return new LockInfo(
          UUID.fromString(lockId.value()),
          tool.value(),
          toolVersion.value(),
          host.value(),
          pid.value(),
          Instant.parse(acquiredAt.value()));
    } catch (JsonReadException | RuntimeException e) {
      return null;
    }
  }
}
