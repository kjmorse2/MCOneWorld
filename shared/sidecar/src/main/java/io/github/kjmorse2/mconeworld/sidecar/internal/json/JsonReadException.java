// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.internal.json;

import io.github.kjmorse2.mconeworld.domain.validation.IssueCode;
import java.io.Serial;

/**
 * JSON could not be read. {@link #code()} is one of {@link JsonIssueCodes}. The message never
 * contains document content beyond a bounded excerpt of member names.
 */
public final class JsonReadException extends Exception {
  @Serial private static final long serialVersionUID = 1L;

  private final transient IssueCode code;
  private final long line;
  private final long column;

  public JsonReadException(IssueCode code, String message, long line, long column) {
    super(message + (line > 0 ? " (line " + line + ", column " + column + ")" : ""));
    this.code = code;
    this.line = line;
    this.column = column;
  }

  public IssueCode code() {
    return code;
  }

  /** 1-based line of the problem, or 0 if unknown. */
  public long line() {
    return line;
  }

  /** 1-based column of the problem, or 0 if unknown. */
  public long column() {
    return column;
  }
}
