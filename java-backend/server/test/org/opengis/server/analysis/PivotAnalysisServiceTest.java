/** 文件职责：server 后端领域：验证对应功能的行为与边界。 */
package org.opengis.server.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PivotAnalysisServiceTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final PivotAnalysisService service = new PivotAnalysisService();

  @Test
  void analyzesStructuredRowsWithoutExecutableSource() throws Exception {
    var request =
        mapper.readTree(
            """
            {
              "kind":"table",
              "columns":["value","category"],
              "rows":[
                {"value":1,"category":"a"},
                {"value":2,"category":"a"},
                {"value":3,"category":"b"},
                {"value":4,"category":"b"}
              ],
              "total_rows":4
            }
            """);

    var result = service.analyze(request);

    assertThat(result.get("stats").toString()).contains("value", "category", "number");
    assertThat(result.get("distributions").toString()).contains("probability");
    assertThat(result.get("summary").toString()).contains("Java analyzed 4 / 4 records");
  }

  @Test
  void limitsRowsAndColumnsAndHandlesRasterContract() throws Exception {
    var request =
        mapper.readTree(
            """
            {"kind":"raster","raster_rows":[
              {"band":"band_1","valid_pixels":10,"nodata_pixels":2,"min":1,"max":9,"mean":4.5}
            ]}
            """);

    var result = service.analyze(request);

    assertThat(result.get("stats").toString()).contains("band_1", "nullCount=2");
    assertThat(result.get("summary").toString()).contains("raster band");
  }
}
