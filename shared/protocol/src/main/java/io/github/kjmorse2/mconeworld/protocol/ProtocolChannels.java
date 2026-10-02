// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.protocol;

/**
 * The MCOneWorld-owned channel names. Platform adapters register them; shared code only names them.
 * A server sends on {@link #CONTEXT} only to a client that sent {@link #HELLO} first, so vanilla
 * clients never receive MCOneWorld payloads (root AGENTS.md §14, §15).
 */
public final class ProtocolChannels {
  /** Client to server: the client's supported versions and capabilities. */
  public static final String HELLO = "mconeworld:hello";

  /** Server to client: the negotiated version and the active season context. */
  public static final String CONTEXT = "mconeworld:context";

  private ProtocolChannels() {}
}
