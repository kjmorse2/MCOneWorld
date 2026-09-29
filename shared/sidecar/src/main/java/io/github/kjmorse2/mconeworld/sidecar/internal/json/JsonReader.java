// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.internal.json;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonArray;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonBoolean;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonNull;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonNumber;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonObject;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonString;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.json.JsonFactoryBuilder;
import tools.jackson.core.json.JsonReadFeature;

/**
 * Reads bytes in the MCOneWorld JSON profile into a {@link JsonValue} tree. It is thread-safe and
 * stateless per call.
 *
 * <p>Checks run in this order, and the first failure wins:
 *
 * <ol>
 *   <li>size
 *   <li>byte-order mark
 *   <li>strict UTF-8, which rejects overlong forms, encoded surrogates, and truncated sequences
 *   <li>JSON syntax, with every Jackson {@code ALLOW_*} leniency disabled
 *   <li>profile rules: nesting depth, string, array, and object limits, duplicate keys, unpaired
 *       surrogates from {@code \\u} escapes, and integer-only numbers
 * </ol>
 *
 * <p>Our own limit checks fire before Jackson's, which are configured one unit looser as a
 * backstop, so most failures carry a precise code. If Jackson's backstop fires first (for example
 * on a string much longer than the limit), the code is derived from its constraint type.
 */
public final class JsonReader {
  private final JsonReadLimits limits;
  private final JsonFactory factory;

  public JsonReader(JsonReadLimits limits) {
    this.limits = limits;
    JsonFactoryBuilder builder =
        JsonFactory.builder()
            .streamReadConstraints(
                StreamReadConstraints.builder()
                    .maxNestingDepth(limits.maxNestingDepth() + 1)
                    .maxStringLength(limits.maxStringChars() + 1)
                    .maxNameLength(limits.maxStringChars() + 1)
                    .maxNumberLength(64)
                    .maxDocumentLength(limits.maxDocumentBytes())
                    .build());
    // Disable every leniency explicitly, including ones added in future Jackson versions.
    for (JsonReadFeature feature : JsonReadFeature.values()) {
      if (feature.name().startsWith("ALLOW_")) {
        builder.disable(feature);
      }
    }
    this.factory = builder.build();
  }

  /**
   * Parses {@code bytes}. The array is never modified.
   *
   * @throws JsonReadException with a code from {@link JsonIssueCodes}
   */
  public JsonValue read(byte[] bytes) throws JsonReadException {
    if (bytes.length > limits.maxDocumentBytes()) {
      throw new JsonReadException(
          JsonIssueCodes.LIMIT_DOCUMENT_SIZE,
          "Document is " + bytes.length + " bytes; limit is " + limits.maxDocumentBytes(),
          0,
          0);
    }
    if (bytes.length >= 3
        && bytes[0] == (byte) 0xEF
        && bytes[1] == (byte) 0xBB
        && bytes[2] == (byte) 0xBF) {
      throw new JsonReadException(JsonIssueCodes.BOM, "UTF-8 byte-order mark is not allowed", 1, 1);
    }
    char[] chars = decodeStrictUtf8(bytes);
    try (JsonParser parser = factory.createParser(ObjectReadContext.empty(), chars)) {
      JsonToken first = parser.nextToken();
      if (first == null) {
        throw fail(JsonIssueCodes.MALFORMED, "Empty document", parser);
      }
      JsonValue root = readValue(parser, first, 0);
      if (parser.nextToken() != null) {
        throw fail(
            JsonIssueCodes.MALFORMED, "Unexpected content after the top-level value", parser);
      }
      return root;
    } catch (StreamConstraintsException e) {
      throw new JsonReadException(
          constraintCode(e),
          "Resource limit exceeded: " + e.getOriginalMessage(),
          line(e.getLocation()),
          column(e.getLocation()));
    } catch (JacksonException e) {
      throw new JsonReadException(
          JsonIssueCodes.MALFORMED,
          "Malformed JSON: " + e.getOriginalMessage(),
          line(e.getLocation()),
          column(e.getLocation()));
    }
  }

  private JsonValue readValue(JsonParser p, JsonToken token, int depth) throws JsonReadException {
    return switch (token) {
      case START_OBJECT -> readObject(p, depth + 1);
      case START_ARRAY -> readArray(p, depth + 1);
      case VALUE_STRING -> new JsonString(checkedString(p.getString(), p));
      case VALUE_NUMBER_INT -> {
        if (p.getNumberType() == JsonParser.NumberType.BIG_INTEGER) {
          throw fail(
              JsonIssueCodes.NUMBER_OUT_OF_RANGE, "Integer outside the signed 64-bit range", p);
        }
        yield new JsonNumber(p.getLongValue());
      }
      case VALUE_NUMBER_FLOAT ->
          throw fail(
              JsonIssueCodes.NON_INTEGER_NUMBER,
              "Only integers are allowed; found a fraction or exponent",
              p);
      case VALUE_TRUE -> new JsonBoolean(true);
      case VALUE_FALSE -> new JsonBoolean(false);
      case VALUE_NULL -> JsonNull.INSTANCE;
      default -> throw fail(JsonIssueCodes.MALFORMED, "Unexpected token " + token, p);
    };
  }

  private JsonObject readObject(JsonParser p, int depth) throws JsonReadException {
    checkDepth(depth, p);
    Map<String, JsonValue> members = new LinkedHashMap<>();
    JsonToken token;
    while ((token = p.nextToken()) == JsonToken.PROPERTY_NAME) {
      String name = checkedString(p.currentName(), p);
      if (members.size() >= limits.maxObjectMembers()) {
        throw fail(
            JsonIssueCodes.LIMIT_OBJECT_MEMBERS,
            "Object has more than " + limits.maxObjectMembers() + " members",
            p);
      }
      if (members.containsKey(name)) {
        throw fail(JsonIssueCodes.DUPLICATE_KEY, "Duplicate key \"" + excerpt(name) + "\"", p);
      }
      JsonToken valueToken = p.nextToken();
      if (valueToken == null) {
        throw fail(JsonIssueCodes.MALFORMED, "Unexpected end of input", p);
      }
      members.put(name, readValue(p, valueToken, depth));
    }
    if (token != JsonToken.END_OBJECT) {
      throw fail(JsonIssueCodes.MALFORMED, "Unexpected end of input inside an object", p);
    }
    return new JsonObject(members);
  }

  private JsonArray readArray(JsonParser p, int depth) throws JsonReadException {
    checkDepth(depth, p);
    List<JsonValue> elements = new ArrayList<>();
    JsonToken token;
    while ((token = p.nextToken()) != JsonToken.END_ARRAY) {
      if (token == null) {
        throw fail(JsonIssueCodes.MALFORMED, "Unexpected end of input inside an array", p);
      }
      if (elements.size() >= limits.maxArrayElements()) {
        throw fail(
            JsonIssueCodes.LIMIT_ARRAY_ELEMENTS,
            "Array has more than " + limits.maxArrayElements() + " elements",
            p);
      }
      elements.add(readValue(p, token, depth));
    }
    return new JsonArray(elements);
  }

  private void checkDepth(int depth, JsonParser p) throws JsonReadException {
    if (depth > limits.maxNestingDepth()) {
      throw fail(
          JsonIssueCodes.LIMIT_NESTING_DEPTH, "Nesting deeper than " + limits.maxNestingDepth(), p);
    }
  }

  private String checkedString(String value, JsonParser p) throws JsonReadException {
    if (value.length() > limits.maxStringChars()) {
      throw fail(
          JsonIssueCodes.LIMIT_STRING_LENGTH,
          "String longer than " + limits.maxStringChars() + " chars",
          p);
    }
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (Character.isHighSurrogate(c)
          && i + 1 < value.length()
          && Character.isLowSurrogate(value.charAt(i + 1))) {
        i++;
      } else if (Character.isSurrogate(c)) {
        throw fail(
            JsonIssueCodes.UNPAIRED_SURROGATE, "String contains an unpaired UTF-16 surrogate", p);
      }
    }
    return value;
  }

  private static char[] decodeStrictUtf8(byte[] bytes) throws JsonReadException {
    CharsetDecoder decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      CharBuffer chars = decoder.decode(ByteBuffer.wrap(bytes));
      char[] out = new char[chars.remaining()];
      chars.get(out);
      return out;
    } catch (CharacterCodingException e) {
      throw new JsonReadException(JsonIssueCodes.INVALID_UTF8, "Input is not valid UTF-8", 0, 0);
    }
  }

  private static IssueCode constraintCode(StreamConstraintsException e) {
    String message = String.valueOf(e.getOriginalMessage()).toLowerCase(Locale.ROOT);
    if (message.contains("nesting")) {
      return JsonIssueCodes.LIMIT_NESTING_DEPTH;
    }
    if (message.contains("number")) {
      return JsonIssueCodes.NUMBER_OUT_OF_RANGE;
    }
    if (message.contains("document")) {
      return JsonIssueCodes.LIMIT_DOCUMENT_SIZE;
    }
    return JsonIssueCodes.LIMIT_STRING_LENGTH;
  }

  private static JsonReadException fail(IssueCode code, String message, JsonParser p) {
    TokenStreamLocation at = p.currentTokenLocation();
    return new JsonReadException(code, message, line(at), column(at));
  }

  private static long line(TokenStreamLocation at) {
    return at == null ? 0 : Math.max(0, at.getLineNr());
  }

  private static long column(TokenStreamLocation at) {
    return at == null ? 0 : Math.max(0, at.getColumnNr());
  }

  private static String excerpt(String s) {
    return s.length() <= 64 ? s : s.substring(0, 64) + "…";
  }
}
