// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.manifest;

import static io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestTestSupport.CODEC;
import static io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestTestSupport.assertRejected;
import static io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestTestSupport.goldenText;
import static io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestTestSupport.goldenWith;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.kjmorse2.mconeworld.domain.DomainErrorCode;
import io.github.kjmorse2.mconeworld.domain.archive.ArchiveIssueCodes;
import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import io.github.kjmorse2.mconeworld.fixtures.ManifestFixtures;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Schema 1.1 (manifest-v1.md §3.1): the 1.0 golden still reads, the new members are required from
 * 1.1 on and unknown in 1.0, and their rules are enforced.
 */
class ManifestV11Test {
  private static final byte[] V1_0 = ManifestFixtures.resource(ManifestFixtures.GOLDEN_FULL_V1_0);

  @Test
  void aSchema10ManifestReadsDirectlyAndIsWrittenBackAs11WithEmptyNewMembers() throws Exception {
    DecodedManifest decoded = CODEC.decode(V1_0.clone());
    assertThat(decoded.document()).isEqualTo(ManifestFixtures.fullDocumentV1_0());
    assertThat(decoded.warnings().issues()).isEmpty();
    assertThat(decoded.canonical()).as("canonical bytes are now 1.1").isFalse();
    String written = new String(CODEC.encode(decoded.document()), StandardCharsets.UTF_8);
    assertThat(written)
        .contains("\"schemaVersion\": \"1.1\"")
        .contains("\"world\": null")
        .contains("\"pendingActivation\": null")
        .contains("\"archivedDimensions\": []");
    assertThat(CODEC.decode(written.getBytes(StandardCharsets.UTF_8)).document().state())
        .isEqualTo(decoded.document().state());
  }

  @Test
  void the11MembersAreUnknownInA10Document() {
    String text = new String(V1_0, StandardCharsets.UTF_8);
    byte[] withWorld =
        text.replace("  \"revision\": 42,", "  \"revision\": 42,\n  \"pendingActivation\": null,")
            .getBytes(StandardCharsets.UTF_8);
    assertRejected(withWorld, ManifestIssueCodes.UNKNOWN_MEMBER, ManifestProblemKind.SCHEMA);
  }

  @Test
  void the11MembersAreRequiredIn11() {
    for (String member :
        new String[] {"\"pendingActivation\": ", "\"archivedDimensions\": ", "\"world\": "}) {
      byte[] bytes = replaceValue(goldenText(), member);
      assertRejected(bytes, ManifestIssueCodes.MISSING_MEMBER, ManifestProblemKind.SCHEMA);
    }
  }

  /** Renames {@code member} so the original goes missing (the renamed one is reported too). */
  private static byte[] replaceValue(String text, String member) {
    return text.replace(member, "\"x_" + member.substring(1)).getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void stalePendingActivationIsAWarningButUnknownSeasonsAndBackupsAreErrors() throws Exception {
    byte[] stale =
        goldenWith(
            "\"pendingActivation\": \"5ea50000-0000-4000-8000-000000000002\"",
            "\"pendingActivation\": \"5ea50000-0000-4000-8000-000000000001\"");
    DecodedManifest decoded = CODEC.decode(stale);
    assertThat(decoded.warnings().has(ArchiveIssueCodes.PENDING_ACTIVATION_STALE)).isTrue();

    assertRejected(
        goldenWith(
            "\"pendingActivation\": \"5ea50000-0000-4000-8000-000000000002\"",
            "\"pendingActivation\": \"5ea50000-0000-4000-8000-000000000009\""),
        ArchiveIssueCodes.UNKNOWN_SEASON_REFERENCE,
        ManifestProblemKind.REFERENTIAL);
    assertRejected(
        goldenWith(
            "\"backup\": \"bacc0000-0000-4000-8000-000000000001\"",
            "\"backup\": \"bacc0000-0000-4000-8000-000000000009\""),
        ArchiveIssueCodes.UNKNOWN_BACKUP_REFERENCE,
        ManifestProblemKind.REFERENTIAL);
  }

  @Test
  void bindingValuesAreValidated() {
    assertRejected(
        goldenWith("\"levelName\": \"Golden World\"", "\"levelName\": \" padded\""),
        IssueCode.of(DomainErrorCode.TEXT_INVALID),
        ManifestProblemKind.DOMAIN);
    assertRejected(
        goldenWith("\"dataVersion\": 4903", "\"dataVersion\": -1"),
        IssueCode.of(DomainErrorCode.VALUE_INVALID),
        ManifestProblemKind.DOMAIN);
    assertRejected(
        goldenWith("\"world\": {", "\"world\": [], \"unused\": {"),
        ManifestIssueCodes.WRONG_TYPE,
        ManifestProblemKind.SCHEMA);
  }
}
