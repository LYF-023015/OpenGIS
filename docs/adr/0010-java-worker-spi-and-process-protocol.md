# ADR-0010：Java Worker SPI 与进程协议

- 状态：Accepted
- 日期：2026-08-02

## 决策

常驻扩展实现稳定的 `OpenGisWorker` SPI，并复用 Script Runner 子 JVM 协议。Worker package、metadata、日志和配置由 workspace Manager 管理；动态地图帧按 layer_id 和递增 sequence 校验。

## 后果

默认并发数为 2，自动恢复采用最多 3 次有限退避。后端重启不会猜测旧 PID 仍然可用，而是把陈旧运行态修复为 paused。Python Worker 只生成迁移报告与 Java 模板。
