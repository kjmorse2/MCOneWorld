// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.app;

import javafx.application.Application;

/**
 * Entry point. It is deliberately not the {@link Application} subclass: the JavaFX launcher
 * requires JavaFX on the module path when the main class extends {@code Application}, and the Tool
 * runs JavaFX from the class path.
 */
public final class Main {
  private Main() {}

  public static void main(String[] args) {
    Application.launch(ToolApplication.class, args);
  }
}
