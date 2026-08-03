# OpenGIS Worker

`opengis-worker` 管理工作区中的常驻 Java Worker。Worker 包位于：

```text
<workspace>/worker/<slug>-<worker-id>/
├── manifest.json
├── config.json
├── metadata.json
├── README.md
└── src/main/java/.../Worker.java
```

入口类实现 `opengis-script-sdk` 中的 `OpenGisWorker`。Manager 负责
create/start/get/list/wait/pause/restart/delete、启动恢复、最多两个并发 Worker、最多三次有限退避、资源采样、日志与进程树清理。

Worker 在独立子 JVM 中运行。Tool 回调必须回到服务端 `ToolRuntime` 重新做 schema 和权限检查；动态地图只允许 `rpc.ui.map.*`，每个图层的 sequence 必须严格递增。

旧 Python Worker 只读扫描，不由 Java 执行。`WorkerMigrationService` 会生成依赖、环境变量、网络/RPC/动态图层风险报告和 Java 模板，交由用户人工确认迁移。

测试：

```powershell
mvn -pl opengis-worker -am test
```
