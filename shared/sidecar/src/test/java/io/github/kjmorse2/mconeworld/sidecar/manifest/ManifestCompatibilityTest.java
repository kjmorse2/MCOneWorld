// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.manifest;

import static io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestTestSupport.CODEC;
import static io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestTestSupport.assertRejected;
import static io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestTestSupport.golden;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.kjmorse2.mconeworld.domain.DisplayName;
import io.github.kjmorse2.mconeworld.domain.validation.Severity;
import io.github.kjmorse2.mconeworld.fixtures.DomainFixtures;
import io.github.kjmorse2.mconeworld.fixtures.ManifestFixtures;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Forward compatibility: newer-minor unknown members and namespaced extensions survive
 * read-modify-write.
 */
class ManifestCompatibilityTest {
  private static final String SEASON_3 = "5ea50000-0000-4000-8000-000000000003";

  /**
   * Golden rewritten as schema 1.7 with unknown members at root, in a season, a nested position,
   * and a file.
   */
  private static byte[] newerMinorWithUnknownMembers() {
    String text =
        new String(golden(), StandardCharsets.UTF_8)
            .replace("\"schemaVersion\": \"1.0\"", "\"schemaVersion\": \"1.7\"")
            .replace(
                "\"revision\": 42,",
                "\"revision\": 42,\n  \"retention\": {\"days\": 30, \"policy\": [\"keep\"]},")
            .replace("\"name\": \"Next\",", "\"name\": \"Next\",\n      \"theme\": \"autumn\",")
            .replace("\"x\": 3145728,", "\"x\": 3145728,\n        \"facing\": \"north\",")
            .replace(
                "\"role\": \"report\",", "\"role\": \"report\",\n      \"compression\": null,");
    return text.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void newerMinorVersionIsAcceptedWithAWarningAndPreservesUnknownMembers() throws Exception {
    DecodedManifest decoded = CODEC.decode(newerMinorWithUnknownMembers());
    assertThat(decoded.warnings().issues())
        .singleElement()
        .satisfies(
            i -> {
              assertThat(i.code()).isEqualTo(ManifestIssueCodes.NEWER_MINOR_VERSION);
              assertThat(i.severity()).isEqualTo(Severity.WARNING);
            });
    assertThat(decoded.document().schemaVersion()).isEqualTo(new SchemaVersion(1, 7));
    assertThat(decoded.document().preserved().locations())
        .containsExactlyInAnyOrder(
            "$",
            "seasons/" + SEASON_3,
            "seasons/" + SEASON_3 + "/spawn",
            "files/reports/import-scan.json");
    // The domain view is exactly what a 1.0 file with the same known members would give.
    assertThat(decoded.document().state()).isEqualTo(ManifestFixtures.fullDocument().state());
  }

  @Test
  void readModifyWriteKeepsUnknownMembersAndTheNewerVersionLabel() throws Exception {
    ManifestDocument read = CODEC.decode(newerMinorWithUnknownMembers()).document();
    ManifestDocument modified =
        read.withState(
                read.state().renameSeason(DomainFixtures.seasonId(3), DisplayName.of("Renamed")))
            .nextRevision();

    String written = new String(CODEC.encode(modified), StandardCharsets.UTF_8);
    assertThat(written)
        .contains("\"schemaVersion\": \"1.7\"")
        .contains("\"theme\": \"autumn\"")
        .contains("\"facing\": \"north\"")
        .contains("\"compression\": null")
        .contains("\"days\": 30")
        .contains("\"name\": \"Renamed\"")
        .contains("\"revision\": 43");

    DecodedManifest reread = CODEC.decode(written.getBytes(StandardCharsets.UTF_8));
    assertThat(reread.document().preserved()).isEqualTo(read.preserved());
    assertThat(reread.canonical()).isTrue();
  }

  @Test
  void preservedMembersDisappearWithTheirRecord() throws Exception {
    ManifestDocument read = CODEC.decode(newerMinorWithUnknownMembers()).document();
    // Cancel-and-drop is not a domain operation, so drop season 3 by rebuilding the state without
    // it.
    var state = read.state();
    var withoutSeason3 =
        new io.github.kjmorse2.mconeworld.domain.archive.ArchiveState(
            state.seasons().subList(0, 2),
            state.activeSeasonId(),
            state.protectedRegions(),
            state.portalMarkers(),
            state.backups(),
            state.playerArchives());
    String written =
        new String(CODEC.encode(read.withState(withoutSeason3)), StandardCharsets.UTF_8);
    assertThat(written).doesNotContain("autumn").doesNotContain("north").contains("\"days\": 30");
  }

  @Test
  void extensionsArePreservedInAnyVersion() throws Exception {
    String text =
        new String(golden(), StandardCharsets.UTF_8)
            .replace(
                "\"revision\": 42,",
                "\"revision\": 42,\n  \"extensions\": {\"example:dashboard\": {\"pinned\": true}},")
            .replace(
                "\"name\": \"Genesis\",",
                "\"name\": \"Genesis\",\n      \"extensions\": {\"example:color\": \"#00ff00\"},");
    DecodedManifest decoded = CODEC.decode(text.getBytes(StandardCharsets.UTF_8));
    assertThat(decoded.warnings().issues()).isEmpty();
    assertThat(decoded.document().schemaVersion()).isEqualTo(ManifestCodec.CURRENT);
    String written =
        new String(CODEC.encode(decoded.document().nextRevision()), StandardCharsets.UTF_8);
    assertThat(written)
        .contains("\"example:dashboard\"")
        .contains("\"example:color\": \"#00ff00\"");
    assertThat(CODEC.decode(written.getBytes(StandardCharsets.UTF_8)).document().preserved())
        .isEqualTo(decoded.document().preserved());
  }

  @Test
  void extensionKeysMustBeNamespacedAndExtensionsMustBeObjects() {
    assertRejected(
        ManifestTestSupport.goldenWith(
            "\"revision\": 42,", "\"revision\": 42,\n  \"extensions\": {\"plain\": 1},"),
        ManifestIssueCodes.INVALID_VALUE,
        ManifestProblemKind.SCHEMA);
    assertRejected(
        ManifestTestSupport.goldenWith(
            "\"revision\": 42,", "\"revision\": 42,\n  \"extensions\": [],"),
        ManifestIssueCodes.WRONG_TYPE,
        ManifestProblemKind.SCHEMA);
    // Extensions are not allowed inside plain value objects such as positions.
    assertRejected(
        ManifestTestSupport.goldenWith(
            "\"x\": 3145728,", "\"x\": 3145728,\n        \"extensions\": {},"),
        ManifestIssueCodes.UNKNOWN_MEMBER,
        ManifestProblemKind.SCHEMA);
  }

  @Test
  void newerMinorStillValidatesEverythingItUnderstands() {
    String text =
        new String(newerMinorWithUnknownMembers(), StandardCharsets.UTF_8)
            .replace("\"revision\": 42", "\"revision\": -5");
    assertRejected(
        text.getBytes(StandardCharsets.UTF_8),
        ManifestIssueCodes.INVALID_VALUE,
        ManifestProblemKind.SCHEMA);
  }
}
