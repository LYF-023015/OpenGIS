# opengis-gis

Phase 7 的 Java GIS 计算面，负责格式 IO、CRS、JTS Geometry、GeoTIFF 瓦片以及 OSM/QGIS/Datasource 适配器。

## 包结构

- `model`：格式与统一 metadata 契约。
- `io`：workspace 路径和符号链接安全边界。
- `vector`：GeoJSON、CSV、SHP、GPKG、KML loader。
- `crs`：GeoTools CRS 与 GeoJSON/JTS 变换。
- `geometry`：有点数上限的 JTS 操作。
- `raster`：GeoTIFF metadata、style、revision、XYZ tile 和 LRU cache。
- `osm`、`qgis`、`datasource`：外部 GIS adapter。
- `tool`：向统一 `ToolRegistry` 贡献 GIS 工具。

依赖方向保持为 `opengis-gis -> common/framework/platform/tool`，HTTP 和 Spring Controller 只存在于 `opengis-server`。

详细能力矩阵和学习路线见 [`docs/migration/phase7/README.md`](../../docs/migration/phase7/README.md)。
