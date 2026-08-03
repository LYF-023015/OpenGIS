package org.opengis.gis.osm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.opengis.tool.context.CancellationToken;
import tools.jackson.databind.ObjectMapper;

class OsmAdapterTest {
  @Test
  void assemblesRelationWaysIntoPolygonWithHole() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    var response =
        mapper.readTree(
            """
        {"elements":[{"type":"relation","id":7,"tags":{"type":"multipolygon","name":"park"},"members":[
          {"type":"way","role":"outer","geometry":[{"lon":0,"lat":0},{"lon":4,"lat":0},{"lon":4,"lat":4}]},
          {"type":"way","role":"outer","geometry":[{"lon":4,"lat":4},{"lon":0,"lat":4},{"lon":0,"lat":0}]},
          {"type":"way","role":"inner","geometry":[{"lon":1,"lat":1},{"lon":2,"lat":1},{"lon":2,"lat":2},{"lon":1,"lat":2},{"lon":1,"lat":1}]}
        ]}]}
        """);
    var result = new OsmAdapter(mapper).osmToGeoJson(response, new CancellationToken());
    var feature = result.path("features").get(0);
    assertThat(feature.path("geometry").path("type").asText()).isEqualTo("Polygon");
    assertThat(feature.path("geometry").path("coordinates")).hasSize(2);
    assertThat(feature.path("properties").path("_osm_type").asText()).isEqualTo("relation");
  }

  @Test
  void skipsRelationWhenOuterMembersCannotFormClosedRing() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    var response =
        mapper.readTree(
            """
        {"elements":[{"type":"relation","id":8,"tags":{"type":"multipolygon"},"members":[
          {"type":"way","role":"outer","geometry":[{"lon":0,"lat":0},{"lon":1,"lat":0}]}
        ]}]}
        """);
    var result = new OsmAdapter(mapper).osmToGeoJson(response, new CancellationToken());
    assertThat(result.path("features")).isEmpty();
  }
}
