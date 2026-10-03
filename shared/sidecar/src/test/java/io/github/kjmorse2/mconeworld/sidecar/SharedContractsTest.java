// SPDX-License-Identifier: MIT
package io.github.kjmorse2.mconeworld.sidecar;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kjmorse2.mconeworld.protocol.Capability;
import io.github.kjmorse2.mconeworld.protocol.ProtocolChannels;
import io.github.kjmorse2.mconeworld.protocol.ProtocolSupport;
import io.github.kjmorse2.mconeworld.protocol.codec.ProtocolCodec;
import io.github.kjmorse2.mconeworld.sidecar.index.ChunkIndexExport;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonReadLimits;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonReader;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonArray;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonNumber;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonObject;
import io.github.kjmorse2.mconeworld.sidecar.internal.json.JsonValue.JsonString;
import io.github.kjmorse2.mconeworld.sidecar.manifest.ManifestCodec;
import io.github.kjmorse2.mconeworld.sidecar.operation.OperationPlanCodec;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * {@code docs/compatibility/shared-contracts.json} must state exactly the format and protocol
 * versions the code implements, so platform projects can read it instead of the code. Constants
 * that are package-private are read reflectively; this test is their only outside reader.
 */
class SharedContractsTest {
  private static final Path CONTRACTS = Path.of("../../docs/compatibility/shared-contracts.json");

  private static JsonObject contracts() throws Exception {
    return (JsonObject)
        new JsonReader(new JsonReadLimits(1 << 16, 8, 1024, 256, 64))
            .read(Files.readAllBytes(CONTRACTS));
  }

  private static Object constant(String className, String field) throws Exception {
    Field f =
        Class.forName("io.github.kjmorse2.mconeworld.sidecar." + className).getDeclaredField(field);
    f.setAccessible(true);
    return f.get(null);
  }

  @Test
  void formatVersionsMatchTheCode() throws Exception {
    Map<String, String> documented = new TreeMap<>();
    for (JsonValue v : ((JsonArray) contracts().members().get("formats")).elements()) {
      Map<String, JsonValue> m = ((JsonObject) v).members();
      documented.put(((JsonString) m.get("name")).value(), ((JsonString) m.get("version")).value());
      assertThat(Path.of("../..", ((JsonString) m.get("spec")).value())).exists();
    }
    Map<String, String> code = new TreeMap<>();
    code.put(ManifestCodec.FORMAT, ManifestCodec.CURRENT.toString());
    code.put(OperationPlanCodec.FORMAT, OperationPlanCodec.CURRENT.toString());
    code.put(ChunkIndexExport.FORMAT, ChunkIndexExport.VERSION);
    code.put(
        io.github.kjmorse2.mconeworld.sidecar.backup.BackupManifestCodec.FORMAT,
        io.github.kjmorse2.mconeworld.sidecar.backup.BackupManifestCodec.VERSION);
    code.put(
        (String) constant("operation.JournalEntryCodec", "FORMAT"),
        constant("operation.JournalEntryCodec", "CURRENT").toString());
    code.put(
        (String) constant("index.IndexFormat", "FORMAT"),
        constant("index.IndexFormat", "MAJOR") + "." + constant("index.IndexFormat", "MINOR"));
    code.put(
        (String) constant("storage.LockFileFormat", "FORMAT"),
        constant("storage.LockFileFormat", "VERSION").toString());
    code.put(
        (String) constant("storage.TransactionRecord", "FORMAT"),
        constant("storage.TransactionRecord", "VERSION").toString());
    // The report's version is a literal in its encoder (1), covered by the migration report tests.
    code.put((String) constant("migration.MigrationReportFormat", "FORMAT"), "1");
    assertThat(documented).isEqualTo(code);
  }

  @Test
  void protocolMatchesTheCode() throws Exception {
    Map<String, JsonValue> p = ((JsonObject) contracts().members().get("protocol")).members();
    assertThat(((JsonNumber) p.get("layout")).value()).isEqualTo(ProtocolCodec.LAYOUT_V1);
    Map<String, JsonValue> versions = ((JsonObject) p.get("versions")).members();
    assertThat(((JsonNumber) versions.get("min")).value())
        .isEqualTo(ProtocolSupport.CURRENT.versions().min());
    assertThat(((JsonNumber) versions.get("max")).value())
        .isEqualTo(ProtocolSupport.CURRENT.versions().max());
    assertThat(((JsonArray) p.get("channels")).elements())
        .containsExactly(
            new JsonString(ProtocolChannels.CONTEXT), new JsonString(ProtocolChannels.HELLO));
    Map<String, JsonValue> capabilities = ((JsonObject) p.get("capabilities")).members();
    assertThat(capabilities.keySet())
        .containsExactlyElementsOf(
            ProtocolSupport.CURRENT.capabilities().values().stream().map(Capability::id).toList());
    for (Map.Entry<String, JsonValue> c : capabilities.entrySet()) {
      assertThat(((JsonNumber) c.getValue()).value())
          .isEqualTo(ProtocolSupport.sinceVersion(new Capability(c.getKey())));
    }
  }

  @Test
  void noMinecraftSupportIsClaimedYet() throws Exception {
    // Root AGENTS.md §5: a support claim needs a recorded validation run; none exists before Paper
    // and Fabric integration.
    assertThat(((JsonArray) contracts().members().get("minecraftSupportClaims")).elements())
        .isEmpty();
  }
}
