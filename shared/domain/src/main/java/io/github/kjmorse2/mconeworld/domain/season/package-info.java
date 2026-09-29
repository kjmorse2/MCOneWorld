// SPDX-License-Identifier: MIT
/**
 * Season records and their lifecycle.
 *
 * <p>A season has a stable {@link io.github.kjmorse2.mconeworld.domain.SeasonId}, a non-unique
 * display name, a creation instant, a {@link
 * io.github.kjmorse2.mconeworld.domain.season.SeasonStatus}, optional per-dimension origins, and an
 * absolute, dimension-qualified spawn. Which season is active is decided explicitly by the archive
 * state ({@code ArchiveState.activeSeasonId}). It is never inferred from timestamps or list order.
 */
@NullMarked
package io.github.kjmorse2.mconeworld.domain.season;

import org.jspecify.annotations.NullMarked;
