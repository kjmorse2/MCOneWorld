// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import io.github.kjmorse2.mconeworld.domain.validation.Severity;
import io.github.kjmorse2.mconeworld.fixtures.ManifestFixtures;
import java.nio.charset.StandardCharsets;

/** Helpers shared by the manifest tests. Golden bytes are copied before any mutation. */
final class ManifestTestSupport {
  static final ManifestCodec CODEC = new ManifestCodec();

  private ManifestTestSupport() {}

  static byte[] golden() {
    return ManifestFixtures.resource(ManifestFixtures.GOLDEN_FULL).clone();
  }

  static String goldenText() {
    return new String(golden(), StandardCharsets.UTF_8);
  }

  /** The golden text with exactly one occurrence of {@code from} replaced; fails if absent. */
  static byte[] goldenWith(String from, String to) {
    String text = goldenText();
    int at = text.indexOf(from);
    assertThat(at).as("fixture contains %s", from).isNotNegative();
    return (text.substring(0, at) + to + text.substring(at + from.length()))
        .getBytes(StandardCharsets.UTF_8);
  }

  static ManifestException rejected(byte[] bytes) {
    try {
      CODEC.decode(bytes);
    } catch (ManifestException e) {
      return e;
    }
    throw new AssertionError("Expected the manifest to be rejected");
  }

  /** Asserts rejection with an error of {@code code} whose kind is {@code kind}. */
  static ManifestException assertRejected(byte[] bytes, IssueCode code, ManifestProblemKind kind) {
    ManifestException e = rejected(bytes);
    assertThat(e.issues().withSeverity(Severity.ERROR)).extracting(i -> i.code()).contains(code);
    assertThat(e.kinds()).contains(kind);
    assertThat(ManifestProblemKind.of(code)).isEqualTo(kind);
    return e;
  }

  static void assertEncodeRejected(ManifestDocument document, IssueCode code) {
    assertThatThrownBy(() -> CODEC.encode(document))
        .isInstanceOfSatisfying(
            ManifestException.class, e -> assertThat(e.issues().has(code)).isTrue());
  }
}
