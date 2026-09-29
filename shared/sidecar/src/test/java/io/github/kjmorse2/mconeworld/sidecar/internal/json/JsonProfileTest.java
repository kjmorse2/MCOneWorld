// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.internal.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonArray;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonNumber;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonObject;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonString;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonProfileTest {
  private static final JsonReadLimits LIMITS = new JsonReadLimits(4096, 8, 64, 16, 16);
  private static final JsonReader READER = new JsonReader(LIMITS);

  private static JsonValue read(String text) throws JsonReadException {
    return READER.read(text.getBytes(StandardCharsets.UTF_8));
  }

  private static void assertCode(byte[] bytes, IssueCode expected) {
    assertThatThrownBy(() -> READER.read(bytes))
        .isInstanceOfSatisfying(
            JsonReadException.class, e -> assertThat(e.code()).isEqualTo(expected));
  }

  private static void assertCode(String text, IssueCode expected) {
    assertCode(text.getBytes(StandardCharsets.UTF_8), expected);
  }

  @Test
  void readsTheProfile() throws JsonReadException {
    JsonValue value = read("{\"a\": [1, -9223372036854775808, true, null], \"b\": \"x\\u00e9\"}");
    JsonObject object = (JsonObject) value;
    assertThat(((JsonArray) object.get("a")).elements()).hasSize(4);
    assertThat(((JsonNumber) ((JsonArray) object.get("a")).elements().get(1)).value())
        .isEqualTo(Long.MIN_VALUE);
    assertThat(((JsonString) object.get("b")).value()).isEqualTo("xé");
  }

  @Test
  void syntaxFailures() {
    assertCode("", JsonIssueCodes.MALFORMED);
    assertCode("{\"a\": 1", JsonIssueCodes.MALFORMED); // truncated
    assertCode("{\"a\": [1, 2", JsonIssueCodes.MALFORMED); // truncated inside array
    assertCode("{} {}", JsonIssueCodes.MALFORMED); // trailing content
    assertCode("{\"a\": 1,}", JsonIssueCodes.MALFORMED); // trailing comma
    assertCode("{'a': 1}", JsonIssueCodes.MALFORMED); // single quotes
    assertCode("{a: 1}", JsonIssueCodes.MALFORMED); // unquoted names
    assertCode("{\"a\": 01}", JsonIssueCodes.MALFORMED); // leading zero
    assertCode("{\"a\": NaN}", JsonIssueCodes.MALFORMED);
    assertCode("/* c */ {}", JsonIssueCodes.MALFORMED); // comments
    assertCode("{\"a\": \"tab\tinside\"}", JsonIssueCodes.MALFORMED); // unescaped control char
  }

  @Test
  void duplicateKeysAreRejectedNotResolved() {
    assertCode("{\"a\": 1, \"a\": 1}", JsonIssueCodes.DUPLICATE_KEY);
    assertCode("{\"x\": {\"a\": 1, \"b\": 2, \"a\": 3}}", JsonIssueCodes.DUPLICATE_KEY);
  }

  @ParameterizedTest
  @ValueSource(strings = {"1.0", "1e3", "-0.5", "1E-2"})
  void onlyIntegersAreAllowed(String number) {
    assertCode("{\"n\": " + number + "}", JsonIssueCodes.NON_INTEGER_NUMBER);
  }

  @Test
  void integersOutsideLongRangeAreRejected() {
    assertCode("{\"n\": 9223372036854775808}", JsonIssueCodes.NUMBER_OUT_OF_RANGE);
    assertCode("{\"n\": -9223372036854775809}", JsonIssueCodes.NUMBER_OUT_OF_RANGE);
    assertCode("{\"n\": " + "9".repeat(100) + "}", JsonIssueCodes.NUMBER_OUT_OF_RANGE);
  }

  @Test
  void malformedUnicodeIsRejected() {
    assertCode(
        new byte[] {'"', (byte) 0xC3, (byte) 0x28, '"'},
        JsonIssueCodes.INVALID_UTF8); // bad continuation
    assertCode(
        new byte[] {'"', (byte) 0xC0, (byte) 0x80, '"'},
        JsonIssueCodes.INVALID_UTF8); // overlong NUL
    assertCode(
        new byte[] {'"', (byte) 0xED, (byte) 0xA0, (byte) 0x80, '"'},
        JsonIssueCodes.INVALID_UTF8); // encoded surrogate
    assertCode(
        new byte[] {'"', (byte) 0xE2, (byte) 0x82},
        JsonIssueCodes.INVALID_UTF8); // truncated sequence
    assertCode(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '{', '}'}, JsonIssueCodes.BOM);
    assertCode("\"\\ud800\"", JsonIssueCodes.UNPAIRED_SURROGATE); // lone high surrogate escape
    assertCode("\"\\udc00x\"", JsonIssueCodes.UNPAIRED_SURROGATE); // lone low surrogate escape
    assertCode("{\"\\ud800\": 1}", JsonIssueCodes.UNPAIRED_SURROGATE); // in a member name
  }

  @Test
  void pairedSurrogateEscapesAreAccepted() throws JsonReadException {
    assertThat(((JsonString) read("\"\\ud83c\\udf32\"")).value()).isEqualTo("🌲");
  }

  @Test
  void limitsAreEnforcedWithSpecificCodes() {
    assertCode(
        ("\"" + "x".repeat(5000) + "\"").getBytes(StandardCharsets.UTF_8),
        JsonIssueCodes.LIMIT_DOCUMENT_SIZE);
    assertCode("[[[[[[[[[1]]]]]]]]]", JsonIssueCodes.LIMIT_NESTING_DEPTH); // 9 > 8
    assertCode("\"" + "x".repeat(65) + "\"", JsonIssueCodes.LIMIT_STRING_LENGTH);
    assertCode("{\"" + "k".repeat(65) + "\": 1}", JsonIssueCodes.LIMIT_STRING_LENGTH);
    assertCode(
        "\"" + "x".repeat(1000) + "\"",
        JsonIssueCodes.LIMIT_STRING_LENGTH); // Jackson backstop path
    assertCode("[" + "1,".repeat(16) + "1]", JsonIssueCodes.LIMIT_ARRAY_ELEMENTS);
    StringBuilder members = new StringBuilder("{");
    for (int i = 0; i < 17; i++) {
      members.append(i == 0 ? "" : ",").append("\"k").append(i).append("\": 0");
    }
    assertCode(members.append('}').toString(), JsonIssueCodes.LIMIT_OBJECT_MEMBERS);
  }

  @Test
  void limitsHaveHardMaxima() {
    assertThatThrownBy(
            () -> new JsonReadLimits(JsonReadLimits.HARD_MAX_DOCUMENT_BYTES + 1, 8, 8, 8, 8))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new JsonReadLimits(1, 0, 8, 8, 8))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void canonicalWriterFormat() {
    Map<String, JsonValue> members = new LinkedHashMap<>();
    members.put("b", new JsonArray(List.of(new JsonNumber(-0), JsonValue.JsonNull.INSTANCE)));
    members.put("a", new JsonString("q\"\\\n\u0001é🌲"));
    members.put("c", new JsonObject(Map.of()));
    members.put("d", new JsonArray(List.of()));
    String written =
        new String(CanonicalJsonWriter.write(new JsonObject(members)), StandardCharsets.UTF_8);
    assertThat(written)
        .isEqualTo(
            "{\n  \"a\": \"q\\\"\\\\\\n\\u0001é🌲\",\n  \"b\": [\n    0,\n    null\n  ],\n  \"c\": {},\n  \"d\": []\n}\n");
  }

  @Test
  void memberOrderIsCodePointOrderNotUtf16Order() {
    // U+FF01 (BMP, high) vs U+1F332 (supplementary): UTF-16 order puts the emoji first; code point
    // order does not.
    Map<String, JsonValue> members = new LinkedHashMap<>();
    members.put("🌲", new JsonNumber(1));
    members.put("！", new JsonNumber(2));
    String written =
        new String(CanonicalJsonWriter.write(new JsonObject(members)), StandardCharsets.UTF_8);
    assertThat(written.indexOf("！")).isLessThan(written.indexOf("🌲"));
  }

  @Test
  void writeThenReadIsIdentityAndInsertionOrderIndependent() throws JsonReadException {
    Map<String, JsonValue> forward = new LinkedHashMap<>();
    forward.put("x", new JsonNumber(1));
    forward.put("y", new JsonNumber(2));
    Map<String, JsonValue> backward = new LinkedHashMap<>();
    backward.put("y", new JsonNumber(2));
    backward.put("x", new JsonNumber(1));
    byte[] a = CanonicalJsonWriter.write(new JsonObject(forward));
    byte[] b = CanonicalJsonWriter.write(new JsonObject(backward));
    assertThat(a).isEqualTo(b);
    assertThat(CanonicalJsonWriter.write(READER.read(a))).isEqualTo(a);
  }
}
