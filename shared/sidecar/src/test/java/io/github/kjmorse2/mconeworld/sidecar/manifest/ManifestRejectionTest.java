// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.manifest;

import static io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestProblemKind.DOMAIN;
import static io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestProblemKind.LIMIT;
import static io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestProblemKind.REFERENTIAL;
import static io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestProblemKind.SCHEMA;
import static io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestProblemKind.SYNTAX;
import static io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestProblemKind.UNSUPPORTED_VERSION;
import static io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestTestSupport.assertEncodeRejected;
import static io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestTestSupport.assertRejected;
import static io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestTestSupport.golden;
import static io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestTestSupport.goldenWith;
import static io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestTestSupport.rejected;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import io.github.kjmorse2.mconeworld.domain.validation.Severity;
import io.github.kjmorse2.mconeworld.fixtures.ManifestFixtures;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonIssueCodes;
import io.github.kjmorse2.mconeworld.sidecar.storage.SidecarPath;
import io.github.kjmorse2.mconeworld.sidecar.storage.UnsafeSidecarPathException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Each test targets one documented failure, derived from a copy of the golden manifest. */
class ManifestRejectionTest {
  private static final String SEASON_1 = "5ea50000-0000-4000-8000-000000000001";
  private static final String SEASON_2 = "5ea50000-0000-4000-8000-000000000002";

  // --- syntax and limits -----------------------------------------------------------------------

  @Test
  void truncationAtAnyPointIsASyntaxError() {
    byte[] full = golden();
    for (int length = 0; length < full.length - 1; length += 37) {
      ManifestException e = rejected(Arrays.copyOf(full, length));
      assertThat(e.primaryKind()).as("truncated to %d bytes", length).isIn(SYNTAX);
    }
  }

  @Test
  void duplicateKeysAreSyntaxErrors() {
    assertRejected(
        goldenWith("\"revision\": 42,", "\"revision\": 42,\n  \"revision\": 43,"),
        JsonIssueCodes.DUPLICATE_KEY,
        SYNTAX);
  }

  @Test
  void malformedUnicodeIsASyntaxError() {
    byte[] bytes = goldenWith("\"Genesis\"", "\"Gen\u0001esis\"").clone();
    // Replace the U+0001 placeholder byte with an invalid UTF-8 lead byte.
    for (int i = 0; i < bytes.length; i++) {
      if (bytes[i] == 0x01) {
        bytes[i] = (byte) 0xC3;
        bytes[i + 1] = (byte) 0x28;
      }
    }
    assertRejected(bytes, JsonIssueCodes.INVALID_UTF8, SYNTAX);
    assertRejected(
        goldenWith("\"Genesis\"", "\"Gen\\ud800esis\""), JsonIssueCodes.UNPAIRED_SURROGATE, SYNTAX);
    byte[] bom = new byte[golden().length + 3];
    bom[0] = (byte) 0xEF;
    bom[1] = (byte) 0xBB;
    bom[2] = (byte) 0xBF;
    System.arraycopy(golden(), 0, bom, 3, golden().length);
    assertRejected(bom, JsonIssueCodes.BOM, SYNTAX);
  }

  @Test
  void floatsAreRejected() {
    assertRejected(
        goldenWith("\"revision\": 42", "\"revision\": 42.0"),
        JsonIssueCodes.NON_INTEGER_NUMBER,
        SYNTAX);
  }

  @Test
  void excessiveSizesAreLimitErrors() throws Exception {
    ManifestCodec small = new ManifestCodec(new ManifestLimits(1024, 32, 8192, 100, 100));
    ManifestException e = rejectedBy(small, golden());
    assertThat(e.issues().has(JsonIssueCodes.LIMIT_DOCUMENT_SIZE)).isTrue();
    assertThat(e.primaryKind()).isEqualTo(LIMIT);

    ManifestCodec shallow = new ManifestCodec(new ManifestLimits(1 << 20, 3, 8192, 100, 100));
    assertThat(rejectedBy(shallow, golden()).issues().has(JsonIssueCodes.LIMIT_NESTING_DEPTH))
        .isTrue();

    ManifestCodec fewElements = new ManifestCodec(new ManifestLimits(1 << 20, 32, 8192, 2, 100));
    assertThat(rejectedBy(fewElements, golden()).issues().has(JsonIssueCodes.LIMIT_ARRAY_ELEMENTS))
        .isTrue();

    ManifestCodec shortStrings = new ManifestCodec(new ManifestLimits(1 << 20, 32, 16, 100, 100));
    assertThat(rejectedBy(shortStrings, golden()).issues().has(JsonIssueCodes.LIMIT_STRING_LENGTH))
        .isTrue();

    // Streams are read with a bound: an endless stream is rejected without being consumed fully.
    java.io.InputStream endless =
        new java.io.InputStream() {
          @Override
          public int read() {
            return ' ';
          }
        };
    try {
      small.decode(endless);
      throw new AssertionError("expected rejection");
    } catch (ManifestException ex) {
      assertThat(ex.primaryKind()).isEqualTo(LIMIT);
    }
    assertThat(
            small
                .decode(
                    new ByteArrayInputStream(
                        new ManifestCodec()
                            .encode(
                                io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestDocument
                                    .create(
                                        io.github.kjmorse2.mconeworld.fixtures.DomainFixtures
                                            .archiveId(1),
                                        io.github.kjmorse2.mconeworld.fixtures.DomainFixtures
                                            .instant(0)))))
                .document()
                .revision())
        .isZero();
  }

  private static ManifestException rejectedBy(ManifestCodec codec, byte[] bytes) {
    try {
      codec.decode(bytes);
    } catch (ManifestException e) {
      return e;
    }
    throw new AssertionError("Expected rejection");
  }

  // --- version -------------------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(strings = {"2.0", "0.9", "9999.0"})
  void unsupportedMajorVersionsFailClosed(String version) {
    ManifestException e =
        assertRejected(
            goldenWith("\"schemaVersion\": \"1.0\"", "\"schemaVersion\": \"" + version + "\""),
            ManifestIssueCodes.UNSUPPORTED_VERSION,
            UNSUPPORTED_VERSION);
    // Fail closed: nothing else is interpreted, so no schema noise for a format we do not know.
    assertThat(e.issues().issues()).hasSize(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"1", "1.0.0", "01.0", "1.00", "v1.0", ""})
  void malformedVersionTextIsASchemaError(String version) {
    assertRejected(
        goldenWith("\"schemaVersion\": \"1.0\"", "\"schemaVersion\": \"" + version + "\""),
        ManifestIssueCodes.INVALID_VALUE,
        SCHEMA);
  }

  @Test
  void notAManifest() {
    assertRejected(
        goldenWith("\"format\": \"mconeworld-manifest\"", "\"format\": \"something-else\""),
        ManifestIssueCodes.NOT_A_MANIFEST,
        SCHEMA);
    assertRejected(
        "[]".getBytes(StandardCharsets.UTF_8), ManifestIssueCodes.NOT_A_MANIFEST, SCHEMA);
  }

  // --- schema --------------------------------------------------------------------------------

  @Test
  void missingAndMistypedMembers() {
    assertRejected(
        goldenWith("\"revision\": 42,\n", ""), ManifestIssueCodes.MISSING_MEMBER, SCHEMA);
    assertRejected(
        goldenWith("\"revision\": 42", "\"revision\": \"42\""),
        ManifestIssueCodes.WRONG_TYPE,
        SCHEMA);
    // Nullable members must be present (null), not absent.
    assertRejected(
        goldenWith("      \"destinationSeason\": null,\n", ""),
        ManifestIssueCodes.MISSING_MEMBER,
        SCHEMA);
  }

  @Test
  void unknownMemberInSameMinorVersionIsRejected() {
    ManifestException e =
        assertRejected(
            goldenWith(
                "\"status\": \"PLANNED\"", "\"status\": \"PLANNED\",\n      \"colour\": \"blue\""),
            ManifestIssueCodes.UNKNOWN_MEMBER,
            SCHEMA);
    assertThat(e.issues().issues().get(0).location().path()).isEqualTo("$.seasons[2].colour");
  }

  /** Regression (Prompt 11 audit): such names used to throw IllegalArgumentException. */
  @ParameterizedTest
  @ValueSource(strings = {"a-b", "1x", "", "_x", "x.y", "\\u005d"})
  void unknownMembersWithAnyNameAreReportedNotThrown(String name) {
    ManifestException e =
        assertRejected(
            goldenWith(
                "\"status\": \"PLANNED\"", "\"status\": \"PLANNED\",\n      \"" + name + "\": 1"),
            ManifestIssueCodes.UNKNOWN_MEMBER,
            SCHEMA);
    assertThat(e.issues().issues().get(0).location().path()).startsWith("$.seasons[2][");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "2026-01-01T00:00:00+01:00",
        "2026-01-01T00:00:00z",
        "2026-02-30T00:00:00Z",
        "2026-01-01 00:00:00Z",
        "20260101T000000Z",
        "+12026-01-01T00:00:00Z",
      })
  void invalidTimestamps(String timestamp) {
    assertRejected(
        goldenWith(
            "\"createdAt\": \"2026-01-01T00:00:00Z\"", "\"createdAt\": \"" + timestamp + "\""),
        ManifestIssueCodes.INVALID_VALUE,
        SCHEMA);
  }

  @Test
  void invalidScalarValues() {
    assertRejected(
        goldenWith("\"revision\": 42", "\"revision\": -1"),
        ManifestIssueCodes.INVALID_VALUE,
        SCHEMA);
    assertRejected(
        goldenWith("\"status\": \"PLANNED\"", "\"status\": \"planned\""),
        ManifestIssueCodes.INVALID_VALUE,
        SCHEMA);
    assertRejected(
        goldenWith("\"y\": 72", "\"y\": 3000000000"), ManifestIssueCodes.INVALID_VALUE, SCHEMA);
    assertRejected(
        goldenWith("\"INVENTORY\",", "\"INVENTORY\",\n        \"INVENTORY\","),
        ManifestIssueCodes.INVALID_VALUE,
        SCHEMA);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "../outside.idx",
        "/etc/passwd",
        "indexes\\\\x.idx",
        "Indexes/x.idx",
        "indexes//x.idx",
        "c:/x.idx"
      })
  void unsafePathsAreRejected(String path) {
    assertRejected(
        goldenWith("\"indexes/overworld.idx\"", "\"" + path + "\""),
        UnsafeSidecarPathException.CODE,
        SCHEMA);
  }

  @Test
  void backupsMustLiveUnderBackups() {
    assertRejected(
        goldenWith("\"backups/2026-01-01-genesis/world.tar\"", "\"reports/world.tar\""),
        ManifestIssueCodes.INVALID_VALUE,
        SCHEMA);
  }

  // --- referential ---------------------------------------------------------------------------

  @Test
  void duplicateIdsAreReferentialErrors() {
    assertRejected(
        goldenWith("\"id\": \"" + SEASON_2 + "\"", "\"id\": \"" + SEASON_1 + "\""),
        IssueCode.of("archive.season.duplicate_id"),
        REFERENTIAL);
  }

  @Test
  void invalidActiveSeasonReferences() {
    assertRejected(
        goldenWith(
            "\"activeSeason\": \"" + SEASON_2 + "\"",
            "\"activeSeason\": \"5ea50000-0000-4000-8000-000000000099\""),
        IssueCode.of("archive.active_season.not_found"),
        REFERENTIAL);
    assertRejected(
        goldenWith("\"activeSeason\": \"" + SEASON_2 + "\"", "\"activeSeason\": null"),
        IssueCode.of("archive.active_season.unreferenced"),
        REFERENTIAL);
    assertRejected(
        goldenWith(
            "\"activeSeason\": \"" + SEASON_2 + "\"", "\"activeSeason\": \"" + SEASON_1 + "\""),
        IssueCode.of("archive.active_season.not_active"),
        REFERENTIAL);
  }

  @Test
  void duplicatePathsAreReferentialErrors() {
    assertRejected(
        goldenWith("\"indexes/the_nether.idx\"", "\"indexes/overworld.idx\""),
        ManifestIssueCodes.DUPLICATE_PATH,
        REFERENTIAL);
  }

  // --- domain --------------------------------------------------------------------------------

  @Test
  void domainValueErrorsKeepDomainCodes() {
    assertRejected(
        goldenWith("\"minecraft:the_end\"", "\"Minecraft:The_End\""),
        IssueCode.of("domain.dimension.malformed"),
        DOMAIN);
    assertRejected(
        goldenWith("\"name\": \"Next\"", "\"name\": \" Next\""),
        IssueCode.of("domain.display_name.invalid"),
        DOMAIN);
    assertRejected(
        goldenWith("\"sha256:e3b0", "\"SHA256:e3b0"),
        IssueCode.of("domain.checksum.malformed"),
        DOMAIN);
    assertRejected(
        goldenWith(
            "\"id\": \"" + SEASON_1 + "\"", "\"id\": \"00000000-0000-0000-0000-000000000000\""),
        IssueCode.of("domain.identifier.nil"),
        DOMAIN);
    assertRejected(
        goldenWith("\"x\": 1048600", "\"x\": 99999999999"),
        IssueCode.of("domain.coordinate.out_of_range"),
        DOMAIN);
  }

  // --- aggregate behaviour -------------------------------------------------------------------

  @Test
  void everyProblemIsReportedInDeterministicOrder() {
    String text =
        new String(golden(), StandardCharsets.UTF_8)
            .replace("\"revision\": 42", "\"revision\": -1")
            .replace("\"name\": \"Next\"", "\"name\": \"\"")
            .replace("\"minecraft:the_end\"", "\"END\"");
    ManifestException e = rejected(text.getBytes(StandardCharsets.UTF_8));
    assertThat(e.issues().withSeverity(Severity.ERROR)).hasSizeGreaterThanOrEqualTo(3);
    assertThat(e.kinds()).contains(SCHEMA, DOMAIN);
    assertThat(e.primaryKind()).isEqualTo(SCHEMA);
    ManifestException again = rejected(text.getBytes(StandardCharsets.UTF_8));
    assertThat(again.issues()).isEqualTo(e.issues());
  }

  @Test
  void encodeRefusesInvalidDocuments() {
    ManifestDocument full = ManifestFixtures.fullDocument();
    ManifestDocument noBackupPath =
        new ManifestDocument(
            full.schemaVersion(),
            full.archiveId(),
            full.createdAt(),
            full.revision(),
            full.state(),
            new TreeMap<>(),
            full.files(),
            full.features(),
            full.preserved());
    assertEncodeRejected(noBackupPath, ManifestIssueCodes.BACKUP_PATH_MISSING);

    ManifestDocument duplicateFile =
        full.withFile(
            new FileReference(
                "report",
                null,
                SidecarPath.of("backups/2026-01-01-genesis/world.tar"),
                ManifestFixtures.CHECKSUM_A,
                1));
    assertEncodeRejected(duplicateFile, ManifestIssueCodes.DUPLICATE_PATH);

    ManifestDocument manifestSelfReference =
        full.withFile(
            new FileReference(
                "report", null, SidecarPath.of("manifest.json"), ManifestFixtures.CHECKSUM_A, 1));
    assertEncodeRejected(manifestSelfReference, ManifestIssueCodes.INVALID_VALUE);

    ManifestDocument farFuture =
        ManifestDocument.create(
            full.archiveId(), java.time.Instant.parse("+10000-01-01T00:00:00Z"));
    assertEncodeRejected(farFuture, ManifestIssueCodes.INVALID_VALUE);
  }

  @Test
  void issueCodesAreStable() {
    assertThat(
            List.of(
                ManifestIssueCodes.NOT_A_MANIFEST,
                ManifestIssueCodes.MISSING_MEMBER,
                ManifestIssueCodes.UNKNOWN_MEMBER,
                ManifestIssueCodes.WRONG_TYPE,
                ManifestIssueCodes.INVALID_VALUE,
                ManifestIssueCodes.UNSUPPORTED_VERSION,
                ManifestIssueCodes.NEWER_MINOR_VERSION,
                ManifestIssueCodes.DUPLICATE_PATH,
                ManifestIssueCodes.BACKUP_PATH_MISSING,
                ManifestIssueCodes.BACKUP_PATH_UNKNOWN))
        .extracting(IssueCode::value)
        .containsExactly(
            "manifest.schema.not_a_manifest",
            "manifest.schema.missing_member",
            "manifest.schema.unknown_member",
            "manifest.schema.wrong_type",
            "manifest.schema.invalid_value",
            "manifest.version.unsupported",
            "manifest.version.newer_minor",
            "manifest.reference.duplicate_path",
            "manifest.reference.backup_path_missing",
            "manifest.reference.backup_path_unknown");
  }
}
