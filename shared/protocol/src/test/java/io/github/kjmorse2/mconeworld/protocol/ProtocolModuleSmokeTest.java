// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.protocol;

import io.github.kjmorse2.mconeworld.fixtures.ModuleContract;
import org.junit.jupiter.api.Test;

/** Proves shared:protocol is null-marked and consumable by the Java 21 offline tool (ADR 0002). */
class ProtocolModuleSmokeTest {

  @Test
  void rootPackageSatisfiesTheSharedContract() {
    ModuleContract.assertSharedPackageContract("io.github.kjmorse2.mconeworld.protocol");
  }

  @Test
  void codecPackageSatisfiesTheSharedContract() {
    ModuleContract.assertSharedPackageContract("io.github.kjmorse2.mconeworld.protocol.codec");
  }
}
