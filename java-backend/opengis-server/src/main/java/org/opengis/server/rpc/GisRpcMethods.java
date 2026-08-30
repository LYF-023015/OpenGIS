package org.opengis.server.rpc;

import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import java.util.Map;
import org.opengis.gis.crs.CrsService;
import org.opengis.gis.io.WorkspaceGisPaths;
import org.opengis.gis.model.GisFormat;
import org.opengis.gis.raster.RasterContracts;
import org.opengis.gis.raster.RasterRegistration;
import org.opengis.gis.raster.RasterService;
import org.opengis.gis.raster.RasterStyle;
import org.opengis.gis.vector.VectorLoader;
import org.opengis.platform.persistence.JsonTypeReferences;
import org.opengis.tool.context.CancellationToken;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Direct Phase 7 GIS RPCs for UI diagnostics and raster registration. */
@Component
public final class GisRpcMethods {
  private final RpcMethodRegistry registry;
  private final RasterService rasters;
  private final ObjectMapper mapper;
  private final VectorLoader vectors;

  public GisRpcMethods(RpcMethodRegistry registry, RasterService rasters, ObjectMapper mapper) {
    this.registry = registry;
    this.rasters = rasters;
    this.mapper = mapper;
    this.vectors = new VectorLoader(mapper, new CrsService());
  }

  @PostConstruct
  void registerMethods() {
    registry.registerOrReplace("rpc.gis.capabilities", this::capabilities);
    registry.registerOrReplace("rpc.gis.file.inspect", this::inspect);
    registry.registerOrReplace("rpc.gis.vector.load", this::loadVector);
    registry.registerOrReplace("rpc.gis.raster.register", this::registerRaster);
    registry.registerOrReplace("rpc.gis.raster.cache_stats", params -> rasters.cacheStats());
  }

  private Object capabilities(JsonNode params) {
    return Map.of(
        "runtime",
        "java",
        "geotools_version",
        "35.0",
        "formats",
        GisFormat.values(),
        "raster_cache",
        rasters.cacheStats());
  }

  private Object inspect(JsonNode params) {
    Path workspace = workspace(params);
    Path path = WorkspaceGisPaths.input(workspace, params.path("path").asString());
    GisFormat format =
        GisFormat.detect(path)
            .orElseThrow(() -> new IllegalArgumentException("Unsupported GIS format"));
    if ("vector".equals(format.kind())) {
      return vectors.metadata(path, limit(params), new CancellationToken());
    }
    if ("raster".equals(format.kind())) return rasters.inspect(path, new CancellationToken());
    return Map.of("format", format, "status", format.support());
  }

  private Object loadVector(JsonNode params) {
    Path workspace = workspace(params);
    Path path = WorkspaceGisPaths.input(workspace, params.path("path").asString());
    return vectors.load(path, limit(params), new CancellationToken());
  }

  private Object registerRaster(JsonNode params) {
    Path workspace = workspace(params);
    Path path = WorkspaceGisPaths.input(workspace, params.path("path").asString());
    Map<String, Object> style =
        params.path("style").isObject()
            ? mapper.convertValue(params.path("style"), JsonTypeReferences.STRING_OBJECT_MAP)
            : Map.of();
    RasterRegistration registration =
        rasters.register(
            path, RasterStyle.merge(RasterStyle.defaults(), style), new CancellationToken());
    return Map.of(
        "status", "ok",
        "raster_id", registration.rasterId(),
        "style_revision", registration.styleRevision(),
        "info", RasterContracts.info(registration.info()),
        "tile_url", "/api/rasters/" + registration.rasterId() + "/tiles/{z}/{x}/{y}.png?rev=0");
  }

  private static Path workspace(JsonNode params) {
    String value = params.path("workspace_path").asString("");
    if (value.isBlank()) throw new IllegalArgumentException("workspace_path is required");
    return Path.of(value).toAbsolutePath().normalize();
  }

  private static int limit(JsonNode params) {
    return Math.max(1, Math.min(params.path("max_features").asInt(100_000), 100_000));
  }
}
