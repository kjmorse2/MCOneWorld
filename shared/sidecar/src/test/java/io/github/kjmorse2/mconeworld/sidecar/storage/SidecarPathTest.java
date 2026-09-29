// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SidecarPathTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "manifest.json",
        "indexes/overworld.mcoi",
        "backups/2026-01-01_a/world.tar.zst",
        "a/b/c",
        ".hidden",
        "reports/r-1_x.json"
      })
  void acceptsPortableRelativePaths(String path) {
    assertThat(SidecarPath.of(path).value()).isEqualTo(path);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "/etc/passwd",
        "../escape",
        "a/../../b",
        "a/./b",
        "a//b",
        "a/",
        "C:/world",
        "c:/world",
        "a\\b",
        "Indexes/x",
        "a b",
        "con",
        "nul.json",
        "backups/COM1.tar",
        "lpt9.x.y",
        "trailing.",
        "tab\tx",
        "ü",
        "~/x",
      })
  void rejectsUnsafeOrNonPortablePaths(String path) {
    assertThat(SidecarPath.isValid(path)).isFalse();
    assertThatThrownBy(() -> SidecarPath.of(path)).isInstanceOf(UnsafeSidecarPathException.class);
  }

  @Test
  void enforcesLengthAndSegmentBounds() {
    assertThat(SidecarPath.isValid("a/".repeat(15) + "a")).isTrue();
    assertThat(SidecarPath.isValid("a/".repeat(16) + "a")).isFalse();
    assertThat(SidecarPath.isValid("x".repeat(65))).isFalse();
    assertThat(SidecarPath.isValid(("x".repeat(60) + "/").repeat(4) + "x".repeat(20)))
        .isFalse(); // > 255
  }

  @Test
  void directoryContainment() {
    assertThat(SidecarPath.of("backups/a.tar").isUnder("backups")).isTrue();
    assertThat(SidecarPath.of("backupsx/a.tar").isUnder("backups")).isFalse();
    assertThat(SidecarPath.of("backups/a.tar").segments()).containsExactly("backups", "a.tar");
  }
}
