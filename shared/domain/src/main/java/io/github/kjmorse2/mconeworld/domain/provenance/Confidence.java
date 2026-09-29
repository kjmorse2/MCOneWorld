// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.provenance;

/**
 * How strongly the evidence supports a claim. Destructive tooling must treat anything below {@link
 * #HIGH} as insufficient on its own (root AGENTS.md §10: never delete based on a single activity
 * statistic). Constant names are persisted.
 */
public enum Confidence {
  /** Direct observation, such as runtime generation seen by the plugin. */
  HIGH,
  /** Strong inference from a reliable signal. */
  MEDIUM,
  /** Weak inference, for example from a single timestamp or statistic. */
  LOW
}
