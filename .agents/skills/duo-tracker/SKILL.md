---
name: duo-tracker
description: duo-harness 的工程设施入口——本地 issue tracker（spec 与工单的创建、查找、状态流转）与领域文档（docs/05-参考/术语表.md 术语表、docs/adr/ 决策记录）的使用规范。用户说"写个 spec"、"建工单"、"拆工单"、"看工单"、"记个 ADR"、"记录架构决策"、"更新术语表"、"issue 放哪"时触发。
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
| 讨论中敲定术语或架构决策 | 按 [domain.md](../../../docs/agents/domain.md) 惰性更新 docs/05-参考/术语表.md / docs/adr/，或用 `/grill-with-docs`、`/domain-modeling` |
| 查工单进度 / 找 spec | 读 `.scratch/<feature>/` 下的对应文件 |

## 硬约束

- 两个配置文件留在 `docs/agents/`；改内容直接改文件本身，不在任何 skill 里留副本
- `.scratch/` 随 git 入库——code-review 的 spec 探测依赖它在磁盘上

## 任务结束后：自我进化

本次工程设施任务完成后,调用 `duo-skill-evolution` 进行复盘：

1. 总结本次执行的关键步骤、遇到的问题、用户反馈
2. 评估是否存在改进空间（spec/工单质量、术语表/ADR 更新准确性等）
3. 若有价值的经验教训，触发进化

调用方式：在任务收口后说"现在调用 duo-skill-evolution 复盘本次执行"，提供：
- Skill 名称：duo-tracker
- 任务摘要：操作类型（建 spec/工单/ADR/术语表）、生成的文档数量、关键内容
- 执行过程关键点：spec/工单模板是否正确应用、术语表/ADR 是否准确更新、状态流转是否合规、是否有遗漏字段
- 用户反馈：用户对 spec/工单质量、术语表准确性、ADR 完整性的反馈
- 自我感知：流程中是否有遗漏环节、模板应用是否准确、可优化点

## 经验参考

执行前建议阅读 `references/experience.md`，了解常见边界情况和最佳实践；任务结束后的复盘经验同样追加到该文件。
