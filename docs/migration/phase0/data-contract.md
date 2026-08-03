# Phase 0 GIS and `.opengis` data contract

## GIS fixtures

The shared fixture set includes:

| Fixture | Purpose |
|---|---|
| `points_wgs84.geojson` | Six Chinese city points with text, numeric, nullable and weight fields. |
| `lines_wgs84.geojson` | Two line features. |
| `polygons_wgs84.geojson` | Two valid polygon features. |
| `edge_cases_wgs84.geojson` | Null geometry, invalid self-intersecting polygon, Unicode and high-longitude/high-latitude point. |
| `points_wgs84.csv` | UTF-8 BOM CSV with Chinese field/value content. |
| `points_shapefile/` | Complete `.shp/.shx/.dbf/.prj/.cpg` component set. |
| `points.gpkg` | GeoPackage point layer. |
| `small_raster_3857.tif` | 32x32 float GeoTIFF, EPSG:3857, nodata `-9999`. |
| `scale/points_10000.geojson.gz` | Deterministic medium fixture used for 10k correctness/performance comparison. |
| `scale/points_100000.geojson.gz` | Deterministic large fixture used for 100k streaming/memory comparison. |

The Python golden summary records feature counts, geometry types, null/valid/invalid counts, CRS, bbox, fields, raster transform facts and statistics.

## Operation baseline

The same point fixture is used for:

- GeoJSON to CSV conversion;
- deterministic KMeans with two clusters and random state 42;
- Gaussian kernel density with fixed bandwidth/cell size and a 10,000-cell ceiling.

Approved initial tolerances:

- bbox absolute error: `1e-6`;
- area/length relative error: `1e-6`;
- raster statistic relative error: `1e-5`;
- cluster numeric labels may be permuted, but partition membership, cluster count and noise count must match.

## Anonymous `.opengis` fixture

`test/phase0/fixtures/opengis-workspace/.opengis` is synthetic. It includes:

- sessions and inbox;
- profiles and permission rules;
- context and workflow;
- artifact index;
- a complete run directory with all current JSONL streams;
- a legacy Python Operation manifest and harmless sample entrypoint.
- titled-conversation state, legacy and structured memory;
- workflow step output, Operation run files and latest index;
- workspace skill plus skill-source configuration.

`.opengis/schema-inventory.json` lists 16 workspace-local persistent/cache families and maps each to its current Python owner. The raster cache is explicitly classified as regenerable rather than a round-trip migration target. Global `~/.opengis/user_instructions.md` is tracked separately in the migration matrix because it does not belong to a workspace fixture.

All filesystem references use `${WORKSPACE}`. The validator rejects home-directory markers, API-key markers and common secret prefixes. Current Python Readers successfully load the Session, AgentProfile, PermissionRule, Workflow and RunArchive fixture.

## Compatibility rule

Java readers must read these files before Java writers are enabled. Any Java writer test must use a temporary copy, never the golden fixture in place. A schema upgrade must be versioned, idempotent and retain the original fixture for backward-compatibility testing.
