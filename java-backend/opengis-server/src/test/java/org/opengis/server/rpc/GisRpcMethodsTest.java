package org.opengis.server.rpc;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opengis.gis.crs.CrsService;
import org.opengis.gis.raster.RasterService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class GisRpcMethodsTest {
  @Test
  void registersAndInspectsVectorThroughJavaRpc(@TempDir Path workspace) throws Exception {
    Path source = workspace.resolve("point.geojson");
    Files.writeString(
        source,
        """
        {"type":"FeatureCollection","features":[
          {"type":"Feature","geometry":{"type":"Point","coordinates":[121.4,31.2]},"properties":{"name":"A"}}
        ]}
        """,
        StandardCharsets.UTF_8);
    ObjectMapper mapper = new ObjectMapper();
    RpcMethodRegistry registry = new RpcMethodRegistry();
    RasterService rasters = new RasterService(new CrsService());
    new GisRpcMethods(registry, rasters, mapper).registerMethods();

    assertThat(
            java.util.List.of(
                "rpc.gis.capabilities",
                "rpc.gis.file.inspect",
                "rpc.gis.vector.load",
                "rpc.gis.raster.register",
                "rpc.gis.raster.cache_stats"))
        .allMatch(method -> registry.find(method).isPresent());
    JsonNode result =
        mapper.valueToTree(
            registry
                .find("rpc.gis.file.inspect")
                .orElseThrow()
                .handle(
                    mapper
                        .createObjectNode()
                        .put("workspace_path", workspace.toString())
                        .put("path", "point.geojson")));
    assertThat(result.path("formatName").asString()).isEqualTo("GeoJSON");
    assertThat(result.path("featureCount").asLong()).isEqualTo(1);
  }
}
