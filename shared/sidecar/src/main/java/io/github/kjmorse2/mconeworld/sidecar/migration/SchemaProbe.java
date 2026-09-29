// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.migration;

import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonReadException;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonReader;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonObject;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonString;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestCodec;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestLimits;
import io.github.kjmorse2.mconeworld.sidecar.manifest.SchemaVersion;

/** Reads just enough of any manifest version to learn its schema version. */
final class SchemaProbe {
  private SchemaProbe() {}

  static SchemaVersion versionOf(byte[] bytes, JsonReader reader) throws MigrationException {
    try {
      if (reader.read(bytes) instanceof JsonObject root
          && root.get("format") instanceof JsonString format
          && format.value().equals(ManifestCodec.FORMAT)
          && root.get("schemaVersion") instanceof JsonString version) {
        return SchemaVersion.parse(version.value());
      }
    } catch (JsonReadException | IllegalArgumentException e) {
      throw new MigrationException(
          MigrationIssueCodes.SOURCE_UNREADABLE, "Not a readable manifest: " + e.getMessage());
    }
    throw new MigrationException(
        MigrationIssueCodes.SOURCE_UNREADABLE,
        "Not an MCOneWorld manifest (missing format or schemaVersion)");
  }

  static JsonReader reader(ManifestLimits limits) {
    return new JsonReader(
        new io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonReadLimits(
            limits.maxDocumentBytes(),
            limits.maxNestingDepth(),
            limits.maxStringChars(),
            limits.maxArrayElements(),
            limits.maxObjectMembers()));
  }
}
