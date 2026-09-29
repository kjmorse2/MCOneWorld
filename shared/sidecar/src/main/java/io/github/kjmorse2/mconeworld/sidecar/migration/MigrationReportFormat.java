// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.migration;

import io.github.kjmorse2.mconeworld.domain.validation.ValidationIssue;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.CanonicalJsonWriter;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonArray;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonNumber;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonObject;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonString;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Canonical JSON form of a persisted migration report ({@code mconeworld-migration-report}, version
 * 1), written to {@code reports/migrations/<runId>.json}. For a fixed run id and backup, the bytes
 * depend only on the report.
 */
final class MigrationReportFormat {
  static final String FORMAT = "mconeworld-migration-report";

  private MigrationReportFormat() {}

  static byte[] encode(MigrationReport report, UUID runId, VerifiedBackup backup) {
    Map<String, JsonValue> root = new LinkedHashMap<>();
    root.put("format", str(FORMAT));
    root.put("version", new JsonNumber(1));
    root.put("runId", str(runId.toString()));
    Map<String, JsonValue> b = new LinkedHashMap<>();
    b.put("id", str(backup.id().toString()));
    b.put("checksum", str(backup.checksum().toString()));
    b.put("sizeBytes", new JsonNumber(backup.sizeBytes()));
    root.put("backup", new JsonObject(b));
    root.put("source", str(report.source().toString()));
    root.put("target", str(report.target().toString()));
    root.put("sourceChecksum", str(report.sourceChecksum().toString()));
    root.put("migratedChecksum", str(report.migratedChecksum().toString()));
    List<JsonValue> steps = new ArrayList<>();
    for (StepReport step : report.steps()) {
      Map<String, JsonValue> s = new LinkedHashMap<>();
      s.put("id", str(step.step().id().value()));
      s.put("from", str(step.step().from().toString()));
      s.put("to", str(step.step().to().toString()));
      s.put("transformed", fields(step.transformed()));
      s.put(
          "preservedExtensions",
          new JsonArray(
              step.preservedExtensions().stream().map(MigrationReportFormat::str).toList()));
      List<JsonValue> warnings = new ArrayList<>();
      for (ValidationIssue w : step.warnings()) {
        Map<String, JsonValue> m = new LinkedHashMap<>();
        m.put("code", str(w.code().value()));
        m.put("location", str(w.location().path()));
        m.put("message", str(w.message()));
        warnings.add(new JsonObject(m));
      }
      s.put("warnings", new JsonArray(warnings));
      steps.add(new JsonObject(s));
    }
    root.put("steps", new JsonArray(steps));
    return CanonicalJsonWriter.write(new JsonObject(root));
  }

  private static JsonArray fields(List<StepReport.TransformedField> fields) {
    List<JsonValue> out = new ArrayList<>();
    for (StepReport.TransformedField f : fields) {
      Map<String, JsonValue> m = new LinkedHashMap<>();
      m.put("path", str(f.path()));
      m.put("description", str(f.description()));
      out.add(new JsonObject(m));
    }
    return new JsonArray(out);
  }

  private static JsonValue str(String s) {
    return new JsonString(s);
  }
}
