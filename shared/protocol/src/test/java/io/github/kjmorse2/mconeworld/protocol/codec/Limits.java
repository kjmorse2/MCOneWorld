// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.protocol.codec;

import io.github.kjmorse2.mconeworld.domain.DimensionKey;
import io.github.kjmorse2.mconeworld.domain.coordinate.CoordinateLimits;
import io.github.kjmorse2.mconeworld.domain.coordinate.DimensionOrigin;
import io.github.kjmorse2.mconeworld.protocol.Capability;
import io.github.kjmorse2.mconeworld.protocol.Extension;
import io.github.kjmorse2.mconeworld.protocol.Extensions;
import io.github.kjmorse2.mconeworld.protocol.ProtocolLimits;
import java.util.ArrayList;
import java.util.List;

/** The largest values each field allows, for the size tests. */
final class Limits {
  private Limits() {}

  /** {@code count} distinct capabilities of the maximum identifier length. */
  static List<Capability> capabilities(int count) {
    List<Capability> out = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      out.add(new Capability(longIdentifier(i)));
    }
    return out;
  }

  /** The maximum number of extensions, each with the longest key and value. */
  static Extensions largestExtensions() {
    List<Extension> out = new ArrayList<>();
    for (int i = 0; i < ProtocolLimits.MAX_EXTENSIONS; i++) {
      out.add(new Extension(longIdentifier(i), new byte[ProtocolLimits.MAX_EXTENSION_BYTES]));
    }
    return Extensions.of(out);
  }

  /** The maximum number of origins, each with the longest dimension key. */
  static List<DimensionOrigin> largestOrigins() {
    List<DimensionOrigin> out = new ArrayList<>();
    for (int i = 0; i < ProtocolLimits.MAX_ORIGINS; i++) {
      String key = "d:" + "x".repeat(DimensionKey.MAX_LENGTH - 5) + String.format("%03d", i);
      out.add(
          new DimensionOrigin(
              new DimensionKey(key),
              CoordinateLimits.MIN_BLOCK_XZ,
              i,
              CoordinateLimits.MAX_BLOCK_XZ));
    }
    return out;
  }

  private static String longIdentifier(int i) {
    return "a:" + "x".repeat(ProtocolLimits.MAX_IDENTIFIER_BYTES - 4) + String.format("%02d", i);
  }
}
