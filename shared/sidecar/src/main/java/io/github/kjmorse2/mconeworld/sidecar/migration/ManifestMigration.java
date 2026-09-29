// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.migration;

/**
 * One migration step, implemented as a <b>pure, deterministic</b> function from manifest bytes of
 * version {@link MigrationStep#from()} to manifest bytes of {@link MigrationStep#to()}.
 *
 * <p>Contract for implementers:
 *
 * <ul>
 *   <li>Same input, same output bytes: no clock, randomness, locale, or environment. The runner
 *       re-runs the step and refuses to commit if the output differs.
 *   <li>Never mutate the input array. Never perform I/O.
 *   <li>Record every transformed field, preserved extension, and warning in the {@link
 *       MigrationRecorder}.
 *   <li>Data that cannot be carried forward without loss must be reported with {@link
 *       MigrationRecorder#unsupported} and must fail the step. It is never dropped quietly, and
 *       never hidden in a warning.
 *   <li>Write canonical output for the target version, and increase the revision by exactly 1.
 * </ul>
 */
public interface ManifestMigration {
  /** Identity and version step. */
  MigrationStep step();

  /**
   * Migrates {@code source}.
   *
   * @throws MigrationException with {@code PREREQUISITE_FAILED}, {@code UNSUPPORTED_DATA}, or
   *     {@code STEP_FAILED}
   */
  byte[] migrate(byte[] source, MigrationRecorder recorder) throws MigrationException;
}
