// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar.manifest;

import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonObject;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * Manifest members that this reader does not interpret but must write back unchanged: unknown
 * members from a newer minor schema version, and namespaced {@code extensions} objects.
 *
 * <p>The contents are opaque by design, so no JSON type leaks into the public API. Entries are
 * keyed by a stable record location (for example {@code seasons/<id>}). They follow their record
 * through read-modify-write, and they disappear if that record is removed.
 */
public final class PreservedMembers {
  private static final PreservedMembers NONE = new PreservedMembers(Map.of());

  private final Map<String, JsonObject> byLocation;

  PreservedMembers(Map<String, JsonObject> byLocation) {
    this.byLocation = Collections.unmodifiableMap(new TreeMap<>(byLocation));
  }

  /** Nothing preserved. */
  public static PreservedMembers none() {
    return NONE;
  }

  /** Returns whether nothing is preserved. */
  public boolean isEmpty() {
    return byLocation.isEmpty();
  }

  /** Locations that carry preserved members, sorted; for diagnostics and tests. */
  public Set<String> locations() {
    return byLocation.keySet();
  }

  @Nullable JsonObject at(String location) {
    return byLocation.get(location);
  }

  @Override
  public boolean equals(@Nullable Object o) {
    return o instanceof PreservedMembers other && byLocation.equals(other.byLocation);
  }

  @Override
  public int hashCode() {
    return byLocation.hashCode();
  }

  @Override
  public String toString() {
    return "PreservedMembers" + byLocation.keySet();
  }
}
