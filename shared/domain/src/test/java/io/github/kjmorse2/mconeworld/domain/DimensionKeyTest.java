// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DimensionKeyTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "minecraft:overworld",
        "minecraft:the_nether",
        "minecraft:the_end",
        "mymod:mining/deep_caves",
        "a.b-c_d:e.f-g_h",
        "0:0",
        "mod:a/b/c",
        "mod:...x", // dots are legal characters; only whole "." or ".." segments are rejected
      })
  void acceptsValidIdentifiers(String text) {
    DimensionKey key = DimensionKey.parse(text);
    assertThat(key.namespacedId()).isEqualTo(text);
    assertThat(key).hasToString(text);
    assertThat(DimensionKey.isValid(text)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        ":",
        "overworld", // namespace is mandatory
        "minecraft:",
        ":overworld",
        "Minecraft:overworld",
        "minecraft:Overworld",
        "minecraft:over world",
        " minecraft:overworld",
        "minecraft:overworld ",
        "minecraft:a:b",
        "mine/craft:overworld", // '/' is not allowed in namespaces
        "minecraft:/overworld",
        "minecraft:overworld/",
        "minecraft:a//b",
        "minecraft:../escape",
        "minecraft:a/./b",
        "..:overworld",
        ".:overworld",
        "minecraft:é",
        "minecraft:tab\t",
        "minecraft:null\u0000",
      })
  void rejectsMalformedIdentifiers(String text) {
    assertThat(DimensionKey.isValid(text)).isFalse();
    assertThatThrownBy(() -> DimensionKey.parse(text))
        .isInstanceOfSatisfying(
            InvalidDomainValueException.class,
            e -> assertThat(e.code()).isEqualTo(DomainErrorCode.DIMENSION_MALFORMED));
  }

  @Test
  void enforcesLengthBound() {
    String atLimit = "m:" + "a".repeat(DimensionKey.MAX_LENGTH - 2);
    assertThat(DimensionKey.isValid(atLimit)).isTrue();
    assertThat(DimensionKey.isValid(atLimit + "a")).isFalse();
  }

  @Test
  void exposesNamespaceAndPath() {
    DimensionKey key = DimensionKey.parse("mymod:mining/deep");
    assertThat(key.namespace()).isEqualTo("mymod");
    assertThat(key.path()).isEqualTo("mining/deep");
  }

  @Test
  void vanillaConstants() {
    assertThat(DimensionKey.OVERWORLD.namespacedId()).isEqualTo("minecraft:overworld");
    assertThat(DimensionKey.THE_NETHER.namespacedId()).isEqualTo("minecraft:the_nether");
    assertThat(DimensionKey.THE_END.namespacedId()).isEqualTo("minecraft:the_end");
  }

  @Test
  void equalityAndOrderingAreByText() {
    assertThat(DimensionKey.parse("minecraft:overworld")).isEqualTo(DimensionKey.OVERWORLD);
    List<DimensionKey> keys =
        new ArrayList<>(
            List.of(DimensionKey.THE_END, DimensionKey.parse("a:z"), DimensionKey.OVERWORLD));
    keys.sort(null);
    assertThat(keys)
        .containsExactly(DimensionKey.parse("a:z"), DimensionKey.OVERWORLD, DimensionKey.THE_END);
  }
}
