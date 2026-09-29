// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain;

import io.github.kjmorse2.mconeworld.fixtures.ModuleContract;
import org.junit.jupiter.api.Test;

/** Proves shared:domain is null-marked and consumable by the Java 21 offline tool (ADR 0002). */
class DomainModuleSmokeTest {

  @Test
  void rootPackageSatisfiesTheSharedContract() {
    ModuleContract.assertSharedPackageContract("io.github.kjmorse2.mconeworld.domain");
  }
}
