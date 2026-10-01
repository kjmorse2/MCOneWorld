// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.operation;

import io.github.kjmorse2.mconeworld.domain.Checksum;
import io.github.kjmorse2.mconeworld.domain.DomainErrorCode;
import io.github.kjmorse2.mconeworld.domain.InvalidDomainValueException;
import io.github.kjmorse2.mconeworld.domain.validation.IssueLocation;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationIssue;
import io.github.kjmorse2.mconeworld.domain.validation.ValidationResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The state an operation depends on. A plan stores the state it was planned against as its
 * preconditions. Before applying, the caller observes the same things again and records the
 * observation in the {@code APPLY_STARTED} journal event. The two must be equal, or the operation
 * refuses to start ({@link #differences}).
 *
 * <p>The world's identity is expressed here too, without paths: for example a {@code
 * world:level.dat} checksum and a {@code minecraft:data_version} fact.
 *
 * @param manifest the sidecar manifest state
 * @param checksums exact input contents, by logical key; at most {@value #MAX_ENTRIES}
 * @param facts other observed values, such as a data version, by logical key; each value is a token
 *     {@code [A-Za-z0-9._:+-]{1,128}}; at most {@value #MAX_ENTRIES}
 */
public record PreconditionSnapshot(
    ExpectedManifest manifest,
    SortedMap<InputKey, Checksum> checksums,
    SortedMap<InputKey, String> facts) {
  /** Maximum entries in each map. */
  public static final int MAX_ENTRIES = 1024;

  /** Maximum length of a fact value, in characters. */
  public static final int MAX_FACT_LENGTH = 128;

  /**
   * Copies both maps into sorted, unmodifiable maps.
   *
   * @throws InvalidDomainValueException with {@code VALUE_INVALID} if a map is too large, or {@code
   *     KEY_MALFORMED} for a malformed fact value
   */
  public PreconditionSnapshot {
    Objects.requireNonNull(manifest, "manifest");
    checksums = Collections.unmodifiableSortedMap(new TreeMap<>(checksums));
    facts = Collections.unmodifiableSortedMap(new TreeMap<>(facts));
    checksums.values().forEach(v -> Objects.requireNonNull(v, "checksum"));
    facts.values().forEach(v -> Keys.check("fact value", v, Keys.TOKEN, MAX_FACT_LENGTH));
    if (checksums.size() > MAX_ENTRIES || facts.size() > MAX_ENTRIES) {
      throw new InvalidDomainValueException(
          DomainErrorCode.VALUE_INVALID, "At most " + MAX_ENTRIES + " precondition entries");
    }
  }

  /** A snapshot of only the manifest state. */
  public static PreconditionSnapshot ofManifest(ExpectedManifest manifest) {
    return new PreconditionSnapshot(manifest, new TreeMap<>(), new TreeMap<>());
  }

  /**
   * Compares an observation with these expectations. Every difference is one {@code ERROR} issue
   * with code {@link OperationIssueCodes#PRECONDITION_CHANGED}, located under {@code $.observed}:
   * {@code .manifest}, {@code .checksums[<key>]}, or {@code .facts[<key>]}. Missing and extra keys
   * are differences too: the observation must describe exactly the same inputs.
   */
  public ValidationResult differences(PreconditionSnapshot observed) {
    List<ValidationIssue> issues = new ArrayList<>();
    IssueLocation at = IssueLocation.root().field("observed");
    if (!manifest.equals(observed.manifest)) {
      issues.add(
          ValidationIssue.error(
              OperationIssueCodes.PRECONDITION_CHANGED,
              at.field("manifest"),
              "The sidecar manifest is no longer in the planned state"));
    }
    compare(issues, at.field("checksums"), checksums, observed.checksums);
    compare(issues, at.field("facts"), facts, observed.facts);
    return ValidationResult.of(issues);
  }

  private static <V> void compare(
      List<ValidationIssue> issues,
      IssueLocation at,
      Map<InputKey, V> expected,
      Map<InputKey, V> observed) {
    TreeSet<InputKey> keys = new TreeSet<>(expected.keySet());
    keys.addAll(observed.keySet());
    for (InputKey key : keys) {
      V want = expected.get(key);
      V got = observed.get(key);
      if (!Objects.equals(want, got)) {
        String what =
            want == null ? "was not planned" : got == null ? "was not observed" : "has changed";
        issues.add(
            ValidationIssue.error(
                OperationIssueCodes.PRECONDITION_CHANGED,
                at.key(key.value()),
                "Input " + key + " " + what));
      }
    }
  }
}
