// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.manifest;

import static io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestTestSupport.CODEC;
import static io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestTestSupport.golden;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kjmorse2.mconeworld.fixtures.ManifestFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Read-only decoding never touches the file or its directory, whether it succeeds or fails. */
class ManifestFilesTest {

  /** Content as hex, so record equality compares bytes rather than array identity. */
  private record Snapshot(String contentHex, FileTime modified, List<Path> listing) {}

  private static Snapshot snapshot(Path file) throws Exception {
    try (Stream<Path> listing = Files.list(file.getParent())) {
      return new Snapshot(
          HexFormat.of().formatHex(Files.readAllBytes(file)),
          Files.getLastModifiedTime(file),
          listing.sorted().toList());
    }
  }

  private static void assertUnchanged(Path file, Snapshot before) throws Exception {
    assertThat(snapshot(file)).isEqualTo(before);
  }

  @Test
  void failedDecodeLeavesTheFileAndDirectoryUntouched(@TempDir Path sidecar) throws Exception {
    Path manifest = sidecar.resolve("manifest.json");
    byte[] corrupt = Arrays.copyOf(golden(), golden().length / 2); // truncated copy
    Files.write(manifest, corrupt);
    Files.setLastModifiedTime(manifest, FileTime.fromMillis(1_000_000_000_000L));
    Snapshot before = snapshot(manifest);

    assertThatThrownBy(() -> ManifestFiles.readOnly(manifest, CODEC))
        .isInstanceOf(ManifestException.class);
    assertUnchanged(manifest, before);
  }

  @Test
  void nonCanonicalFileIsReadButNotRewritten(@TempDir Path sidecar) throws Exception {
    Path manifest = sidecar.resolve("manifest.json");
    Files.write(
        manifest,
        new String(golden(), java.nio.charset.StandardCharsets.UTF_8)
            .replace("\n", "\r\n")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    Files.setLastModifiedTime(manifest, FileTime.fromMillis(1_000_000_000_000L));
    Snapshot before = snapshot(manifest);

    DecodedManifest decoded = ManifestFiles.readOnly(manifest, CODEC);
    assertThat(decoded.canonical()).isFalse();
    assertThat(decoded.document()).isEqualTo(ManifestFixtures.fullDocument());
    assertUnchanged(manifest, before);
  }

  @Test
  void readOnlyFileCanBeDecoded(@TempDir Path sidecar) throws Exception {
    Path manifest = sidecar.resolve("manifest.json");
    Files.write(manifest, golden());
    boolean readOnly = manifest.toFile().setReadOnly();
    assertThat(readOnly).isTrue();
    assertThat(ManifestFiles.readOnly(manifest, CODEC).canonical()).isTrue();
  }
}
