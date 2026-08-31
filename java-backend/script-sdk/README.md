# OpenGIS Script SDK

供用户 Java Script 和 Worker 使用的稳定 API。SDK 不依赖 Spring、GIS 实现或服务端状态。

- `OpenGisScript`：单次脚本入口。
- `ScriptContext`：workspace、run、参数和四类受控客户端。
- `ToolClient`：通过父进程重新进入 ToolRuntime 权限检查。
- `ArtifactClient`：登记 workspace 内产物；`registerPlot` 会让 PNG/JPEG/SVG 在已连接的聊天界面显示。
- `MapClient`：发送受控地图事件。
- `ProgressEmitter`：发送结构化进度。

协议版本为 `1.0`，父子进程使用 JSONL。
