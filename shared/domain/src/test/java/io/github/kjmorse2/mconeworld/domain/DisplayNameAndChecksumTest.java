// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DisplayNameAndChecksumTest {

  @ParameterizedTest
  @ValueSource(strings = {"Season 1", "Spring '26", "季節", "🌲 Forest", "a"})
  void acceptsReasonableNames(String name) {
    assertThat(DisplayName.of(name).value()).isEqualTo(name);
  }

  @Test
  void rejectsInvalidNames() {
    String[] invalid = {
      "",
      " leading",
      "trailing ",
      "tab\tinside",
      "new\nline",
      "\u0000",
      "x".repeat(65),
      "bidi" + (char) 0x202E + "override", // built at runtime: no bidi char in source
      "isolate" + (char) 0x2066 + "x",
      "lone\uD800surrogate",
      "\uDC00",
      " nbsp-leading",
    };
    for (String name : invalid) {
      assertThatThrownBy(() -> DisplayName.of(name))
          .as("%s", name)
          .isInstanceOfSatisfying(
              InvalidDomainValueException.class,
              e -> assertThat(e.code()).isEqualTo(DomainErrorCode.DISPLAY_NAME_INVALID));
    }
  }

  @Test
  void lengthIsCountedInCodePoints() {
    String sixtyFourEmoji = "🌲".repeat(64); // 128 UTF-16 chars
    assertThat(DisplayName.of(sixtyFourEmoji).value()).hasSize(128);
    assertThatThrownBy(() -> DisplayName.of(sixtyFourEmoji + "🌲"))
        .isInstanceOf(InvalidDomainValueException.class);
  }

  @Test
  void namesAreNotIdentifiers() {
    // Equal names are allowed for different seasons; DisplayName has value equality only.
    assertThat(DisplayName.of("Same")).isEqualTo(DisplayName.of("Same"));
  }

  @Test
  void checksumRoundTripsCanonicalText() throws NoSuchAlgorithmException {
    byte[] digest =
        MessageDigest.getInstance("SHA-256").digest("abc".getBytes(StandardCharsets.UTF_8));
    Checksum checksum = Checksum.ofDigest(Checksum.Algorithm.SHA_256, digest);
    // Well-known SHA-256("abc") test vector (FIPS 180-2).
    assertThat(checksum)
        .hasToString("sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    assertThat(Checksum.parse(checksum.toString())).isEqualTo(checksum);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "sha256",
        "sha256:",
        "md5:d41d8cd98f00b204e9800998ecf8427e",
        "SHA256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        "sha256:BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD",
        "sha256:ba7816bf",
        "sha256:ga7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
      })
  void rejectsMalformedChecksums(String text) {
    assertThatThrownBy(() -> Checksum.parse(text))
        .isInstanceOfSatisfying(
            InvalidDomainValueException.class,
            e -> assertThat(e.code()).isEqualTo(DomainErrorCode.CHECKSUM_MALFORMED));
  }
}
