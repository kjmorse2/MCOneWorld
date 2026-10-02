// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.index;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kjmorse2.mconeworld.fixtures.index.ChunkIndexFixtures;
import io.github.kjmorse2.mconeworld.fixtures.index.ChunkRecordText;
import io.github.kjmorse2.mconeworld.fixtures.index.ReferenceChunkIndex;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The format is pinned byte for byte: today's writer reproduces the golden v1 files exactly,
 * today's reader reads them to the golden export, and every file matches its {@code .meta} (test
 * plan M1). A change here is a format change: it needs a new minor or major version and a migration
 * story.
 */
class ChunkIndexGoldenTest {
  @TempDir Path temp;

  private static byte[] resource(String path) throws IOException {
    try (InputStream in = ChunkIndexFixtures.class.getResourceAsStream(path)) {
      if (in == null) {
        throw new IOException("missing golden resource " + path);
      }
      return in.readAllBytes();
    }
  }

  @Test
  void theWriterReproducesTheGoldenFilesByteForByte() throws Exception {
    Path root = temp.resolve("sidecar");
    ChunkIndexFixtures.writeGoldenIndex(root);
    for (String file : ChunkIndexFixtures.GOLDEN_FILES) {
      assertThat(Files.readAllBytes(root.resolve("chunk-index").resolve(file)))
          .as(file)
          .isEqualTo(resource(ChunkIndexFixtures.GOLDEN_DIRECTORY + file));
    }
    try (var files = Files.walk(root.resolve("chunk-index"))) {
      assertThat(files.filter(Files::isRegularFile).count())
          .as("no other files")
          .isEqualTo(ChunkIndexFixtures.GOLDEN_FILES.size());
    }
  }

  @Test
  void theReaderReadsTheGoldenFilesToTheGoldenExport() throws Exception {
    Path root = temp.resolve("copy");
    Files.createDirectories(root.resolve("chunk-index/segments"));
    for (String file : ChunkIndexFixtures.GOLDEN_FILES) {
      Files.write(
          root.resolve("chunk-index").resolve(file),
          resource(ChunkIndexFixtures.GOLDEN_DIRECTORY + file));
    }
    ByteArrayOutputStream export = new ByteArrayOutputStream();
    try (ChunkIndexSnapshot reader = IndexStores.readOnly(root).openReader()) {
      ChunkIndexExport.write(reader, export);
      ReferenceChunkIndex reference = new ReferenceChunkIndex();
      ChunkIndexFixtures.applyHistory(reference, false);
      try (var expected = reference.reader()) {
        assertThat(ChunkRecordText.digest(reader)).isEqualTo(ChunkRecordText.digest(expected));
      }
      assertThat(reader.state().generation()).isEqualTo(1);
      assertThat(reader.state().loggedMutations()).isEqualTo(7);
    }
    assertThat(new String(export.toByteArray(), StandardCharsets.UTF_8))
        .isEqualTo(new String(resource(ChunkIndexFixtures.GOLDEN_EXPORT), StandardCharsets.UTF_8));
    assertThat(IndexStores.readOnly(root).verify().isValid()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "catalog",
        "segments/seg-0000000000000001.dat",
        "deltas-0000000000000001.log",
        "export.jsonl"
      })
  void everyGoldenFileMatchesItsMeta(String file) throws Exception {
    Properties meta = new Properties();
    meta.load(
        new java.io.ByteArrayInputStream(
            resource(ChunkIndexFixtures.GOLDEN_DIRECTORY + file + ".meta")));
    byte[] bytes = resource(ChunkIndexFixtures.GOLDEN_DIRECTORY + file);
    assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
        .isEqualTo(meta.getProperty("sha256"));
    assertThat(meta.getProperty("version")).isEqualTo("1.0");
    assertThat(meta.getProperty("format"))
        .isIn(List.of("mconeworld-chunk-index", "mconeworld-chunk-index-export"));
  }
}
