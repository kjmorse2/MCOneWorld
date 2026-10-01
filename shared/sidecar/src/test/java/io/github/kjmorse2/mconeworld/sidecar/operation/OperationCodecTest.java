// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.operation;

import static io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.instant;
import static io.github.kjmorse2.mconeworld.fixtures.OperationFixtures.checksum;
import static io.github.kjmorse2.mconeworld.fixtures.OperationFixtures.failure;
import static io.github.kjmorse2.mconeworld.fixtures.OperationFixtures.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.domain.DomainErrorCode;
import io.github.kjmorse2.mconeworld.domain.operation.ActionKey;
import io.github.kjmorse2.mconeworld.domain.operation.BackupRequirement;
import io.github.kjmorse2.mconeworld.domain.operation.ExpectedManifest;
import io.github.kjmorse2.mconeworld.domain.operation.ImpactClass;
import io.github.kjmorse2.mconeworld.domain.operation.JournalEntry;
import io.github.kjmorse2.mconeworld.domain.operation.JournalEvent;
import io.github.kjmorse2.mconeworld.domain.operation.OperationPlan;
import io.github.kjmorse2.mconeworld.domain.operation.OperationProgress;
import io.github.kjmorse2.mconeworld.domain.operation.OperationState;
import io.github.kjmorse2.mconeworld.domain.operation.OperationTarget;
import io.github.kjmorse2.mconeworld.domain.operation.OperationType;
import io.github.kjmorse2.mconeworld.domain.operation.PreconditionSnapshot;
import io.github.kjmorse2.mconeworld.domain.operation.RollbackDeclaration;
import io.github.kjmorse2.mconeworld.domain.operation.RollbackStrategy;
import io.github.kjmorse2.mconeworld.domain.operation.StepDescriptor;
import io.github.kjmorse2.mconeworld.domain.operation.ToolIdentity;
import io.github.kjmorse2.mconeworld.domain.operation.ValidationDescriptor;
import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import io.github.kjmorse2.mconeworld.fixtures.DomainFixtures;
import io.github.kjmorse2.mconeworld.fixtures.ManifestFixtures;
import io.github.kjmorse2.mconeworld.fixtures.OperationFixtures;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonIssueCodes;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Plan and journal-entry encodings: golden bytes, round trips, digest, and version policy. */
class OperationCodecTest {
  private static final OperationPlanCodec PLANS = new OperationPlanCodec();
  private static final JournalEntryCodec ENTRIES = new JournalEntryCodec();
  private static final OperationPlan FULL = OperationFixtures.fullPlan();

  private static Properties meta(String resource) throws IOException {
    Properties meta = new Properties();
    meta.load(new ByteArrayInputStream(ManifestFixtures.resource(resource + ".meta")));
    return meta;
  }

  private static String utf8(byte[] bytes) {
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  @Nested
  class Golden {
    @Test
    void planEncodingIsPinned() throws Exception {
      byte[] golden = ManifestFixtures.resource(OperationFixtures.GOLDEN_PLAN_V1);
      Properties meta = meta(OperationFixtures.GOLDEN_PLAN_V1);
      assertThat(meta.getProperty("format")).isEqualTo(OperationPlanCodec.FORMAT);
      assertThat(meta.getProperty("schemaVersion"))
          .isEqualTo(OperationPlanCodec.CURRENT.toString());
      assertThat(PLANS.encode(FULL)).isEqualTo(golden);
      assertThat(PLANS.digest(FULL).hex()).isEqualTo(meta.getProperty("sha256"));
      DecodedPlan decoded = PLANS.decode(golden);
      assertThat(decoded.plan()).isEqualTo(FULL);
      assertThat(decoded.digest()).isEqualTo(PLANS.digest(FULL));
      assertThat(decoded.executable()).isTrue();
      assertThat(decoded.unknownMembers()).isEmpty();
    }

    @Test
    void journalEncodingIsPinned() throws Exception {
      byte[] golden = ManifestFixtures.resource(OperationFixtures.GOLDEN_JOURNAL_V1);
      Properties meta = meta(OperationFixtures.GOLDEN_JOURNAL_V1);
      assertThat(meta.getProperty("format")).isEqualTo(JournalEntryCodec.FORMAT);
      assertThat(OperationPlanCodec.sha256(golden).hex()).isEqualTo(meta.getProperty("sha256"));

      Checksum digest = PLANS.digest(FULL);
      List<JournalEvent> events = new ArrayList<>();
      events.add(OperationProgress.firstEvent(FULL, digest));
      events.addAll(OperationFixtures.goldenJournalEvents(digest));
      String[] lines = utf8(golden).split("\n", -1);
      assertThat(lines).hasSize(events.size() + 1);
      assertThat(lines[lines.length - 1]).isEmpty();
      assertThat(String.valueOf(events.size())).isEqualTo(meta.getProperty("entries"));

      Checksum previous = null;
      OperationProgress progress = null;
      for (int i = 0; i < events.size(); i++) {
        JournalEntryCodec.Decoded decoded = ENTRIES.decode(bytes(lines[i]));
        assertThat(decoded.entry().sequence()).isEqualTo(i);
        assertThat(decoded.entry().event()).isEqualTo(events.get(i));
        assertThat(decoded.entry().recordedAt()).isEqualTo(instant(100 + i));
        assertThat(decoded.previous()).isEqualTo(previous);
        assertThat(ENTRIES.encode(decoded.entry(), FULL.id(), previous).line())
            .isEqualTo(bytes(lines[i] + "\n"));
        progress =
            progress == null
                ? OperationProgress.planned(FULL, digest)
                : progress.apply(decoded.entry());
        previous = decoded.checksum();
      }
      assertThat(progress.state()).isEqualTo(OperationState.ROLLED_BACK);
    }
  }

  @Test
  void publishedFormatExamplesAreExactlyTheGoldens() throws Exception {
    // The Gradle test task runs in the project directory (shared/sidecar).
    Path examples = Path.of("../../docs/formats/examples");
    assertThat(Files.readAllBytes(examples.resolve("operation-plan-v1-example.json")))
        .isEqualTo(ManifestFixtures.resource(OperationFixtures.GOLDEN_PLAN_V1));
    assertThat(Files.readAllBytes(examples.resolve("operation-journal-v1-example.jsonl")))
        .isEqualTo(ManifestFixtures.resource(OperationFixtures.GOLDEN_JOURNAL_V1));
  }

  @Nested
  class Entries {
    @Test
    void everyEventTypeRoundTrips() throws Exception {
      Checksum digest = PLANS.digest(FULL);
      List<JournalEvent> events =
          List.of(
              OperationProgress.firstEvent(FULL, digest),
              new JournalEvent.BackupConfirmed(OperationFixtures.BACKUP),
              new JournalEvent.BackupConfirmed(null),
              new JournalEvent.Cancelled(failure("Precondition changed")),
              new JournalEvent.ApplyStarted(digest, FULL.preconditions()),
              new JournalEvent.ApplyStarted(
                  digest, PreconditionSnapshot.ofManifest(new ExpectedManifest.Absent())),
              new JournalEvent.StepStarted(2, 3),
              new JournalEvent.StepCompleted(2),
              new JournalEvent.ValidationStarted(),
              new JournalEvent.CheckPassed(1),
              new JournalEvent.OperationCompleted(),
              new JournalEvent.OperationFailed(failure("x"), null),
              new JournalEvent.OperationFailed(failure("y"), 0),
              new JournalEvent.RollbackStarted(1),
              new JournalEvent.RollbackCompleted(),
              new JournalEvent.RollbackFailed(failure("z \"quoted\" \\ ünïcødé")));
      Set<Object> types = new HashSet<>();
      for (JournalEvent event : events) {
        JournalEntry entry = new JournalEntry(5, instant(7), event, OperationState.APPLYING);
        JournalEntryCodec.Encoded encoded = ENTRIES.encode(entry, FULL.id(), checksum('9'));
        byte[] line = encoded.line();
        assertThat(line[line.length - 1]).isEqualTo((byte) '\n');
        int newlines = 0;
        for (byte b : line) {
          newlines += b == '\n' ? 1 : 0;
        }
        assertThat(newlines).as("one line per entry").isEqualTo(1);
        JournalEntryCodec.Decoded decoded =
            ENTRIES.decode(java.util.Arrays.copyOf(line, line.length - 1));
        assertThat(decoded.entry()).isEqualTo(entry);
        assertThat(decoded.checksum()).isEqualTo(encoded.checksum());
        assertThat(decoded.previous()).isEqualTo(checksum('9'));
        types.add(event.type());
      }
      assertThat(types)
          .hasSize(io.github.kjmorse2.mconeworld.domain.operation.JournalEventType.values().length);
    }

    @Test
    void alteredEntryFailsItsChecksum() throws Exception {
      JournalEntry entry =
          new JournalEntry(
              1, instant(1), new JournalEvent.StepCompleted(0), OperationState.APPLYING);
      String line = utf8(ENTRIES.encode(entry, FULL.id(), checksum('1')).line()).trim();
      assertCode(
          line.replace("\"step\":0", "\"step\":1"), JournalIssueCodes.ENTRY_CHECKSUM_MISMATCH);
      assertCode(line.substring(0, line.length() - 5), JsonIssueCodes.MALFORMED);
      assertCode("", JsonIssueCodes.MALFORMED);
    }

    @Test
    void unknownMembersOnlyFromANewerMinorVersion() throws Exception {
      JournalEntry entry =
          new JournalEntry(
              1, instant(1), new JournalEvent.StepCompleted(0), OperationState.APPLYING);
      // Build entries with an extra member and a recomputed checksum, as a newer writer would.
      String current = withExtraMember(entry, "1.0");
      assertCode(current, JournalIssueCodes.UNKNOWN_MEMBER);
      JournalEntryCodec.Decoded newer = ENTRIES.decode(bytes(withExtraMember(entry, "1.3")));
      assertThat(newer.entry()).isEqualTo(entry);
      assertThat(newer.unknownMembers()).containsExactly("$[futureMember]");
      assertCode(withExtraMember(entry, "2.0"), JournalIssueCodes.UNSUPPORTED_VERSION);
    }

    private String withExtraMember(JournalEntry entry, String version) throws Exception {
      String line = utf8(ENTRIES.encode(entry, FULL.id(), checksum('1')).line()).trim();
      String body =
          line.replaceFirst("\"checksum\":\"sha256:[0-9a-f]{64}\",", "")
              .replace("\"schemaVersion\":\"1.0\"", "\"schemaVersion\":\"" + version + "\"")
              .replace("{\"event\"", "{\"futureMember\":true,\"event\"");
      // The checksum covers the compact canonical form without "checksum": recompute it.
      var tree =
          new io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonReader(
                  OperationJson.limits(1 << 20))
              .read(bytes(body));
      Checksum sum =
          OperationPlanCodec.sha256(
              io.github.kjmorse2.mconeworld.sidecar.internal.json.CanonicalJsonWriter.writeCompact(
                  tree));
      return body.replaceFirst("\\{", "{\"checksum\":\"" + sum + "\",");
    }

    private void assertCode(String line, IssueCode code) {
      assertThatThrownBy(() -> ENTRIES.decode(bytes(line)))
          .isInstanceOfSatisfying(
              OperationFormatException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
  }

  @Nested
  class Digest {
    @Test
    void digestIsDeterministicAndIgnoresFormatting() throws Exception {
      assertThat(PLANS.digest(OperationFixtures.fullPlan())).isEqualTo(PLANS.digest(FULL));
      String pretty = utf8(PLANS.encode(FULL));
      String compact = pretty.replaceAll("\\n\\s*", "").replace("\": ", "\":");
      DecodedPlan reformatted = PLANS.decode(bytes(compact));
      assertThat(reformatted.digest()).isEqualTo(PLANS.digest(FULL));
      assertThat(reformatted.plan()).isEqualTo(FULL);
    }

    @Test
    void everyPlanComponentChangesTheDigest() {
      List<UnaryOperator<OperationPlanParts>> changes =
          List.of(
              p -> p.id(DomainFixtures.operationId(9)),
              p -> p.type(OperationType.of("mconeworld:other")),
              p -> p.createdAt(instant(61)),
              p -> p.createdBy(ToolIdentity.of("mconeworld-tool", "0.1.1")),
              p -> p.archive(DomainFixtures.archiveId(2)),
              p -> p.impact(ImpactClass.WORLD_MUTATING),
              p -> p.targets(FULL.targets().subList(0, 5).reversed()),
              p ->
                  p.preconditions(PreconditionSnapshot.ofManifest(OperationFixtures.manifestAt7())),
              p -> p.backup(new BackupRequirement.NewBackup()),
              p -> p.steps(List.of(FULL.steps().get(0))),
              p ->
                  p.steps(
                      List.of(
                          new StepDescriptor(
                              FULL.steps().get(0).action(),
                              FULL.steps().get(0).description(),
                              true,
                              FULL.steps().get(0).targets()))),
              p -> p.validations(FULL.validations().subList(0, 1)),
              p ->
                  p.validations(
                      List.of(
                          new ValidationDescriptor(
                              ActionKey.of("mconeworld:verify_regions"), text("Reopen regions")),
                          FULL.validations().get(1))),
              p ->
                  p.rollback(
                      new RollbackDeclaration(
                          RollbackStrategy.MANUAL, FULL.rollback().description())),
              p ->
                  p.rollback(
                      new RollbackDeclaration(
                          RollbackStrategy.RESTORE_BACKUP, text("Restore it"))));
      Set<Checksum> digests = new HashSet<>();
      digests.add(PLANS.digest(FULL));
      for (UnaryOperator<OperationPlanParts> change : changes) {
        assertThat(digests.add(PLANS.digest(change.apply(new OperationPlanParts(FULL)).build())))
            .isTrue();
      }
      assertThat(digests).hasSize(changes.size() + 1);
    }

    @Test
    void targetOrderMatters() {
      List<OperationTarget> swapped = new ArrayList<>(FULL.targets());
      swapped.set(3, FULL.targets().get(4));
      swapped.set(4, FULL.targets().get(3));
      List<StepDescriptor> steps = List.of(FULL.steps().get(0), FULL.steps().get(1));
      OperationPlan a = new OperationPlanParts(FULL).steps(steps).build();
      OperationPlan b = new OperationPlanParts(FULL).steps(steps).targets(swapped).build();
      assertThat(PLANS.digest(a)).isNotEqualTo(PLANS.digest(b));
    }
  }

  @Nested
  class Compatibility {
    private final String golden = utf8(ManifestFixtures.resource(OperationFixtures.GOLDEN_PLAN_V1));

    private String withMember(String version) {
      return golden
          .replace("\"schemaVersion\": \"1.0\"", "\"schemaVersion\": \"" + version + "\"")
          .replace(
              "\"type\": \"mconeworld:chunk_deletion\",",
              "\"type\": \"mconeworld:chunk_deletion\",\n  \"futureMember\": [1, 2],");
    }

    @Test
    void unknownMemberIsRejectedAtTheCurrentVersion() {
      assertPlanCode(withMember("1.0"), JournalIssueCodes.UNKNOWN_MEMBER);
    }

    @Test
    void newerMinorIsReadableButNotExecutableAndItsMembersCountInTheDigest() throws Exception {
      DecodedPlan newer = PLANS.decode(bytes(withMember("1.4")));
      assertThat(newer.plan()).isEqualTo(FULL);
      assertThat(newer.executable()).isFalse();
      assertThat(newer.unknownMembers()).containsExactly("$[futureMember]");
      assertThat(newer.digest()).isNotEqualTo(PLANS.digest(FULL));
      DecodedPlan other = PLANS.decode(bytes(withMember("1.4").replace("[1, 2]", "[1, 3]")));
      assertThat(other.digest()).isNotEqualTo(newer.digest());
    }

    @Test
    void otherFormatsAndMajorsAreRejected() {
      assertPlanCode(
          golden.replace("\"schemaVersion\": \"1.0\"", "\"schemaVersion\": \"2.0\""),
          JournalIssueCodes.UNSUPPORTED_VERSION);
      assertPlanCode(
          golden.replace("mconeworld-operation-plan", "mconeworld-manifest"),
          JournalIssueCodes.WRONG_FORMAT);
      assertPlanCode(
          golden.replace("\"retrySafe\": false", "\"retrySafe\": 0"), JournalIssueCodes.WRONG_TYPE);
      assertPlanCode(
          golden.replace("\"kind\": \"sidecarManifest\"", "\"kind\": \"region\""),
          JournalIssueCodes.INVALID_VALUE);
      assertPlanCode(
          golden.replace("\"strategy\": \"RESTORE_BACKUP\"", "\"strategy\": \"NOT_APPLICABLE\""),
          IssueCode.of(DomainErrorCode.OPERATION_PLAN_INVALID));
      assertPlanCode(
          golden.replace("world:level.dat", "/srv/world/level.dat"),
          IssueCode.of(DomainErrorCode.KEY_MALFORMED));
      assertPlanCode(
          golden.replace("\"id\": \"0be4", "\"identity\": \"0be4"),
          JournalIssueCodes.MISSING_MEMBER);
      assertPlanCode("{", JsonIssueCodes.MALFORMED);
    }

    private void assertPlanCode(String json, IssueCode code) {
      assertThatThrownBy(() -> PLANS.decode(bytes(json)))
          .isInstanceOfSatisfying(
              OperationFormatException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
  }

  /** A mutable copy of a plan's components, for building variations. */
  static final class OperationPlanParts {
    private io.github.kjmorse2.mconeworld.domain.OperationId id;
    private OperationType type;
    private java.time.Instant createdAt;
    private ToolIdentity createdBy;
    private io.github.kjmorse2.mconeworld.domain.ArchiveId archive;
    private ImpactClass impact;
    private List<OperationTarget> targets;
    private PreconditionSnapshot preconditions;
    private BackupRequirement backup;
    private List<StepDescriptor> steps;
    private List<ValidationDescriptor> validations;
    private RollbackDeclaration rollback;

    OperationPlanParts(OperationPlan p) {
      id = p.id();
      type = p.type();
      createdAt = p.createdAt();
      createdBy = p.createdBy();
      archive = p.archive();
      impact = p.impact();
      targets = p.targets();
      preconditions = p.preconditions();
      backup = p.backup();
      steps = p.steps();
      validations = p.validations();
      rollback = p.rollback();
    }

    OperationPlanParts id(io.github.kjmorse2.mconeworld.domain.OperationId v) {
      id = v;
      return this;
    }

    OperationPlanParts type(OperationType v) {
      type = v;
      return this;
    }

    OperationPlanParts createdAt(java.time.Instant v) {
      createdAt = v;
      return this;
    }

    OperationPlanParts createdBy(ToolIdentity v) {
      createdBy = v;
      return this;
    }

    OperationPlanParts archive(io.github.kjmorse2.mconeworld.domain.ArchiveId v) {
      archive = v;
      return this;
    }

    OperationPlanParts impact(ImpactClass v) {
      impact = v;
      return this;
    }

    OperationPlanParts targets(List<OperationTarget> v) {
      targets = v;
      return this;
    }

    OperationPlanParts preconditions(PreconditionSnapshot v) {
      preconditions = v;
      return this;
    }

    OperationPlanParts backup(BackupRequirement v) {
      backup = v;
      return this;
    }

    OperationPlanParts steps(List<StepDescriptor> v) {
      steps = v;
      return this;
    }

    OperationPlanParts validations(List<ValidationDescriptor> v) {
      validations = v;
      return this;
    }

    OperationPlanParts rollback(RollbackDeclaration v) {
      rollback = v;
      return this;
    }

    OperationPlan build() {
      return new OperationPlan(
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
          rollback);
    }
  }
}
