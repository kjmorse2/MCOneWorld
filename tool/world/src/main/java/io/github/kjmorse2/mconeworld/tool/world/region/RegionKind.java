// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.world.region;

/** The three parallel region-file families of a dimension. */
public enum RegionKind {
  /** Terrain, block entities, and structures: the chunk itself. */
  REGION("region"),
  /** Entities, stored separately since 1.17. */
  ENTITIES("entities"),
  /** Points of interest (beds, workstations, portals). */
  POI("poi");

  private final String directoryName;

  RegionKind(String directoryName) {
    this.directoryName = directoryName;
  }

  /** The directory name inside a dimension directory. */
  public String directoryName() {
    return directoryName;
  }
}
