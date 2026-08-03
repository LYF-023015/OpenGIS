# OpenGIS Code Runtime

Phase 8B 的服务端执行层：JavaParser 静态检查、JavaCompiler 编译、Maven 依赖治理、独立子 JVM、JSONL 双向协议、输出/资源配额和 Script Archive。

未知代码永远不在 Spring Sidecar JVM 内执行；这里提供的是进程隔离和权限代理，不宣称是操作系统级强沙箱。
