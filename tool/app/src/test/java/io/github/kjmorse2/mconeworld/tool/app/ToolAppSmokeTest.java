// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kjmorse2.mconeworld.fixtures.ModuleContract;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Proves tool:app is null-marked, compiled for Java 25, and that its main window starts headless
 * (Monocle, configured by the test task), so UI tests need no display.
 */
class ToolAppSmokeTest {

  @BeforeAll
  static void startJavaFx() {
    try {
      Platform.startup(() -> {});
    } catch (IllegalStateException alreadyStarted) {
      // Another test class started the toolkit; it can only be started once per JVM.
    }
  }

  @Test
  void rootPackageSatisfiesTheToolContract() {
    ModuleContract.assertPackageContract(
        "io.github.kjmorse2.mconeworld.tool.app", ModuleContract.JAVA_25_CLASS_FILE_MAJOR);
  }

  @Test
  void theMainWindowOpensHeadless() throws Exception {
    CompletableFuture<String> shown = new CompletableFuture<>();
    Platform.runLater(
        () -> {
          try {
            Stage stage = new Stage();
            new ToolApplication().start(stage);
            Label message = (Label) stage.getScene().lookup("#main").lookup(".label");
            shown.complete(
                stage.getTitle() + " | " + message.getText() + " | " + stage.isShowing());
            stage.close();
          } catch (RuntimeException e) {
            shown.completeExceptionally(e);
          }
        });
    assertThat(shown.get(30, TimeUnit.SECONDS))
        .isEqualTo("MCOneWorld Tool | No world open. | true");
  }
}
