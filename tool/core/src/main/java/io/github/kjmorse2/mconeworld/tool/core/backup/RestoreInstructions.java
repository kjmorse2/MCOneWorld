// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.tool.core.backup;

import io.github.kjmorse2.mconeworld.domain.BackupId;
import io.github.kjmorse2.mconeworld.tool.core.session.ToolLocations;
import java.nio.file.Path;
import java.util.List;

/**
 * The exact manual steps to restore a full backup, until restore is automated (ADR 0012). They
 * never delete anything: the current world and sidecar are moved aside first. The paths are this
 * machine's and are only displayed, never stored.
 */
public final class RestoreInstructions {
  private RestoreInstructions() {}

  /** The steps for restoring {@code backup} into {@code locations}. */
  public static List<String> forFullBackup(ToolLocations locations, BackupId backup) {
    Path world = locations.worldDir();
    Path sidecar = locations.sidecarDir();
    Path source = sidecar.resolve("backups").resolve(backup.toString());
    return List.of(
        "1. Stop the Minecraft server and close MCOneWorld Tool.",
        "2. Verify the backup first: in the Tool's Backups panel, choose Verify for "
            + backup
            + ".",
        "3. Rename the current world folder "
            + world
            + " to "
            + world.getFileName()
            + ".before-restore (do not delete it).",
        "4. Rename the current sidecar folder "
            + sidecar
            + " to "
            + sidecar.getFileName()
            + ".before-restore.",
        "5. Create an empty folder "
            + world
            + " and copy everything inside "
            + sidecar.getFileName()
            + ".before-restore/backups/"
            + backup
            + "/world/ into it, keeping every sub-folder.",
        "6. Create an empty folder "
            + sidecar
            + " and copy everything inside "
            + sidecar.getFileName()
            + ".before-restore/backups/"
            + backup
            + "/sidecar/ into it.",
        "7. Copy the folder "
            + sidecar.getFileName()
            + ".before-restore/backups/ into "
            + sidecar
            + " so that every backup, including this one, is kept.",
        "8. Open the world in the Tool: the status should show the restored sidecar with no findings.",
        "9. Only after checking the restored world in game, delete the .before-restore folders. (Backup source: "
            + source
            + ")");
  }
}
