// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.world.nbt;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.jspecify.annotations.Nullable;

/** An immutable NBT value. The numeric ids are the wire tag ids. */
public sealed interface NbtTag {

  /** The tag id on the wire (1 to 12). */
  int id();

  /** TAG_Byte. */
  record NbtByte(byte value) implements NbtTag {
    @Override
    public int id() {
      return 1;
    }
  }

  /** TAG_Short. */
  record NbtShort(short value) implements NbtTag {
    @Override
    public int id() {
      return 2;
    }
  }

  /** TAG_Int. */
  record NbtInt(int value) implements NbtTag {
    @Override
    public int id() {
      return 3;
    }
  }

  /** TAG_Long. */
  record NbtLong(long value) implements NbtTag {
    @Override
    public int id() {
      return 4;
    }
  }

  /** TAG_Float. */
  record NbtFloat(float value) implements NbtTag {
    @Override
    public int id() {
      return 5;
    }
  }

  /** TAG_Double. */
  record NbtDouble(double value) implements NbtTag {
    @Override
    public int id() {
      return 6;
    }
  }

  /** TAG_String, decoded from modified UTF-8. */
  record NbtString(String value) implements NbtTag {
    @Override
    public int id() {
      return 8;
    }
  }

  /**
   * TAG_List.
   *
   * @param elementId the declared element tag id (0 for an empty list of unknown type)
   * @param values the elements, unmodifiable
   */
  record NbtList(int elementId, List<NbtTag> values) implements NbtTag {
    public NbtList {
      values = List.copyOf(values);
    }

    @Override
    public int id() {
      return 9;
    }
  }

  /** TAG_Compound with its members in file order, unmodifiable. */
  record NbtCompound(Map<String, NbtTag> members) implements NbtTag {
    public NbtCompound {
      members = Collections.unmodifiableMap(new LinkedHashMap<>(members));
    }

    @Override
    public int id() {
      return 10;
    }

    public @Nullable NbtTag get(String name) {
      return members.get(name);
    }

    public Optional<NbtCompound> compound(String name) {
      return members.get(name) instanceof NbtCompound c ? Optional.of(c) : Optional.empty();
    }

    public Optional<String> string(String name) {
      return members.get(name) instanceof NbtString s ? Optional.of(s.value()) : Optional.empty();
    }

    /** An int member, also accepting the narrower byte and short tags. */
    public OptionalInt intValue(String name) {
      return switch (members.get(name)) {
        case NbtInt i -> OptionalInt.of(i.value());
        case NbtShort s -> OptionalInt.of(s.value());
        case NbtByte b -> OptionalInt.of(b.value());
        case null, default -> OptionalInt.empty();
      };
    }

    /** A long member, also accepting the narrower integer tags. */
    public OptionalLong longValue(String name) {
      if (members.get(name) instanceof NbtLong l) {
        return OptionalLong.of(l.value());
      }
      OptionalInt i = intValue(name);
      return i.isPresent() ? OptionalLong.of(i.getAsInt()) : OptionalLong.empty();
    }

    public Optional<NbtList> list(String name) {
      return members.get(name) instanceof NbtList l ? Optional.of(l) : Optional.empty();
    }

    public Optional<NbtIntArray> intArray(String name) {
      return members.get(name) instanceof NbtIntArray a ? Optional.of(a) : Optional.empty();
    }
  }

  /** TAG_Byte_Array; the content is copied in and out. */
  final class NbtByteArray implements NbtTag {
    private final byte[] values;

    public NbtByteArray(byte[] values) {
      this.values = values.clone();
    }

    public byte[] values() {
      return values.clone();
    }

    public int length() {
      return values.length;
    }

    @Override
    public int id() {
      return 7;
    }

    @Override
    public boolean equals(@Nullable Object o) {
      return o instanceof NbtByteArray other && Arrays.equals(values, other.values);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(values);
    }

    @Override
    public String toString() {
      return "NbtByteArray[" + values.length + "]";
    }
  }

  /** TAG_Int_Array; the content is copied in and out. */
  final class NbtIntArray implements NbtTag {
    private final int[] values;

    public NbtIntArray(int[] values) {
      this.values = values.clone();
    }

    public int[] values() {
      return values.clone();
    }

    public int length() {
      return values.length;
    }

    @Override
    public int id() {
      return 11;
    }

    @Override
    public boolean equals(@Nullable Object o) {
      return o instanceof NbtIntArray other && Arrays.equals(values, other.values);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(values);
    }

    @Override
    public String toString() {
      return "NbtIntArray" + Arrays.toString(values);
    }
  }

  /** TAG_Long_Array; the content is copied in and out. */
  final class NbtLongArray implements NbtTag {
    private final long[] values;

    public NbtLongArray(long[] values) {
      this.values = values.clone();
    }

    public long[] values() {
      return values.clone();
    }

    public int length() {
      return values.length;
    }

    @Override
    public int id() {
      return 12;
    }

    @Override
    public boolean equals(@Nullable Object o) {
      return o instanceof NbtLongArray other && Arrays.equals(values, other.values);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(values);
    }

    @Override
    public String toString() {
      return "NbtLongArray[" + values.length + "]";
    }
  }
}
