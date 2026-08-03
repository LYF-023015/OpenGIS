# 剩余边界闭环记录

记录日期：2026-08-03；适用范围：Windows x64 + Java 主运行时。

| 原边界 | 当前结论 | 验收依据 |
|---|---|---|
| 真实第三方 Provider 的账号、配额、地区和模型 | 软件侧认证入口已完成；本机没有真实账号密钥，因此未伪造外部通过记录 | `npm run certify:provider`；成功时生成 `docs/migration/phase5/evidence/provider-certification-<provider>.json`，证据不含密钥 |
| `operation`、`java_script`、`subworkflow` 执行适配器 | 已闭环 | 三类节点均为真实执行；子工作流拥有独立 run、同步结果、取消传播、最大 8 层深度和循环引用拒绝测试 |
| KML、GeoPackage、NetCDF、HDF5、JPEG2000、ASCII Grid、OSM relation | 已闭环到当前声明范围 | KML MultiGeometry/Multi*/内外环；GPKG 全矢量表；四类栅格 pure-Java reader；OSM multipolygon 拼环与洞；真实 HDF5 夹具测试 |
| 无法自动识别的 Maven 许可证 | 按用户决定接受风险，不再阻断发布 | `license-review-decision.json`：`manualReviewRequired=false`、`releaseBlocking=false`；4 项 `NOASSERTION` 仍如实保留 |
| npm 生产依赖 17 项漏洞 | 已闭环 | 删除未使用的 `ai`，升级/调整 Monaco、语法高亮、UUID、PostCSS；`npm audit --omit=dev` 为 0 |

## Provider 凭据认证命令

凭据只通过当前 PowerShell 进程传入，不写入仓库：

```powershell
$env:OPENGIS_CERT_PROVIDER = 'openai'
$env:OPENGIS_CERT_API_KEY = '<真实密钥>'
$env:OPENGIS_CERT_MODEL = '<账号实际可用模型>'
$env:OPENGIS_CERT_REGION = '<账号或 endpoint 地区>'
npm run certify:provider
Remove-Item Env:OPENGIS_CERT_API_KEY
```

自定义或区域 endpoint 可另设 `OPENGIS_CERT_BASE_URL` 和 `OPENGIS_CERT_PROTOCOL`。认证脚本发送真实最小请求，因此在成功时同时证明该时刻凭据可用、配额未耗尽、模型可用以及所记录 endpoint 可达；它不承诺未来配额或厂商状态永远不变。

## 本轮 Windows 验收

- Maven 全模块：405 tests，0 failure/error/skip；Spotless、Checkstyle、Dependency Convergence、Release Dependencies 全通过。
- Renderer：158 tests passed；TypeScript typecheck 通过。
- Java sidecar 全链 smoke、生产构建、Windows unpacked package、包内容审计通过。
- 两轮 packaged startup/upgrade-state smoke 通过，Java 错误为 0，均正常退出。
- Phase 10 审计通过；Python 备份 tag 复用且目录与冻结 tree 完全一致，未删除或改写 Python 备份。
