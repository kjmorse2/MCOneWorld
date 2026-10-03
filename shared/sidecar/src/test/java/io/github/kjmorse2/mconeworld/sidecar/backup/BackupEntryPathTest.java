// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.backup;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kjmorse2.mconeworld.sidecar.storage.BackupEntryPath;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Real world names are kept verbatim; unsafe or ambiguous paths are refused. */
class BackupEntryPathTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "world/level.dat",
        "world/datapacks/afk display v1.1.15 (MC 1.21-1.21.11).zip",
        "world/dimensions/minecraft/overworld/data/DistantHorizons.sqlite",
        "world/Frühling 🌲/x",
        "sidecar/chunk-index/segments/seg-0000000000000001.dat",
        "world/a:b"
      })
  void realNamesAreValid(String path) {
    assertThat(BackupEntryPath.isValid(path)).isTrue();
    assertThat(BackupEntryPath.of(path).value()).isEqualTo(path);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "world",
        "other/level.dat",
        "/world/level.dat",
        "world/",
        "world//level.dat",
        "world/./level.dat",
        "world/../level.dat",
        "world/a\\b",
        "world/a\u0000b",
        "world/a\nb",
        "world/a\u007fb",
        "world/\ud800"
      })
  void unsafePathsAreRefused(String path) {
    assertThat(BackupEntryPath.isValid(path)).isFalse();
  }

  @Test
  void lengthsAreBounded() {
    assertThat(BackupEntryPath.isValid("world/" + "x".repeat(255))).isTrue();
    assertThat(BackupEntryPath.isValid("world/" + "x".repeat(256))).isFalse();
    assertThat(BackupEntryPath.isValid("world/" + "a/".repeat(62) + "a")).isTrue();
    assertThat(BackupEntryPath.isValid("world/" + "a/".repeat(63) + "a")).isFalse();
  }

  @Test
  void orderIsCodePointOrder() {
    // U+FF61 sorts before U+1F332 by code point, but after it in UTF-16 order.
    BackupEntryPath bmp = BackupEntryPath.of("world/｡");
    BackupEntryPath astral = BackupEntryPath.of("world/🌲");
    assertThat(bmp).isLessThan(astral);
    assertThat(List.of(astral, bmp).stream().sorted().toList()).containsExactly(bmp, astral);
    assertThat(BackupEntryPath.of("world/a/b").area()).isEqualTo("world");
    assertThat(BackupEntryPath.of("world/a/b").relative()).isEqualTo("a/b");
  }
}
