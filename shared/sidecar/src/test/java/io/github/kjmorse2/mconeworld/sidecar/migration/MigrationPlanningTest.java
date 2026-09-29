// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.migration;

import static io.github.kjmorse2.mconeworld.sidecar.migration.TestMigrations.relabel;
import static io.github.kjmorse2.mconeworld.sidecar.migration.TestMigrations.v;
import static io.github.kjmorse2.mconeworld.sidecar.migration.TestMigrations.v09;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import io.github.kjmorse2.mconeworld.fixtures.ManifestFixtures;
import io.github.kjmorse2.mconeworld.sidecar.manifest.DecodedManifest;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestCodec;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestLimits;
import io.github.kjmorse2.mconeworld.sidecar.manifest.SchemaVersion;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** Pure planning: byte arrays in, plans or structured refusals out. */
class MigrationPlanningTest {
  private static final ManifestMigration SYNTHETIC = new SyntheticManifest09To10();

  private static MigrationPlanner planner(ManifestMigration... migrations)
      throws MigrationException {
    return new MigrationPlanner(MigrationRegistry.of(List.of(migrations)), ManifestLimits.DEFAULT);
  }

  private static MigrationException assertRefused(Executable action, IssueCode code) {
    MigrationException[] caught = new MigrationException[1];
    assertThatThrownBy(action::execute)
        .isInstanceOfSatisfying(
            MigrationException.class,
            e -> {
              assertThat(e.code()).isEqualTo(code);
              caught[0] = e;
            });
    return caught[0];
  }

  private static byte[] v09With(String from, String to) {
    String text = new String(v09(), StandardCharsets.UTF_8);
    assertThat(text).contains(from);
    return text.replace(from, to).getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void validPlanDescribesEveryTransformationAndValidatesAsCurrent() throws Exception {
    byte[] source = v09();
    byte[] untouched = source.clone();
    MigrationPlan plan = planner(SYNTHETIC).plan(source, ManifestCodec.CURRENT);

    assertThat(source).as("caller's bytes are not mutated").isEqualTo(untouched);
    assertThat(plan.steps()).containsExactly(SyntheticManifest09To10.STEP);
    assertThat(plan.sourceRevision()).isEqualTo(3);
    assertThat(plan.report().source()).isEqualTo(v(0, 9));
    assertThat(plan.report().target()).isEqualTo(ManifestCodec.CURRENT);
    StepReport step = plan.report().steps().get(0);
    assertThat(step.transformed())
        .extracting(StepReport.TransformedField::path)
        .contains(
            "$.archive",
            "$.created",
            "$.active",
            "$.seasons[*].state",
            "$.seasons[*].origins",
            "$.portals",
            "$.notes",
            "$.backups");
    assertThat(step.preservedExtensions()).containsExactly("example:dashboard");
    assertThat(step.unsupported()).isEmpty();
  }

  @Test
  void migratedOutputPreservesDocumentedExtensionsAndMovesNotes() throws Exception {
    MigrationPlan plan = planner(SYNTHETIC).plan(v09(), ManifestCodec.CURRENT);
    DecodedManifest migrated = planner(SYNTHETIC).migrate(v09(), plan);
    assertThat(migrated.document().revision()).isEqualTo(4);
    assertThat(migrated.document().state().activeSeasonId())
        .hasToString("5ea50000-0000-4000-8000-000000000002");
    assertThat(migrated.document().preserved().locations()).containsExactly("$#extensions");
    byte[] encoded = new ManifestCodec().encode(migrated.document());
    assertThat(new String(encoded, StandardCharsets.UTF_8))
        .contains("\"example:dashboard\"")
        .contains("\"mconeworld:legacy_notes\": \"Imported from the 2025 test server\"");
  }

  @Test
  void reportIsDeterministic() throws Exception {
    MigrationPlan first = planner(SYNTHETIC).plan(v09(), ManifestCodec.CURRENT);
    MigrationPlan second = planner(SYNTHETIC).plan(v09(), ManifestCodec.CURRENT);
    assertThat(second).isEqualTo(first);

    // Member order in the source does not change the migrated bytes or the step report.
    byte[] reordered = v09With("\"revision\": 3,\n  \"active\"", "\"active\"").clone();
    String text =
        new String(reordered, StandardCharsets.UTF_8)
            .replace("\"extensions\":", "\"revision\": 3,\n  \"extensions\":");
    MigrationPlan third =
        planner(SYNTHETIC).plan(text.getBytes(StandardCharsets.UTF_8), ManifestCodec.CURRENT);
    assertThat(third.report().migratedChecksum()).isEqualTo(first.report().migratedChecksum());
    assertThat(third.report().steps()).isEqualTo(first.report().steps());

    UUID run = new UUID(1, 2);
    VerifiedBackup backup = backupOf(v09());
    assertThat(MigrationReportFormat.encode(first.report(), run, backup))
        .isEqualTo(MigrationReportFormat.encode(second.report(), run, backup));
  }

  private static VerifiedBackup backupOf(byte[] bytes) throws Exception {
    java.nio.file.Path copy = java.nio.file.Files.createTempFile("mco-backup", ".json");
    try {
      java.nio.file.Files.write(copy, bytes);
      return VerifiedBackup.verify(
          io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.backupId(1), copy, 1 << 20);
    } finally {
      java.nio.file.Files.deleteIfExists(copy);
    }
  }

  @Test
  void missingPath() {
    assertRefused(
        () ->
            new MigrationPlanner(MigrationRegistry.builtIn(), ManifestLimits.DEFAULT)
                .plan(v09(), ManifestCodec.CURRENT),
        MigrationIssueCodes.NO_PATH);
  }

  @Test
  void skippedVersionIsImpossible() throws Exception {
    // A step may not jump over a version...
    assertRefused(
        () -> MigrationRegistry.of(List.of(relabel("jump", v(0, 7), v(0, 9)))),
        MigrationIssueCodes.INVALID_STEP);
    // ...and a gap in the chain (0.8 -> 0.9 exists, 0.9 -> 1.0 does not) is a missing path.
    byte[] v08 = v09With("\"schemaVersion\": \"0.9\"", "\"schemaVersion\": \"0.8\"");
    assertRefused(
        () ->
            planner(relabel("manifest-0.8-to-0.9", v(0, 8), v(0, 9)))
                .plan(v08, ManifestCodec.CURRENT),
        MigrationIssueCodes.NO_PATH);
    // With the missing step registered, the two-step chain is found.
    MigrationPlan chained =
        planner(relabel("manifest-0.8-to-0.9", v(0, 8), v(0, 9)), SYNTHETIC)
            .plan(v08, ManifestCodec.CURRENT);
    assertThat(chained.steps())
        .extracting(s -> s.id().value())
        .containsExactly("manifest-0.8-to-0.9", "manifest-0.9-to-1.0");
  }

  @Test
  void ambiguousPathIsRefused() {
    assertRefused(
        () ->
            planner(SYNTHETIC, relabel("other-0.9-to-1.0", v(0, 9), v(1, 0)))
                .plan(v09(), ManifestCodec.CURRENT),
        MigrationIssueCodes.AMBIGUOUS_PATH);
  }

  @Test
  void duplicateIdsAreRefused() {
    assertRefused(
        () -> MigrationRegistry.of(List.of(SYNTHETIC, new SyntheticManifest09To10())),
        MigrationIssueCodes.INVALID_STEP);
  }

  @Test
  void incompatibleTargets() {
    assertRefused(
        () -> planner(SYNTHETIC).plan(v09(), v(2, 0)), MigrationIssueCodes.INCOMPATIBLE_TARGET);
    assertRefused(
        () -> planner(SYNTHETIC).plan(v09(), v(0, 9)), MigrationIssueCodes.INCOMPATIBLE_TARGET);
  }

  @Test
  void currentMajorNeedsNoMigration() {
    byte[] golden = ManifestFixtures.resource(ManifestFixtures.GOLDEN_FULL_V1);
    assertRefused(
        () -> planner(SYNTHETIC).plan(golden, ManifestCodec.CURRENT),
        MigrationIssueCodes.ALREADY_CURRENT);
    byte[] newerMinor =
        new String(golden, StandardCharsets.UTF_8)
            .replace("\"1.0\"", "\"1.7\"")
            .getBytes(StandardCharsets.UTF_8);
    assertRefused(
        () -> planner(SYNTHETIC).plan(newerMinor, ManifestCodec.CURRENT),
        MigrationIssueCodes.ALREADY_CURRENT);
  }

  @Test
  void implicitDowngradeIsRefused() throws Exception {
    byte[] future = v09With("\"schemaVersion\": \"0.9\"", "\"schemaVersion\": \"2.0\"");
    assertRefused(
        () -> planner(SYNTHETIC).plan(future, ManifestCodec.CURRENT),
        MigrationIssueCodes.DOWNGRADE_UNSUPPORTED);
    // A downgrade is only ever found through a step explicitly written as one.
    MigrationRegistry withDowngrade =
        MigrationRegistry.of(List.of(relabel("manifest-2.0-to-1.0", v(2, 0), v(1, 0))));
    assertThat(withDowngrade.findPath(v(2, 0), v(1, 0))).hasSize(1);
    assertThat(withDowngrade.steps().get(0).isDowngrade()).isTrue();
    assertRefused(
        () -> MigrationRegistry.of(List.of(SYNTHETIC)).findPath(v(1, 0), v(0, 9)),
        MigrationIssueCodes.DOWNGRADE_UNSUPPORTED);
  }

  @Test
  void unsupportedDataIsRefusedWithAReportNotDropped() {
    MigrationException e =
        assertRefused(
            () ->
                planner(SYNTHETIC)
                    .plan(
                        v09With(
                            "\"notes\":",
                            "\"experimental\": {\"fastLeaves\": true},\n  \"notes\":"),
                        ManifestCodec.CURRENT),
            MigrationIssueCodes.UNSUPPORTED_DATA);
    assertThat(e.partialReport())
        .singleElement()
        .satisfies(
            r ->
                assertThat(r.unsupported())
                    .extracting(StepReport.TransformedField::path)
                    .containsExactly("$.experimental"));

    byte[] duplicateOrigin =
        v09With(
            "{\"dimension\": \"minecraft:the_nether\", \"x\": 0, \"y\": 64, \"z\": 0}",
            "{\"dimension\": \"minecraft:overworld\", \"x\": 128, \"y\": 64, \"z\": 0}");
    assertRefused(
        () -> planner(SYNTHETIC).plan(duplicateOrigin, ManifestCodec.CURRENT),
        MigrationIssueCodes.UNSUPPORTED_DATA);
  }

  @Test
  void emptyExperimentalIsFine() throws Exception {
    MigrationPlan plan =
        planner(SYNTHETIC)
            .plan(
                v09With("\"notes\":", "\"experimental\": {},\n  \"notes\":"),
                ManifestCodec.CURRENT);
    assertThat(plan.report().steps().get(0).unsupported()).isEmpty();
  }

  @Test
  void undocumentedDataFailsThePrerequisite() {
    assertRefused(
        () ->
            planner(SYNTHETIC)
                .plan(
                    v09With("\"notes\":", "\"colour\": \"blue\",\n  \"notes\":"),
                    ManifestCodec.CURRENT),
        MigrationIssueCodes.PREREQUISITE_FAILED);
  }

  @Test
  void targetValidationFailure() {
    MigrationException e =
        assertRefused(
            () -> planner(TestMigrations.brokenOutput()).plan(v09(), ManifestCodec.CURRENT),
            MigrationIssueCodes.TARGET_INVALID);
    assertThat(e.validation()).isNotNull();
    assertThat(
            e.validation()
                .has(
                    io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestIssueCodes
                        .MISSING_MEMBER))
        .isTrue();
  }

  @Test
  void unreadableSource() {
    assertRefused(
        () ->
            planner(SYNTHETIC)
                .plan("not json".getBytes(StandardCharsets.UTF_8), ManifestCodec.CURRENT),
        MigrationIssueCodes.SOURCE_UNREADABLE);
    assertRefused(
        () ->
            planner(SYNTHETIC)
                .plan(
                    "{\"format\": \"other\"}".getBytes(StandardCharsets.UTF_8),
                    ManifestCodec.CURRENT),
        MigrationIssueCodes.SOURCE_UNREADABLE);
  }

  @Test
  void stepsMustBeSingleVersionSteps() {
    assertThat(new MigrationStep(new MigrationId("a"), v(0, 9), v(1, 0), List.of()).isSingleStep())
        .isTrue();
    assertThat(new MigrationStep(new MigrationId("a"), v(1, 3), v(1, 4), List.of()).isSingleStep())
        .isTrue();
    assertThat(new MigrationStep(new MigrationId("a"), v(1, 4), v(1, 3), List.of()).isSingleStep())
        .isTrue();
    assertThat(new MigrationStep(new MigrationId("a"), v(1, 3), v(1, 5), List.of()).isSingleStep())
        .isFalse();
    assertThat(new MigrationStep(new MigrationId("a"), v(1, 3), v(3, 0), List.of()).isSingleStep())
        .isFalse();
    assertThat(new MigrationStep(new MigrationId("a"), v(1, 3), v(2, 1), List.of()).isSingleStep())
        .isFalse();
    assertThat(SchemaVersion.parse("0.9")).isEqualTo(v(0, 9));
  }
}
