package org.opengis.server.rpc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ProtocolSchemaTest {
  @Test
  void canonicalSchemaFreezesPythonAndTypeScriptProtocolValues() throws IOException {
    try (InputStream stream =
        getClass()
            .getClassLoader()
            .getResourceAsStream("opengis/protocol/opengis-protocol-3.0.schema.json")) {
      assertThat(stream).isNotNull();
      JsonNode schema = new ObjectMapper().readTree(stream);
      JsonNode definitions = schema.path("$defs");

      assertThat(definitions.path("protocolVersion").path("const").asString()).isEqualTo("3.0");
      assertThat(definitions.path("jsonRpcVersion").path("const").asString()).isEqualTo("2.0");
      assertThat(definitions.path("bbox").path("minItems").asInt()).isEqualTo(4);
      assertThat(definitions.path("bbox").path("maxItems").asInt()).isEqualTo(4);
      assertThat(definitions.path("geometryType").path("enum")).hasSize(8);
      assertThat(definitions.path("layerSource").path("enum")).hasSize(4);
      assertThat(definitions.path("layerStyleType").path("enum")).hasSize(5);
    }
  }
}
