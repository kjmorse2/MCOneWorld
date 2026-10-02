// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.app;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

/** The MCOneWorld Tool application: one main window. Screens are added from milestone T10. */
public final class ToolApplication extends Application {
  /** Title of the main window. */
  public static final String TITLE = "MCOneWorld Tool";

  @Override
  public void start(Stage stage) {
    stage.setTitle(TITLE);
    stage.setScene(createScene());
    stage.show();
  }

  /** The main window's scene, separate from {@link #start} so tests can build it on any stage. */
  static Scene createScene() {
    BorderPane root = new BorderPane(new Label("No world open."));
    root.setId("main");
    return new Scene(root, 960, 640);
  }
}
