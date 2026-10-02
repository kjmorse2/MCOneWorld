// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.protocol;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * An optional, keyed field appended to a message: the way later versions add data that earlier
 * decoders skip safely. Version 1 defines none; a version 1 decoder keeps unknown extensions as
 * opaque bytes and never interprets them. Immutable: the value is copied in and out.
 */
public final class Extension implements Comparable<Extension> {
  private final String key;
  private final byte[] value;

  /**
   * @throws IllegalArgumentException if the key breaks the identifier grammar or the value is too
   *     long
   */
  public Extension(String key, byte[] value) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(value, "value");
    if (!Identifiers.isValid(key)) {
      throw new IllegalArgumentException("Invalid extension key: " + key);
    }
    if (value.length > ProtocolLimits.MAX_EXTENSION_BYTES) {
      throw new IllegalArgumentException(
          "Extension value longer than " + ProtocolLimits.MAX_EXTENSION_BYTES);
    }
    this.key = key;
    this.value = value.clone();
  }

  public String key() {
    return key;
  }

  /** A copy of the value. */
  public byte[] value() {
    return value.clone();
  }

  @Override
  public int compareTo(Extension other) {
    return key.compareTo(other.key);
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof Extension e && key.equals(e.key) && Arrays.equals(value, e.value);
  }

  @Override
  public int hashCode() {
    return 31 * key.hashCode() + Arrays.hashCode(value);
  }

  @Override
  public String toString() {
    return key + "=" + HexFormat.of().formatHex(value);
  }
}
