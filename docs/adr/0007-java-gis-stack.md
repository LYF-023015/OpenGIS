# ADR-0007：Java GIS 计算栈采用 GeoTools 35 + JTS

- 状态：Accepted
- 日期：2026-08-02

## 决策

OpenGIS Java GIS 计算面采用 GeoTools 35.0、JTS 1.20.0 和 Java ImageIO-Ext。核心发行不捆绑 GDAL native runtime。

## 原因

- GeoTools 提供统一 DataStore、CRS、Shapefile、GeoPackage 和 GeoTIFF 能力。
- JTS 提供稳定的 topology/geometry 模型。
- 纯 Java 栈能随当前 jlink/JAR 发行，避免用户机器上的 GDAL ABI 差异。
- `opengis-gis` 隔离第三方 GIS API；当前 NetCDF/HDF5、JPEG2000 和 ASCII Grid 均采用 bundled pure-Java reader，不要求 GDAL。

## 后果

- GeoTools 首次 Maven 下载较大，发行包体积增加。
- NetCDF、HDF5/NetCDF-4、JPEG2000 和 ASCII Grid 采用纯 Java reader，并以真实格式夹具验收。
- KML 支持常用 Placemark、MultiGeometry/Multi* 和 Polygon 内外环；KMZ 仍不在当前范围。
- 所有格式状态必须通过能力矩阵公开，不允许把 unsupported 伪装为成功。
