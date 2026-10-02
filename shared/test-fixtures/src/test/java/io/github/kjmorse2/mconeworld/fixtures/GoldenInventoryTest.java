// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.fixtures;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every committed golden file has a sibling {@code <file>.meta} naming its format and version and
 * pinning its SHA-256 (shared/AGENTS.md, "Golden files include format version and expected
 * checksum"), and every {@code .meta} describes an existing file. Readers of each format are tested
 * against these files by that format's golden test; this test only guards the inventory, so a new
 * fixture cannot land without metadata. Added by the Prompt 11 audit.
 */
class GoldenInventoryTest {
  private static final Path ROOT =
      Path.of("src/main/resources/io/github/kjmorse2/mconeworld/fixtures");

  @Test
  void everyGoldenFileHasMatchingMetadata() throws Exception {
    List<Path> files = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(ROOT)) {
      walk.filter(Files::isRegularFile).sorted().forEach(files::add);
    }
    assertThat(files).isNotEmpty();
    int data = 0;
    for (Path file : files) {
      String name = file.getFileName().toString();
      if (name.endsWith(".meta")) {
        assertThat(file.resolveSibling(name.substring(0, name.length() - 5)))
            .as("data file for " + file)
            .exists();
        continue;
      }
      data++;
      Path metaFile = file.resolveSibling(name + ".meta");
      assertThat(metaFile).as("metadata for " + file).exists();
      Properties meta = new Properties();
      meta.load(new ByteArrayInputStream(Files.readAllBytes(metaFile)));
      assertThat(meta.getProperty("format")).as("format in " + metaFile).startsWith("mconeworld-");
      assertThat(meta.getProperty("version", meta.getProperty("schemaVersion")))
          .as("version in " + metaFile)
          .isNotBlank();
      String sha256 =
          HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
      assertThat(meta.getProperty("sha256")).as("sha256 in " + metaFile).isEqualTo(sha256);
    }
    // 4 chunk-index, 1 manifest, 2 operation, 7 protocol; update when a fixture is added.
    assertThat(data).as("golden data files").isEqualTo(14);
  }
}
