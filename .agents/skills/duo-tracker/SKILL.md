---
name: duo-tracker
description: duo-harness 的工程设施入口——本地 issue tracker（spec 与工单的创建、查找、状态流转）与领域文档（CONTEXT.md 术语表、docs/adr/ 决策记录）的使用规范。用户说"写个 spec"、"建工单"、"拆工单"、"看工单"、"记个 ADR"、"记录架构决策"、"更新术语表"、"issue 放哪"时触发。
---

# duo-harness 工程设施（tracker 与领域文档）

两份配置本体在 docs/agents/ 下——这是 mattpocock 用户级技能（`/to-spec`、`/to-tickets`、code-review）的**固定契约路径：内容可改、位置不可挪**：

- [docs/agents/issue-tracker.md](../../../docs/agents/issue-tracker.md) —— spec 与工单的位置、命名、模板、状态行
- [docs/agents/domain.md](../../../docs/agents/domain.md) —— 术语表与 ADR 的布局、消费与惰性创建规则

## 怎么选操作方式

| 场景 | 做法 |
|---|---|
| L1 大需求成型，要落正式 spec | 提示用户显式调用 `/to-spec`（按 tracker 配置发布到 `.scratch/`） |
| spec 要拆实施工单 | 提示用户显式调用 `/to-tickets` |
| 顺手记一张小工单 / 补状态 | 直接按 [issue-tracker.md](../../../docs/agents/issue-tracker.md) 的模板手写文件，不必走显式命令 |
| 讨论中敲定术语或架构决策 | 按 [domain.md](../../../docs/agents/domain.md) 惰性更新 CONTEXT.md / docs/adr/，或用 `/grill-with-docs`、`/domain-modeling` |
| 查工单进度 / 找 spec | 读 `.scratch/<feature>/` 下的对应文件 |

## 硬约束

- 两个配置文件留在 `docs/agents/`；改内容直接改文件本身，不在任何 skill 里留副本
- `.scratch/` 随 git 入库——code-review 的 spec 探测依赖它在磁盘上
