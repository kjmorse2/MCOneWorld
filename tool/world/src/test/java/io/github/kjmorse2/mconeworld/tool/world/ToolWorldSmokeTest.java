// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.world;

import io.github.kjmorse2.mconeworld.fixtures.ModuleContract;
import org.junit.jupiter.api.Test;

/** Proves tool:world is null-marked and compiled for Java 25 (ADR 0009). */
class ToolWorldSmokeTest {

  @Test
  void rootPackageSatisfiesTheToolContract() {
    ModuleContract.assertPackageContract(
        "io.github.kjmorse2.mconeworld.tool.world", ModuleContract.JAVA_25_CLASS_FILE_MAJOR);
  }
}
