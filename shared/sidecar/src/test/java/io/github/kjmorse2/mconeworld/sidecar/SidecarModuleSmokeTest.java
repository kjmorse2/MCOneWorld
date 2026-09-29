// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar;

import io.github.kjmorse2.mconeworld.fixtures.ModuleContract;
import org.junit.jupiter.api.Test;

/** Proves shared:sidecar is null-marked and consumable by the Java 21 offline tool (ADR 0002). */
class SidecarModuleSmokeTest {

  @Test
  void rootPackageSatisfiesTheSharedContract() {
    ModuleContract.assertSharedPackageContract("io.github.kjmorse2.mconeworld.sidecar");
  }
}
