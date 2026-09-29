// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.storage;

import java.io.IOException;

/** Exposes package-private storage test seams to tests in other packages. */
public final class StorageTestAccess {
  private StorageTestAccess() {}

  /** Simulates the writer's process dying: OS lock released, owner file left behind. */
  public static void crash(SidecarWriter writer) throws IOException {
    writer.lock().abandonLikeACrashedProcess();
  }

  /** A fault-injecting filesystem failing (or crashing, if {@code crash}) at {@code stage}. */
  public static StorageFileSystem faulty(CommitStage stage, boolean crash) {
    return crash ? FaultyFileSystem.crashingAt(stage) : FaultyFileSystem.failingAt(stage);
  }

  /** A filesystem that only counts mutations and open handles. */
  public static StorageFileSystem counting() {
    return new FaultyFileSystem();
  }

  public static int mutations(StorageFileSystem fs) {
    return ((FaultyFileSystem) fs).mutations.get();
  }

  public static int openHandles(StorageFileSystem fs) {
    return ((FaultyFileSystem) fs).openHandles.get();
  }

  /** Whether {@code t} is the simulated-crash error. */
  public static boolean isSimulatedCrash(Throwable t) {
    return t instanceof SimulatedCrash;
  }
}
