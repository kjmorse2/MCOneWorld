// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.internal.json;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;

/**
 * Stable codes for JSON-profile failures. {@code sidecar.json.limit.*} codes are resource limits.
 * Every other {@code sidecar.json.*} code is a syntax or encoding failure.
 */
public final class JsonIssueCodes {
  public static final IssueCode BOM = IssueCode.of("sidecar.json.bom");
  public static final IssueCode INVALID_UTF8 = IssueCode.of("sidecar.json.invalid_utf8");
  public static final IssueCode MALFORMED = IssueCode.of("sidecar.json.malformed");
  public static final IssueCode DUPLICATE_KEY = IssueCode.of("sidecar.json.duplicate_key");
  public static final IssueCode UNPAIRED_SURROGATE =
      IssueCode.of("sidecar.json.unpaired_surrogate");
  public static final IssueCode NON_INTEGER_NUMBER =
      IssueCode.of("sidecar.json.non_integer_number");
  public static final IssueCode NUMBER_OUT_OF_RANGE =
      IssueCode.of("sidecar.json.number_out_of_range");
  public static final IssueCode LIMIT_DOCUMENT_SIZE =
      IssueCode.of("sidecar.json.limit.document_size");
  public static final IssueCode LIMIT_NESTING_DEPTH =
      IssueCode.of("sidecar.json.limit.nesting_depth");
  public static final IssueCode LIMIT_STRING_LENGTH =
      IssueCode.of("sidecar.json.limit.string_length");
  public static final IssueCode LIMIT_ARRAY_ELEMENTS =
      IssueCode.of("sidecar.json.limit.array_elements");
  public static final IssueCode LIMIT_OBJECT_MEMBERS =
      IssueCode.of("sidecar.json.limit.object_members");

  /** Prefix shared by all resource-limit codes. */
  public static final String LIMIT_PREFIX = "sidecar.json.limit.";

  private JsonIssueCodes() {}
}
