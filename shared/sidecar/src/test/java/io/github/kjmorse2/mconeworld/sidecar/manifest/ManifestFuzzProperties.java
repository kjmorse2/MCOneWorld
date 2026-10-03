// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kjmorse2.mconeworld.fixtures.ManifestFixtures;
import io.github.kjmorse2.mconeworld.fixtures.Mutations;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.statistics.Statistics;

/**
 * Corrupted manifests (root AGENTS.md §20: sidecars are untrusted input). Every mutant of the
 * golden manifest either decodes, and then re-encodes to a manifest that decodes to the same value,
 * or fails with {@link ManifestException}. Nothing else may escape: no runtime exception, no error,
 * from either entry point.
 */
class ManifestFuzzProperties {
  private static final byte[] GOLDEN = ManifestFixtures.resource(ManifestFixtures.GOLDEN_FULL);
  private static final ManifestCodec CODEC = new ManifestCodec();

  @Property(tries = 3000)
  void mutantsDecodeOrFailWithAManifestException(@ForAll long seed) throws IOException {
    byte[] mutant = Mutations.mutate(GOLDEN, seed);
    try {
      DecodedManifest decoded = CODEC.decode(mutant);
      byte[] again = CODEC.encode(decoded.document());
      assertThat(CODEC.decode(again).document()).isEqualTo(decoded.document());
      Statistics.label("manifest").collect("accepted");
    } catch (ManifestException e) {
      Statistics.label("manifest").collect("rejected");
    }
    try {
      CODEC.decode(new ByteArrayInputStream(mutant));
    } catch (ManifestException e) {
      // expected for most mutants
    }
  }
}
