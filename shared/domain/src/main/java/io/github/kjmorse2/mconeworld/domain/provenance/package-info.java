// SPDX-License-Identifier: MIT
/**
 * Chunk provenance: evidence about when and in which seasons a chunk was observed. It is <b>not</b>
 * exclusive ownership (root AGENTS.md §9).
 *
 * <p>These concepts are independent of one another:
 *
 * <ul>
 *   <li>{@link io.github.kjmorse2.mconeworld.domain.provenance.FirstObservation}: either a known
 *       season, or {@code Legacy}, meaning the chunk existed before MCOneWorld tracking and its
 *       season is unknown. Legacy chunks never pretend to have a season.
 *   <li>{@link io.github.kjmorse2.mconeworld.domain.provenance.SeasonActivity}: later activity,
 *       with at most one entry per season. A chunk can be active in many seasons.
 *   <li>{@link io.github.kjmorse2.mconeworld.domain.provenance.ProvenanceEvidence}: evidence source
 *       and confidence, for every claim.
 *   <li>{@link io.github.kjmorse2.mconeworld.domain.provenance.ChunkProtection}: independent of all
 *       provenance.
 * </ul>
 *
 * <p>{@link io.github.kjmorse2.mconeworld.domain.provenance.ChunkEnvelope} is a derived bounding
 * summary and never a membership set. How millions of provenance records are stored is decided by
 * the chunk-index ADR (Prompt 08), not here.
 */
@NullMarked
package io.github.kjmorse2.mconeworld.domain.provenance;

import org.jspecify.annotations.NullMarked;
