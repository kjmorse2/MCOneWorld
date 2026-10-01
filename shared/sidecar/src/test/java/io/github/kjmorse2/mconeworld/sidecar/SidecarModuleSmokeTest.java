// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar;

import io.github.kjmorse2.mconeworld.fixtures.ModuleContract;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Proves shared:sidecar is null-marked and consumable by the Java 21 offline tool (ADR 0002). */
class SidecarModuleSmokeTest {

  @Test
  void rootPackageSatisfiesTheSharedContract() {
    ModuleContract.assertSharedPackageContract("io.github.kjmorse2.mconeworld.sidecar");
  }

  @ParameterizedTest
  @ValueSource(strings = {"manifest", "storage", "migration", "operation", "index"})
  void apiPackagesSatisfyTheSharedContract(String sub) {
    ModuleContract.assertSharedPackageContract("io.github.kjmorse2.mconeworld.sidecar." + sub);
  }
}
