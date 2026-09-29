// SPDX-License-Identifier: MIT
/**
 * Structured validation results (shared/AGENTS.md "Validation").
 *
 * <p>A {@link io.github.kjmorse2.mconeworld.domain.validation.ValidationIssue} carries:
 *
 * <ul>
 *   <li>a stable machine-readable {@link io.github.kjmorse2.mconeworld.domain.validation.IssueCode}
 *   <li>a {@link io.github.kjmorse2.mconeworld.domain.validation.Severity}
 *   <li>a human-readable message
 *   <li>a logical {@link io.github.kjmorse2.mconeworld.domain.validation.IssueLocation}
 *   <li>an optional remediation hint
 *   <li>an optional causal exception. It is for diagnostics only: it is excluded from equality and
 *       must never be serialized into portable formats.
 * </ul>
 *
 * <p>Callers branch on codes and severities, never on message text. {@link
 * io.github.kjmorse2.mconeworld.domain.validation.ValidationResult} orders issues
 * deterministically, so reports diff cleanly.
 */
@NullMarked
package io.github.kjmorse2.mconeworld.domain.validation;

import org.jspecify.annotations.NullMarked;
