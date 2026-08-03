# ADR-0009：Operation v2 与 workspace 扩展模型

- 状态：Accepted
- 日期：2026-08-02

## 决策

Operation v2 使用标准 Java 源码目录、版本化 manifest、不可变 revision 快照和 checksum 运行记录。内置可信算法进程内执行，自定义 Operation 复用 Java Code Runner。Python v1 仅只读识别并生成迁移模板。

## 后果

create/edit/validate/run/promote 具有可审计来源，历史运行不会被后续源码编辑篡改语义。旧 Python Operation 不会在 Java-only 模式中意外执行。
