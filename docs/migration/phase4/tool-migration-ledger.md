# Phase 4 工具迁移台账

Python 基线共 89 个工具。Java Phase 4 激活 62 个，另外 27 个保留给拥有真实底层引擎的后续阶段。

## 已激活（62）

### 文件与系统

`read_file`、`list_directory`、`file_exists`、`glob`、`grep`、`write_file`、`edit_file`、`create_directory`、`copy_file`、`move_file`、`delete_file`、`bash`、`webfetch`。

### Renderer 地图、布局与图像

`add_layer`、`remove_layer`、`list_layers`、`get_layer`、`get_map_state`、`query_features`、`zoom_to_layer`、`fly_to`、`set_map_camera`、`enter_3d_view`、`exit_3d_view`、`set_basemap`、`set_basemap_visibility`、`update_layer_style`、`set_graduated_style`、`set_categorized_style`、`set_extrusion_style`、`set_layer_visual_variables`、`set_layer_filter`、`set_layer_label`、`highlight_features`、`set_layer_order`、`update_legend_spec`、`add_raster`、`get_raster_info`、`set_raster_style`、`layout_get_state`、`layout_set_page`、`layout_add_element`、`layout_update_frame`、`layout_update_style`、`layout_update_props`、`layout_update_map_view`、`layout_capture_map`、`layout_remove_element`、`layout_export`、`interactive_snapshot`、`save_plot`。

### 报告、学术、调试、Script、Skill

`academic_polish`、`academic_translate`、`academic_grammar_check`、`generate_abstract`、`format_references`、`write_report_section`、`debug_agent_context`、`list_scripts`、`read_script`、`load_skill`。

### GIS 边缘能力

`csv_to_geojson`。

## 后续阶段拥有者（27）

| 工具 | 状态 | 真实拥有者与原因 |
|---|---|---|
| `run_subagent`、`run_subagents` | deferred | Phase 5；需要 provider-neutral LLM、Agent Loop、child context 和取消传播 |
| `create_workflow`、`update_plan` | deferred | Phase 6；需要 Workflow v2、DAG、Session/MessagePart 投影 |
| `datasource_call`、`osm_call`、`qgis_call` | deferred | Phase 7；需要真实 HTTP/socket adapter、超时、重试、限流与 GIS 容差 |
| `copy_operation_to_workspace`、`get_operation`、`list_operations`、`create_operation`、`edit_operation`、`validate_operation`、`run_operation`、`promote_script_to_operation` | deferred | Phase 8A；需要 manifest v2、Java 编译/隔离、revision/checksum |
| `export_report_pdf` | deferred | Phase 8B/发布验证；需要跨平台中文字体、分页和 PDF 渲染验收 |
| `start_worker`、`start_dynamic_map_worker`、`get_worker`、`list_workers`、`wait_worker_update`、`restart_worker`、`pause_worker`、`delete_worker` | deferred | Phase 8C；需要 Java Worker SPI、进程恢复、日志和动态地图协议 |
| `list_extensions` | deferred | Phase 8/9；需要 Java extension manifest 与 UI 去 Python 化 |
| `update_user_instructions` | deferred | Phase 5/9；需要统一用户偏好 Store 与上下文投影 |
| `websearch` | deferred | Phase 5/7；需要明确搜索 Provider、凭据、引用、限流和失败语义 |

“deferred” 表示没有注册到 Java `ToolRegistry`，调用会明确返回 `tool_not_found`；它不是成功占位，也不会悄悄回退到 Python 执行有副作用操作。
