// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.manifest;

import static io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestTestSupport.CODEC;
import static io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestTestSupport.golden;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.fixtures.ManifestFixtures;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Random;
import java.util.TimeZone;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** Golden bytes, round trips, and determinism of the canonical encoding. */
class ManifestGoldenTest {

  @Test
  void encodingTheFullFixtureReproducesTheGoldenBytesAndChecksum() throws Exception {
    byte[] encoded = CODEC.encode(ManifestFixtures.fullDocument());
    assertThat(encoded).isEqualTo(golden());

    Properties meta = new Properties();
    meta.load(
        new java.io.ByteArrayInputStream(
            ManifestFixtures.resource(ManifestFixtures.GOLDEN_FULL_V1 + ".meta")));
    assertThat(meta.getProperty("schemaVersion")).isEqualTo(ManifestCodec.CURRENT.toString());
    assertThat(meta.getProperty("format")).isEqualTo(ManifestCodec.FORMAT);
    String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
    assertThat(sha).isEqualTo(meta.getProperty("sha256"));
  }

  @Test
  void publishedFormatExampleIsExactlyTheGolden() throws Exception {
    // The Gradle test task runs in the project directory (shared/sidecar).
    Path example = Path.of("../../docs/formats/examples/manifest-v1-example.json");
    assertThat(Files.readAllBytes(example)).isEqualTo(golden());
  }

  @Test
  void decodeOfTheGoldenIsTheFixtureAndCanonical() throws Exception {
    DecodedManifest decoded = CODEC.decode(golden());
    assertThat(decoded.document()).isEqualTo(ManifestFixtures.fullDocument());
    assertThat(decoded.canonical()).isTrue();
    assertThat(decoded.warnings().issues()).isEmpty();
  }

  @Test
  void decodeEncodeDecodeIsStable() throws Exception {
    ManifestDocument once = CODEC.decode(golden()).document();
    byte[] again = CODEC.encode(once);
    assertThat(again).isEqualTo(golden());
    assertThat(CODEC.decode(again).document()).isEqualTo(once);
  }

  @Test
  void outputIsIndependentOfInsertionOrder() throws Exception {
    ManifestDocument reference = ManifestFixtures.fullDocument();
    Random random = new Random(7); // seeded, so the test is deterministic
    for (int round = 0; round < 20; round++) {
      List<FileReference> files = new ArrayList<>(reference.files());
      Collections.shuffle(files, random);
      List<DimensionKey> dims =
          new ArrayList<>(reference.state().seasons().get(1).origins().keySet());
      Collections.shuffle(dims, random);
      TreeMap<DimensionKey, io.github.kjmorse2.mconeworld.domain.coordinate.DimensionOrigin>
          origins = new TreeMap<>();
      dims.forEach(d -> origins.put(d, reference.state().seasons().get(1).origins().get(d)));
      ManifestDocument shuffled =
          new ManifestDocument(
              reference.schemaVersion(),
              reference.archiveId(),
              reference.createdAt(),
              reference.revision(),
              reference.state(),
              reference.backupPaths(),
              files,
              reference.features(),
              reference.preserved());
      assertThat(CODEC.encode(shuffled)).isEqualTo(golden());
    }
  }

  @Test
  void repeatedEncodingIsByteIdentical() throws Exception {
    ManifestDocument doc = ManifestFixtures.fullDocument();
    byte[] first = CODEC.encode(doc);
    for (int i = 0; i < 10; i++) {
      assertThat(CODEC.encode(doc)).isEqualTo(first);
    }
  }

  @Test
  void encodingIsIndependentOfLocaleAndTimeZone() throws Exception {
    Locale locale = Locale.getDefault();
    TimeZone zone = TimeZone.getDefault();
    try {
      for (String tag :
          new String[] {"tr-TR", "ar-SA-u-nu-arab", "hi-IN-u-nu-deva", "th-TH-u-ca-buddhist"}) {
        Locale.setDefault(Locale.forLanguageTag(tag));
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kathmandu"));
        assertThat(CODEC.encode(ManifestFixtures.fullDocument())).as(tag).isEqualTo(golden());
        assertThat(CODEC.decode(golden()).document()).isEqualTo(ManifestFixtures.fullDocument());
      }
    } finally {
      Locale.setDefault(locale);
      TimeZone.setDefault(zone);
    }
  }

  @Test
  void validButNonCanonicalInputIsAcceptedAndReportedAsSuch() throws Exception {
    // Compact whitespace and CRLF: same content, different bytes.
    String compact =
        new String(golden(), StandardCharsets.UTF_8).replace("\n  ", "\r\n ").replace(": ", ":");
    DecodedManifest decoded = CODEC.decode(compact.getBytes(StandardCharsets.UTF_8));
    assertThat(decoded.canonical()).isFalse();
    assertThat(decoded.document()).isEqualTo(ManifestFixtures.fullDocument());
    assertThat(CODEC.encode(decoded.document())).isEqualTo(golden());
  }

  @Test
  void newDocumentIsEmptyAtRevisionZero() throws Exception {
    ManifestDocument doc =
        ManifestDocument.create(
            io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.archiveId(9),
            io.github.kjmorse2.mconeworld.fixtures.DomainFixtures.instant(0));
    assertThat(doc.revision()).isZero();
    assertThat(doc.nextRevision().revision()).isEqualTo(1);
    DecodedManifest decoded = CODEC.decode(CODEC.encode(doc));
    assertThat(decoded.document()).isEqualTo(doc);
    assertThat(decoded.canonical()).isTrue();
  }
}
