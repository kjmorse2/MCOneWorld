// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.operation;

import io.github.kjmorse2.mconeworld.domain.ArchiveId;
import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.OperationId;
import io.github.kjmorse2.mconeworld.domain.PlayerId;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.coordinate.ChunkRectangle;
import io.github.kjmorse2.mconeworld.domain.operation.ActionKey;
import io.github.kjmorse2.mconeworld.domain.operation.BackupRequirement;
import io.github.kjmorse2.mconeworld.domain.operation.ImpactClass;
import io.github.kjmorse2.mconeworld.domain.operation.OperationPlan;
import io.github.kjmorse2.mconeworld.domain.operation.OperationTarget;
import io.github.kjmorse2.mconeworld.domain.operation.OperationText;
import io.github.kjmorse2.mconeworld.domain.operation.OperationType;
import io.github.kjmorse2.mconeworld.domain.operation.PreconditionSnapshot;
import io.github.kjmorse2.mconeworld.domain.operation.RollbackDeclaration;
import io.github.kjmorse2.mconeworld.domain.operation.RollbackStrategy;
import io.github.kjmorse2.mconeworld.domain.operation.StepDescriptor;
import io.github.kjmorse2.mconeworld.domain.operation.ToolIdentity;
import io.github.kjmorse2.mconeworld.domain.operation.ValidationDescriptor;
import io.github.kjmorse2.mconeworld.domain.validation.IssueLocation;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.CanonicalJsonWriter;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonReadException;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonReader;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonArray;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonBoolean;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonObject;
import io.github.kjmorse2.mconeworld.sidecar.manifest.SchemaVersion;
import io.github.kjmorse2.mconeworld.sidecar.storage.Checksums;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Encodes and decodes {@code operations/<id>/plan.json} (format {@value #FORMAT}, schema {@link
 * #CURRENT}), and computes the plan digest. Thread-safe; no I/O.
 *
 * <p><b>Plan digest</b> (operation-journal-v1.md §3): the SHA-256 of the canonical encoding ({@link
 * CanonicalJsonWriter#write}) of the <em>whole</em> plan document. Every member counts, including
 * {@code format}, {@code schemaVersion}, and members from a newer minor version; nothing is
 * excluded, because a plan holds no volatile state (approval, progress, and execution times live in
 * the journal). Whitespace and member order cannot change it, since the digest covers the canonical
 * form of the parsed tree. For a plan this codec wrote, the digest is simply the SHA-256 of the
 * file bytes.
 */
public final class OperationPlanCodec {
  /** Value of the {@code format} member. */
  public static final String FORMAT = "mconeworld-operation-plan";

  /** The schema version this codec implements. */
  public static final SchemaVersion CURRENT = new SchemaVersion(1, 0);

  private final JsonReader reader =
      new JsonReader(OperationJson.limits(OperationPlanLimits.MAX_PLAN_BYTES));

  /**
   * Returns the canonical bytes of {@code plan}.
   *
   * @throws IllegalArgumentException if {@code createdAt} is outside years 0000–9999
   */
  public byte[] encode(OperationPlan plan) {
    return CanonicalJsonWriter.write(tree(plan));
  }

  /** Returns the digest of {@code plan}: the SHA-256 of {@link #encode}. */
  public Checksum digest(OperationPlan plan) {
    return sha256(encode(plan));
  }

  /**
   * Decodes and validates plan bytes.
   *
   * @throws OperationFormatException at the first problem
   */
  public DecodedPlan decode(byte[] bytes) throws OperationFormatException {
    JsonValue tree;
    try {
      tree = reader.read(bytes);
    } catch (JsonReadException e) {
      throw new OperationFormatException(
          e.code(), IssueLocation.root(), String.valueOf(e.getMessage()));
    }
    JsonFields.Context context = new JsonFields.Context();
    JsonFields root = JsonFields.of(tree, IssueLocation.root(), context);
    SchemaVersion version = OperationJson.header(root, FORMAT, CURRENT, context);
    OperationPlan plan = plan(root);
    root.finish();
    return new DecodedPlan(
        plan, sha256(CanonicalJsonWriter.write(tree)), version, context.unknownMembers);
  }

  static Checksum sha256(byte[] bytes) {
    try {
      return Checksums.sha256(new ByteArrayInputStream(bytes), bytes.length).checksum();
    } catch (IOException e) {
      throw new IllegalStateException("In-memory stream cannot fail", e);
    }
  }

  // --- encoding -------------------------------------------------------------------------------

  private static JsonObject tree(OperationPlan p) {
    Map<String, JsonValue> m = new LinkedHashMap<>();
    m.put("format", OperationJson.str(FORMAT));
    m.put("schemaVersion", OperationJson.str(CURRENT));
    m.put("id", OperationJson.str(p.id()));
    m.put("type", OperationJson.str(p.type()));
    m.put("createdAt", OperationJson.instant(p.createdAt()));
    Map<String, JsonValue> by = new LinkedHashMap<>();
    by.put("name", OperationJson.str(p.createdBy().name()));
    by.put("version", OperationJson.str(p.createdBy().version()));
    m.put("createdBy", new JsonObject(by));
    m.put("archive", OperationJson.str(p.archive()));
    m.put("impact", OperationJson.str(p.impact().name()));
    m.put("targets", new JsonArray(p.targets().stream().map(OperationPlanCodec::target).toList()));
    m.put("preconditions", OperationJson.snapshot(p.preconditions()));
    m.put("backup", backup(p.backup()));
    m.put("steps", new JsonArray(p.steps().stream().map(OperationPlanCodec::step).toList()));
    m.put(
        "validations",
        new JsonArray(p.validations().stream().map(OperationPlanCodec::validation).toList()));
    Map<String, JsonValue> rollback = new LinkedHashMap<>();
    rollback.put("strategy", OperationJson.str(p.rollback().strategy().name()));
    rollback.put("description", OperationJson.str(p.rollback().description()));
    m.put("rollback", new JsonObject(rollback));
    return new JsonObject(m);
  }

  private static JsonValue target(OperationTarget target) {
    Map<String, JsonValue> m = new LinkedHashMap<>();
    m.put("kind", OperationJson.str(target.kind()));
    switch (target) {
      case OperationTarget.ChunkArea a -> {
        m.put("dimension", OperationJson.str(a.area().dimension()));
        m.put("minX", OperationJson.num(a.area().minX()));
        m.put("minZ", OperationJson.num(a.area().minZ()));
        m.put("maxX", OperationJson.num(a.area().maxX()));
        m.put("maxZ", OperationJson.num(a.area().maxZ()));
      }
      case OperationTarget.Player p -> m.put("player", OperationJson.str(p.player()));
      case OperationTarget.Season s -> m.put("season", OperationJson.str(s.season()));
      case OperationTarget.SidecarManifest s -> {}
      case OperationTarget.Extension e -> {
        m.put("type", OperationJson.str(e.type()));
        m.put("key", OperationJson.str(e.key()));
      }
    }
    return new JsonObject(m);
  }

  private static JsonValue backup(BackupRequirement backup) {
    Map<String, JsonValue> m = new LinkedHashMap<>();
    switch (backup) {
      case BackupRequirement.NewBackup n -> m.put("kind", OperationJson.str("NEW"));
      case BackupRequirement.ExistingBackup e -> {
        m.put("kind", OperationJson.str("EXISTING"));
        m.put("backup", OperationJson.backup(e.backup()));
      }
      case BackupRequirement.NotRequired n -> {
        m.put("kind", OperationJson.str("NOT_REQUIRED"));
        m.put("justification", OperationJson.str(n.justification()));
      }
    }
    return new JsonObject(m);
  }

  private static JsonValue step(StepDescriptor s) {
    Map<String, JsonValue> m = new LinkedHashMap<>();
    m.put("action", OperationJson.str(s.action()));
    m.put("description", OperationJson.str(s.description()));
    m.put("retrySafe", new JsonBoolean(s.retrySafe()));
    m.put(
        "targets",
        new JsonArray(s.targets().stream().map(i -> (JsonValue) OperationJson.num(i)).toList()));
    return new JsonObject(m);
  }

  private static JsonValue validation(ValidationDescriptor v) {
    Map<String, JsonValue> m = new LinkedHashMap<>();
    m.put("check", OperationJson.str(v.check()));
    m.put("description", OperationJson.str(v.description()));
    return new JsonObject(m);
  }

  // --- decoding -------------------------------------------------------------------------------

  private static OperationPlan plan(JsonFields r) throws OperationFormatException {
    OperationId id = r.parse("id", OperationId::parse);
    OperationType type = r.parse("type", OperationType::of);
    Instant createdAt = r.instant("createdAt");
    JsonFields by = r.object("createdBy");
    String name = by.string("name");
    String version = by.string("version");
    by.finish();
    ToolIdentity createdBy =
        JsonFields.construct(r.at("createdBy"), () -> ToolIdentity.of(name, version));
    ArchiveId archive = r.parse("archive", ArchiveId::parse);
    ImpactClass impact = r.enumValue("impact", ImpactClass.class);
    List<OperationTarget> targets = new ArrayList<>();
    List<JsonValue> rawTargets = r.array("targets");
    for (int i = 0; i < rawTargets.size(); i++) {
      targets.add(target(JsonFields.of(rawTargets.get(i), r.at("targets").index(i), contextOf(r))));
    }
    PreconditionSnapshot preconditions = OperationJson.snapshot(r.object("preconditions"));
    BackupRequirement backup = backup(r.object("backup"));
    List<StepDescriptor> steps = new ArrayList<>();
    List<JsonValue> rawSteps = r.array("steps");
    for (int i = 0; i < rawSteps.size(); i++) {
      steps.add(step(JsonFields.of(rawSteps.get(i), r.at("steps").index(i), contextOf(r))));
    }
    List<ValidationDescriptor> validations = new ArrayList<>();
    List<JsonValue> rawChecks = r.array("validations");
    for (int i = 0; i < rawChecks.size(); i++) {
      JsonFields v = JsonFields.of(rawChecks.get(i), r.at("validations").index(i), contextOf(r));
      validations.add(
          new ValidationDescriptor(
              v.parse("check", ActionKey::of), v.parse("description", OperationText::of)));
      v.finish();
    }
    JsonFields rb = r.object("rollback");
    RollbackDeclaration rollback =
        new RollbackDeclaration(
            rb.enumValue("strategy", RollbackStrategy.class),
            rb.parse("description", OperationText::of));
    rb.finish();
    return JsonFields.construct(
        IssueLocation.root(),
        () ->
            new OperationPlan(
                id,
                type,
                createdAt,
                createdBy,
                archive,
                impact,
                targets,
                preconditions,
                backup,
                steps,
                validations,
                rollback));
  }

  private static JsonFields.Context contextOf(JsonFields r) {
    return r.context();
  }

  private static OperationTarget target(JsonFields t) throws OperationFormatException {
    String kind = t.string("kind");
    OperationTarget target =
        switch (kind) {
          case "chunkArea" -> {
            DimensionKey dimension = t.parse("dimension", DimensionKey::parse);
            int minX = t.intIn("minX", Integer.MIN_VALUE, Integer.MAX_VALUE);
            int minZ = t.intIn("minZ", Integer.MIN_VALUE, Integer.MAX_VALUE);
            int maxX = t.intIn("maxX", Integer.MIN_VALUE, Integer.MAX_VALUE);
            int maxZ = t.intIn("maxZ", Integer.MIN_VALUE, Integer.MAX_VALUE);
            yield JsonFields.construct(
                t.at("kind"),
                () ->
                    new OperationTarget.ChunkArea(
                        new ChunkRectangle(dimension, minX, minZ, maxX, maxZ)));
          }
          case "player" -> new OperationTarget.Player(t.parse("player", PlayerId::parse));
          case "season" -> new OperationTarget.Season(t.parse("season", SeasonId::parse));
          case "sidecarManifest" -> new OperationTarget.SidecarManifest();
          case "extension" -> {
            ActionKey type = t.parse("type", ActionKey::of);
            String key = t.string("key");
            yield JsonFields.construct(t.at("key"), () -> new OperationTarget.Extension(type, key));
          }
          default ->
              throw new OperationFormatException(
                  JournalIssueCodes.INVALID_VALUE, t.at("kind"), "Unknown target kind");
        };
    t.finish();
    return target;
  }

  private static BackupRequirement backup(JsonFields b) throws OperationFormatException {
    BackupRequirement result =
        switch (b.string("kind")) {
          case "NEW" -> new BackupRequirement.NewBackup();
          case "EXISTING" ->
              new BackupRequirement.ExistingBackup(OperationJson.backup(b.object("backup")));
          case "NOT_REQUIRED" ->
              new BackupRequirement.NotRequired(b.parse("justification", OperationText::of));
          default ->
              throw new OperationFormatException(
                  JournalIssueCodes.INVALID_VALUE, b.at("kind"), "Unknown backup kind");
        };
    b.finish();
    return result;
  }

  private static StepDescriptor step(JsonFields s) throws OperationFormatException {
    ActionKey action = s.parse("action", ActionKey::of);
    OperationText description = s.parse("description", OperationText::of);
    boolean retrySafe = s.bool("retrySafe");
    List<Integer> targets = new ArrayList<>();
    List<JsonValue> raw = s.array("targets");
    for (int i = 0; i < raw.size(); i++) {
      if (!(raw.get(i) instanceof JsonValue.JsonNumber n)
          || n.value() < 0
          || n.value() >= OperationPlan.MAX_TARGETS) {
        throw new OperationFormatException(
            JournalIssueCodes.INVALID_VALUE,
            s.at("targets").index(i),
            "Expected a target index in 0.." + (OperationPlan.MAX_TARGETS - 1));
      }
      targets.add((int) n.value());
    }
    s.finish();
    return JsonFields.construct(
        s.at("targets"), () -> new StepDescriptor(action, description, retrySafe, targets));
  }
}
