// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ModuleContractTest {

  @Test
  void readsMajorVersionFromClassFileHeader() {
    byte[] header = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0, 65};
    assertThat(ModuleContract.classFileMajorVersion(header)).isEqualTo(65);
  }

  @Test
  void rejectsBytesThatAreNotAClassFile() {
    assertThatThrownBy(
            () -> ModuleContract.classFileMajorVersion(new byte[] {1, 2, 3, 4, 0, 0, 0, 65}))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("magic");
    assertThatThrownBy(() -> ModuleContract.classFileMajorVersion(new byte[3]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void fixturesPackageSatisfiesTheSharedContract() {
    ModuleContract.assertSharedPackageContract("io.github.kjmorse2.mconeworld.fixtures");
    ModuleContract.assertSharedPackageContract("io.github.kjmorse2.mconeworld.fixtures.index");
  }

  @Test
  void packageWithoutPackageInfoFailsTheContract() {
    assertThatThrownBy(
            () ->
                ModuleContract.assertSharedPackageContract("io.github.kjmorse2.mconeworld.nosuch"))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("no package-info");
  }
}
