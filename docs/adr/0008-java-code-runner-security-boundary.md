# ADR-0008：Java Code Runner 的安全边界

- 状态：Accepted
- 日期：2026-08-02

## 决策

未知 Java Script、workspace Operation 和 Worker 永不在 Spring Sidecar JVM 内执行。源码先经 JavaParser policy 与 JavaCompiler 校验，再放入独立、可取消、有限堆的子 JVM；所有 Tool、Artifact 和 Map 能力经版本化双向协议代理。

## 后果

进程树、超时、日志、协议帧、文件数和真实路径都有上限；Tool 回调重新经过统一权限检查。该方案是隔离和最小授权，不是 OS 级强沙箱，部署方可在外层增加容器或低权限账户。
