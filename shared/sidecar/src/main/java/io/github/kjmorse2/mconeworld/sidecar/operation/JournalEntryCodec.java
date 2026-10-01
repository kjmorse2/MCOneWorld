// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.operation;

import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.domain.OperationId;
import io.github.kjmorse2.mconeworld.domain.operation.JournalEntry;
import io.github.kjmorse2.mconeworld.domain.operation.JournalEvent;
import io.github.kjmorse2.mconeworld.domain.operation.JournalEventType;
import io.github.kjmorse2.mconeworld.domain.operation.OperationState;
import io.github.kjmorse2.mconeworld.domain.operation.OperationType;
import io.github.kjmorse2.mconeworld.domain.operation.PreconditionSnapshot;
import io.github.kjmorse2.mconeworld.domain.validation.IssueLocation;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.CanonicalJsonWriter;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonReadException;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonReader;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonNull;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonObject;
import io.github.kjmorse2.mconeworld.sidecar.manifest.SchemaVersion;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Encodes and decodes one line of {@code journal.jsonl} (format {@value #FORMAT}; see
 * operation-journal-v1.md §4). A line is the compact canonical JSON of one entry followed by LF.
 *
 * <p>Integrity: {@code checksum} is the SHA-256 of the compact canonical encoding of the entry
 * object <em>without</em> its {@code checksum} member; {@code previous} is the {@code checksum} of
 * the entry before (null for sequence 0). Both cover the parsed tree, so members from a newer minor
 * version are covered too.
 */
final class JournalEntryCodec {
  static final String FORMAT = "mconeworld-operation-journal";
  static final SchemaVersion CURRENT = new SchemaVersion(1, 0);
  static final byte NEWLINE = '\n';

  private static final int MAX_STEP = 1 << 16;
  private static final int MAX_ATTEMPT = 1 << 16;

  private final JsonReader reader =
      new JsonReader(OperationJson.limits(OperationPlanLimits.MAX_ENTRY_BYTES));

  /** One encoded line, including its trailing LF, and the entry checksum it carries. */
  static final class Encoded {
    private final byte[] line;
    private final Checksum checksum;

    Encoded(byte[] line, Checksum checksum) {
      this.line = line.clone();
      this.checksum = checksum;
    }

    byte[] line() {
      return line.clone();
    }

    Checksum checksum() {
      return checksum;
    }
  }

  /** One decoded line. */
  record Decoded(
      JournalEntry entry,
      OperationId operation,
      @Nullable Checksum previous,
      Checksum checksum,
      SchemaVersion schemaVersion,
      List<String> unknownMembers) {
    Decoded {
      unknownMembers = List.copyOf(unknownMembers);
    }
  }

  /**
   * @throws IllegalArgumentException if {@code recordedAt} is outside years 0000–9999
   */
  Encoded encode(JournalEntry entry, OperationId operation, @Nullable Checksum previous) {
    Map<String, JsonValue> m = new LinkedHashMap<>();
    m.put("format", OperationJson.str(FORMAT));
    m.put("schemaVersion", OperationJson.str(CURRENT));
    m.put("operation", OperationJson.str(operation));
    m.put("sequence", OperationJson.num(entry.sequence()));
    m.put("previous", OperationJson.nullable(previous));
    m.put("recordedAt", OperationJson.instant(entry.recordedAt()));
    m.put("event", event(entry.event()));
    m.put("state", OperationJson.str(entry.stateAfter().name()));
    Checksum checksum = checksumOf(new JsonObject(m));
    m.put("checksum", OperationJson.str(checksum));
    byte[] compact = CanonicalJsonWriter.writeCompact(new JsonObject(m));
    byte[] line = Arrays.copyOf(compact, compact.length + 1);
    line[compact.length] = NEWLINE;
    return new Encoded(line, checksum);
  }

  /**
   * Decodes one line without its LF and verifies its own checksum. Chain, sequence, and state
   * checks need the preceding entries; the caller does them.
   *
   * @throws OperationFormatException at the first problem
   */
  Decoded decode(byte[] line) throws OperationFormatException {
    JsonValue tree;
    try {
      tree = reader.read(line);
    } catch (JsonReadException e) {
      throw new OperationFormatException(
          e.code(), IssueLocation.root(), String.valueOf(e.getMessage()));
    }
    JsonFields.Context context = new JsonFields.Context();
    JsonFields r = JsonFields.of(tree, IssueLocation.root(), context);
    SchemaVersion version = OperationJson.header(r, FORMAT, CURRENT, context);
    Checksum checksum = r.parse("checksum", Checksum::parse);
    Map<String, JsonValue> withoutChecksum = new LinkedHashMap<>(((JsonObject) tree).members());
    withoutChecksum.remove("checksum");
    if (!checksumOf(new JsonObject(withoutChecksum)).equals(checksum)) {
      throw new OperationFormatException(
          JournalIssueCodes.ENTRY_CHECKSUM_MISMATCH,
          r.at("checksum"),
          "The entry does not match its checksum");
    }
    OperationId operation = r.parse("operation", OperationId::parse);
    long sequence = r.integer("sequence");
    if (sequence < 0) {
      throw new OperationFormatException(
          JournalIssueCodes.INVALID_VALUE, r.at("sequence"), "Sequence must not be negative");
    }
    Checksum previous = r.nullableParse("previous", Checksum::parse);
    Instant recordedAt = r.instant("recordedAt");
    JournalEvent event = event(r.object("event"), operation);
    OperationState state = r.enumValue("state", OperationState.class);
    r.finish();
    return new Decoded(
        new JournalEntry(sequence, recordedAt, event, state),
        operation,
        previous,
        checksum,
        version,
        context.unknownMembers);
  }

  private static Checksum checksumOf(JsonObject object) {
    return OperationPlanCodec.sha256(CanonicalJsonWriter.writeCompact(object));
  }

  private static JsonObject event(JournalEvent event) {
    Map<String, JsonValue> m = new LinkedHashMap<>();
    m.put("type", OperationJson.str(event.type().name()));
    switch (event) {
      case JournalEvent.PlanRecorded e -> {
        m.put("operationType", OperationJson.str(e.operationType()));
        m.put("planDigest", OperationJson.str(e.planDigest()));
      }
      case JournalEvent.BackupConfirmed e -> {
        var backup = e.backup();
        m.put("backup", backup == null ? JsonNull.INSTANCE : OperationJson.backup(backup));
      }
      case JournalEvent.Cancelled e -> m.put("reason", OperationJson.reason(e.reason()));
      case JournalEvent.ApplyStarted e -> {
        m.put("approvedPlanDigest", OperationJson.str(e.approvedPlanDigest()));
        m.put("observed", OperationJson.snapshot(e.observed()));
      }
      case JournalEvent.StepStarted e -> {
        m.put("step", OperationJson.num(e.step()));
        m.put("attempt", OperationJson.num(e.attempt()));
      }
      case JournalEvent.StepCompleted e -> m.put("step", OperationJson.num(e.step()));
      case JournalEvent.ValidationStarted e -> {}
      case JournalEvent.CheckPassed e -> m.put("check", OperationJson.num(e.check()));
      case JournalEvent.OperationCompleted e -> {}
      case JournalEvent.OperationFailed e -> {
        m.put("reason", OperationJson.reason(e.reason()));
        m.put("step", OperationJson.nullableNum(e.step()));
      }
      case JournalEvent.RollbackStarted e -> m.put("attempt", OperationJson.num(e.attempt()));
      case JournalEvent.RollbackCompleted e -> {}
      case JournalEvent.RollbackFailed e -> m.put("reason", OperationJson.reason(e.reason()));
    }
    return new JsonObject(m);
  }

  private static JournalEvent event(JsonFields e, OperationId operation)
      throws OperationFormatException {
    JournalEventType type = e.enumValue("type", JournalEventType.class);
    JournalEvent event =
        switch (type) {
          case PLAN_RECORDED ->
              new JournalEvent.PlanRecorded(
                  operation,
                  e.parse("operationType", OperationType::of),
                  e.parse("planDigest", Checksum::parse));
          case BACKUP_CONFIRMED ->
              new JournalEvent.BackupConfirmed(
                  e.isNull("backup") ? null : OperationJson.backup(e.object("backup")));
          case CANCELLED -> new JournalEvent.Cancelled(OperationJson.reason(e.object("reason")));
          case APPLY_STARTED -> {
            Checksum approved = e.parse("approvedPlanDigest", Checksum::parse);
            PreconditionSnapshot observed = OperationJson.snapshot(e.object("observed"));
            yield new JournalEvent.ApplyStarted(approved, observed);
          }
          case STEP_STARTED ->
              new JournalEvent.StepStarted(
                  e.intIn("step", 0, MAX_STEP), e.intIn("attempt", 1, MAX_ATTEMPT));
          case STEP_COMPLETED -> new JournalEvent.StepCompleted(e.intIn("step", 0, MAX_STEP));
          case VALIDATION_STARTED -> new JournalEvent.ValidationStarted();
          case CHECK_PASSED -> new JournalEvent.CheckPassed(e.intIn("check", 0, MAX_STEP));
          case OPERATION_COMPLETED -> new JournalEvent.OperationCompleted();
          case OPERATION_FAILED ->
              new JournalEvent.OperationFailed(
                  OperationJson.reason(e.object("reason")), e.nullableIntIn("step", 0, MAX_STEP));
          case ROLLBACK_STARTED ->
              new JournalEvent.RollbackStarted(e.intIn("attempt", 1, MAX_ATTEMPT));
          case ROLLBACK_COMPLETED -> new JournalEvent.RollbackCompleted();
          case ROLLBACK_FAILED ->
              new JournalEvent.RollbackFailed(OperationJson.reason(e.object("reason")));
        };
    e.finish();
    return event;
  }
}
