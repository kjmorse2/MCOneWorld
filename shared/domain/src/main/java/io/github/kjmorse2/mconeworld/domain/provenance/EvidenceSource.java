// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.provenance;

/** Where a provenance claim came from. Constant names are persisted, so never rename them. */
public enum EvidenceSource {
  /** Observed live by MCOneWorld Paper while the season was active (generation, load, or edit). */
  RUNTIME_OBSERVATION,
  /** Found by an offline scan of world files (existence only, no season information). */
  OFFLINE_SCAN,
  /** Inferred from a chunk's per-chunk timestamp in its region file. */
  REGION_FILE_TIMESTAMP,
  /** Inferred from a chunk's accumulated inhabited-time statistic. */
  INHABITED_TIME,
  /** Present when an existing vanilla world was imported into MCOneWorld. */
  WORLD_IMPORT,
  /** Asserted manually by an administrator. */
  ADMINISTRATOR
}
