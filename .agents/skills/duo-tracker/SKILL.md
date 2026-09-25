---
name: duo-tracker
description: 工程设施（issue tracker 与术语表、ADR）。触发：写 spec / 建工单 / 记 ADR / 更新术语表 / issue 放哪。
---

# duo-harness 工程设施（tracker 与领域文档）

两份配置本体在 docs/agents/ 下——这是 mattpocock 用户级技能（`/to-spec`、`/to-tickets`、code-review）的**固定契约路径：内容可改、位置不可挪**：

- `docs/agents/issue-tracker.md` —— spec 与工单的位置、命名、模板、状态行（读它并按其执行）
- `docs/agents/domain.md` —— 术语表与 ADR 的布局、消费与惰性创建规则（读它并按其执行）

## 怎么选操作方式

| 场景 | 做法 |
|---|---|
| L1 大需求成型，要落正式 spec | 出示 `/to-spec`（用户门禁——AGENTS.md 显式命令表内），等用户键入 |
| spec 要拆实施工单 | 出示 `/to-tickets`，等用户键入 |
| 顺手记一张小工单 / 补状态 | 直接按 `docs/agents/issue-tracker.md` 的模板手写文件，不必走显式命令 |
| 讨论中敲定术语或架构决策 | 按 `docs/agents/domain.md` 惰性更新 docs/05-参考/术语表.md / docs/adr/，或用 `/grill-with-docs`、`/domain-modeling` |
| 查工单进度 / 找 spec | 读 `.scratch/<feature>/` 下的对应文件 |

完成判据：所选行的做法已执行，产物路径（spec 文件 / 工单文件 / 术语表或 ADR 条目）已在对话出示。

## 硬约束

- 两个配置文件留在 `docs/agents/`；改内容直接改文件本身，技能里只留指针（单一事实源）
- `.scratch/` 随 git 入库——code-review 的 spec 探测依赖它在磁盘上
