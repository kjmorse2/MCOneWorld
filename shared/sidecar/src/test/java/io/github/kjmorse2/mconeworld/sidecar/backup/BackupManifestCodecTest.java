// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import io.github.kjmorse2.mconeworld.fixtures.BackupFixtures;
import io.github.kjmorse2.mconeworld.fixtures.ManifestFixtures;
import io.github.kjmorse2.mconeworld.fixtures.Mutations;
import java.nio.charset.StandardCharsets;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.statistics.Statistics;
import org.junit.jupiter.api.Test;

/** Golden bytes, strict reading, the version policy, and corrupted input. */
class BackupManifestCodecTest {
  private static final BackupManifestCodec CODEC = new BackupManifestCodec();
  private static final byte[] FULL = ManifestFixtures.resource(BackupFixtures.GOLDEN_FULL);
  private static final byte[] ARCHIVE = ManifestFixtures.resource(BackupFixtures.GOLDEN_ARCHIVE);

  private static String full() {
    return new String(FULL, StandardCharsets.UTF_8);
  }

  private static void rejected(String text, IssueCode code) {
    assertThatThrownBy(() -> CODEC.decode(text.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOfSatisfying(
            BackupFormatException.class, e -> assertThat(e.code()).isEqualTo(code));
  }

  @Test
  void theGoldensEncodeAndDecodeExactly() throws Exception {
    assertThat(CODEC.encode(BackupFixtures.full())).isEqualTo(FULL);
    assertThat(CODEC.encode(BackupFixtures.endArchive())).isEqualTo(ARCHIVE);
    assertThat(CODEC.decode(FULL.clone())).isEqualTo(BackupFixtures.full());
    assertThat(CODEC.decode(ARCHIVE.clone())).isEqualTo(BackupFixtures.endArchive());
    assertThat(BackupFixtures.full().totalBytes()).isEqualTo(38_155);
  }

  @Test
  void version10IsStrictNewerMinorsIgnoreUnknownMembersAndOtherMajorsAreRefused() throws Exception {
    String extra =
        full().replace("\"dimension\": null,", "\"dimension\": null,\n  \"colour\": \"blue\",");
    rejected(extra, BackupIssueCodes.INVALID);
    BackupManifest newer =
        CODEC.decode(
            extra
                .replace("\"version\": \"1.0\"", "\"version\": \"1.3\"")
                .getBytes(StandardCharsets.UTF_8));
    assertThat(newer).isEqualTo(BackupFixtures.full());
    rejected(
        full().replace("\"version\": \"1.0\"", "\"version\": \"2.0\""),
        BackupIssueCodes.UNSUPPORTED_VERSION);
    rejected(
        full().replace("\"version\": \"1.0\"", "\"version\": \"1.01\""), BackupIssueCodes.INVALID);
    rejected(
        full().replace("mconeworld-backup", "mconeworld-manifest"), BackupIssueCodes.NOT_A_BACKUP);
  }

  @Test
  void entriesMustBeOrderedUniqueValidAndAddUp() {
    rejected(
        full().replace("\"totalBytes\": 38155", "\"totalBytes\": 38156"), BackupIssueCodes.INVALID);
    rejected(
        full().replace("sidecar/chunk-index/catalog", "sidecar/zzz"), BackupIssueCodes.INVALID);
    rejected(
        full().replace("sidecar/chunk-index/catalog", "sidecar/manifest.json"),
        BackupIssueCodes.INVALID);
    rejected(
        full().replace("sidecar/chunk-index/catalog", "sidecar/../catalog"),
        BackupIssueCodes.INVALID);
    rejected(full().replace("\"sizeBytes\": 512", "\"sizeBytes\": -512"), BackupIssueCodes.INVALID);
    rejected(full().replace("sha256:00000000", "md5:00000000"), BackupIssueCodes.INVALID);
    rejected(
        full().replace("\"kind\": \"FULL\"", "\"kind\": \"DIMENSION_ARCHIVE\""),
        BackupIssueCodes.INVALID);
    rejected(
        full()
            .replace(
                "\"createdAt\": \"2026-01-01T00:40:00Z\"",
                "\"createdAt\": \"2026-02-30T00:00:00Z\""),
        BackupIssueCodes.INVALID);
    rejected("[]", BackupIssueCodes.NOT_A_BACKUP);
    rejected("{", BackupIssueCodes.INVALID);
  }

  @Property(tries = 2000)
  void mutantsDecodeOrFailWithACodedException(@ForAll long seed) {
    try {
      CODEC.decode(Mutations.mutate(FULL, seed));
      Statistics.label("backup").collect("accepted");
    } catch (BackupFormatException e) {
      Statistics.label("backup").collect(e.code().value());
    }
  }
}
