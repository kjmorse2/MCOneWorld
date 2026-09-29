// SPDX-License-Identifier: MIT
/**
 * The MCOneWorld sidecar: the versioned, checksummed metadata directory stored next to (never
 * inside) a Minecraft world save.
 *
 * <p>This module owns the manifest codec, schema migrations, storage safety (atomic writes,
 * checksums, locking, path confinement), and the chunk-index abstraction. It operates only on a
 * caller-supplied sidecar root and never discovers server paths through global state. Read-only
 * opening never rewrites, migrates, or repairs files.
 *
 * <p>Serialization libraries used internally (ADR 0003) must never appear in this module's public
 * API.
 */
@NullMarked
package io.github.kjmorse2.mconeworld.sidecar;

import org.jspecify.annotations.NullMarked;
