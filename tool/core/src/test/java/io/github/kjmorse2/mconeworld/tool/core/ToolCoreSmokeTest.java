// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core;

import io.github.kjmorse2.mconeworld.fixtures.ModuleContract;
import org.junit.jupiter.api.Test;

/** Proves tool:core is null-marked and compiled for Java 25 (ADR 0009). */
class ToolCoreSmokeTest {

  @Test
  void rootPackageSatisfiesTheToolContract() {
    ModuleContract.assertPackageContract(
        "io.github.kjmorse2.mconeworld.tool.core", ModuleContract.JAVA_25_CLASS_FILE_MAJOR);
  }
}
