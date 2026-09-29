// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.domain.archive;

import io.github.kjmorse2.mconeworld.domain.DisplayName;
import io.github.kjmorse2.mconeworld.domain.PortalMarkerId;
import io.github.kjmorse2.mconeworld.domain.SeasonId;
import io.github.kjmorse2.mconeworld.domain.coordinate.BlockPosition;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A sidecar record of an intended controlled-travel endpoint (root AGENTS.md §16).
 *
 * <p>A marker is <b>data, not permission</b>:
 *
 * <ul>
 *   <li>It grants no authorization. Unlocks, costs, and permissions are decided by server-side
 *       policy at travel time.
 *   <li>Its destination is <b>not</b> known to be safe. Terrain may have changed since the marker
 *       was recorded, so the runtime must validate destination safety before every teleport. The
 *       accessor is named {@link #recordedDestination()} to make this explicit.
 * </ul>
 *
 * @param id stable identity
 * @param label non-unique display name
 * @param recordedDestination absolute, dimension-qualified destination as recorded (unverified)
 * @param destinationSeason the historical season the destination belongs to, or {@code null} if not
 *     associated with a season
 * @param createdAt when the marker was recorded, supplied by the caller
 */
public record PortalMarker(
    PortalMarkerId id,
    DisplayName label,
    BlockPosition recordedDestination,
    @Nullable SeasonId destinationSeason,
    Instant createdAt) {
  public PortalMarker {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(label, "label");
    Objects.requireNonNull(recordedDestination, "recordedDestination");
    Objects.requireNonNull(createdAt, "createdAt");
  }
}
