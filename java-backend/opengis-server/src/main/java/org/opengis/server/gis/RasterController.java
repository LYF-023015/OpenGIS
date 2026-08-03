package org.opengis.server.gis;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import java.util.Map;
import org.opengis.gis.raster.RasterContracts;
import org.opengis.gis.raster.RasterRegistration;
import org.opengis.gis.raster.RasterService;
import org.opengis.tool.api.ToolException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Existing Python-compatible raster metadata/style/XYZ HTTP surface. */
@RestController
@RequestMapping("/api/rasters")
public final class RasterController {
  private final RasterService rasters;

  public RasterController(RasterService rasters) {
    this.rasters = rasters;
  }

  @GetMapping("/{rasterId}/metadata")
  public Map<String, Object> metadata(@PathVariable String rasterId) {
    return response(find(rasterId));
  }

  @PostMapping("/{rasterId}/style")
  public Map<String, Object> style(
      @PathVariable String rasterId, @RequestBody(required = false) Map<String, Object> style) {
    try {
      return response(rasters.updateStyle(rasterId, style == null ? Map.of() : style));
    } catch (ToolException exception) {
      throw status(exception);
    }
  }

  @GetMapping(value = "/{rasterId}/tiles/{z}/{x}/{y}.png", produces = MediaType.IMAGE_PNG_VALUE)
  public ResponseEntity<byte[]> tile(
      @PathVariable String rasterId,
      @PathVariable int z,
      @PathVariable int x,
      @PathVariable int y) {
    try {
      return ResponseEntity.ok()
          .cacheControl(CacheControl.noCache())
          .header(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_PNG_VALUE)
          .body(rasters.renderTile(rasterId, z, x, y));
    } catch (ToolException exception) {
      throw status(exception);
    }
  }

  private RasterRegistration find(String id) {
    try {
      return rasters.get(id);
    } catch (ToolException exception) {
      throw status(exception);
    }
  }

  private static Map<String, Object> response(RasterRegistration registration) {
    return RasterContracts.registration(registration);
  }

  private static ResponseStatusException status(ToolException exception) {
    return new ResponseStatusException(
        "raster_not_found".equals(exception.code()) ? NOT_FOUND : BAD_REQUEST,
        exception.getMessage(),
        exception);
  }
}
