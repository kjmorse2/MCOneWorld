// SPDX-License-Identifier: MIT
/**
 * The byte encoding of protocol version 1 ({@code docs/formats/protocol-v1.md}): {@link
 * io.github.kjmorse2.mconeworld.protocol.codec.ProtocolCodec} encodes and decodes the messages of
 * the parent package over {@code byte[]} and {@link java.nio.ByteBuffer}, deterministically and
 * within fixed bounds. Decoding accepts only canonical encodings and throws only {@link
 * io.github.kjmorse2.mconeworld.protocol.ProtocolException}.
 */
@NullMarked
package io.github.kjmorse2.mconeworld.protocol.codec;

import org.jspecify.annotations.NullMarked;
