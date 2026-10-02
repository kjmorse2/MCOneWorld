// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.protocol.codec;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kjmorse2.mconeworld.fixtures.protocol.ProtocolFixtures;
import io.github.kjmorse2.mconeworld.fixtures.protocol.ProtocolFixtures.Context;
import io.github.kjmorse2.mconeworld.fixtures.protocol.ProtocolFixtures.Hello;
import io.github.kjmorse2.mconeworld.fixtures.protocol.ProtocolFixtures.Vector;
import io.github.kjmorse2.mconeworld.protocol.ProtocolException;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The production codec against the pinned bytes: encoding each golden value gives exactly its file,
 * decoding the file gives back the value, and the {@code .meta} checksum matches. A failure here
 * means the wire format changed, which needs a new protocol version.
 */
class ProtocolGoldenTest {

  static Stream<Vector> vectors() {
    return ProtocolFixtures.vectors().stream();
  }

  @ParameterizedTest
  @MethodSource("vectors")
  void encodingMatchesTheGoldenBytes(Vector vector) {
    byte[] golden = ProtocolFixtures.goldenBytes(vector);
    assertThat(ProtocolFixtures.encode(vector)).isEqualTo(golden);
    assertThat(ProtocolFixtures.goldenMeta(vector))
        .isEqualTo(ProtocolFixtures.meta(vector, golden));
  }

  @ParameterizedTest
  @MethodSource("vectors")
  void decodingTheGoldenBytesGivesTheValue(Vector vector) throws ProtocolException {
    byte[] golden = ProtocolFixtures.goldenBytes(vector);
    switch (vector) {
      case Hello h -> assertThat(ProtocolCodec.decodeHello(golden)).isEqualTo(h.message());
      case Context c -> assertThat(ProtocolCodec.decodeContext(golden)).isEqualTo(c.message());
    }
  }
}
