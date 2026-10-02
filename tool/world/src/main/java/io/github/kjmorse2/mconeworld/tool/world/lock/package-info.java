// SPDX-License-Identifier: MIT
/**
 * Detecting a running Minecraft server: it holds an OS file lock on the world's {@code
 * session.lock} while the world is open. The probe never creates, writes, or deletes the file.
 */
@NullMarked
package io.github.kjmorse2.mconeworld.tool.world.lock;

import org.jspecify.annotations.NullMarked;
