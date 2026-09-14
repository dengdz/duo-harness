# M9 Spec：上下文治理四件套——token 计量 / 工具结果修剪 / compaction / spill

> **验收记录**：2026-09-14 用户验收通过（bf70 会话：透明性/HITL/日志完整性 ✓；sp00 合成会话：spill 真实触发 ✓；全量回归 245 例全绿）。随 0.4.0 版本收口合并回 main。

> ADR-0007 v3 具名候选期的第一个整期（"上下文治理四件套应作一整期"）。参照物：DSH compaction/spill/pruner 机制（[docs/research/DSH/核心功能全景.md](../../docs/research/DSH/核心功能全景.md) 41-47 行）。**0.4.0 分支**（分支纪律第二次执行，分支已建）。
>
> **grill 裁定**（2026-09-14，用户全部采纳）：token 计量用本地估算不捕 provider usage；治理发生在**投影读侧**不写日志（事件溯源语义不动）；四件套一期做完（DSH 实测 pruner 前置可能免掉摘要调用，拆开各期不完整）；spill 落会话目录、定位符现阶段主要服务人类。

## Problem Statement

agent 每轮把**全量**会话投影 + **完整**工具结果塞回模型：几轮工具调用后会话就几百行（实测 M8 验收会话 549 行），长会话必然撑爆上下文窗口或显著劣化质量与成本。DSH 靠四件套治理（compaction / token 计量 / 工具结果修剪 / spill）支撑长任务；duo-harness 从 M4 起没有任何上下文治理，这是"能用的 demo"到"天天能用的工具"之间最大的一道坎。

## Solution

治理发生在**模型上下文构造时刻**（读侧视图），JSONL 日志永远保持完整事实：

1. **token 计量**：本地估算（字符÷系数），零依赖、provider 无关
2. **工具结果修剪**：超长工具结果头尾修剪，请求构造时生效
3. **compaction**：估算超阈值时把远端历史折叠为结构化摘要，近端原文保留
4. **spill**：超大工具结果落盘卸载，给模型头尾预览 + 定位符

顺序即管线：spill 先卸大结果 → 修剪次长结果 → 计量判断 → 超阈值触发 compaction。每件独立可关（组装自由），agent 装配层按需组合。

## User Stories

1. As a 长会话使用者, I want 会话变长后 agent 仍能正常回答而不爆上下文, so that 我可以连续工作一整天不开新会话
2. As a 长会话使用者, I want 早期对话被折叠成摘要后 agent 仍记得主要结论, so that 压缩不等于失忆
3. As a 使用大结果工具的人, I want 超长工具结果被修剪头尾而非完整塞入, so that 一次 cat 大文件不毁掉整个会话
4. As a 使用大结果工具的人, I want 超大结果被卸载到磁盘并给模型留预览与定位符, so that 内容可找回且上下文不膨胀
5. As a 排查问题的人, I want JSONL 日志始终完整未被修剪, so that 事后回放与审计看到的是真实历史
6. As a 装配者, I want 每件治理能力独立开关（不挂载即零残留）, so that 我按场景选择治理强度
7. As a 装配者, I want 阈值可配置（触发比例 / 修剪长度 / spill 上限）, so that 不同模型窗口大小都能适配
8. As a 框架维护者, I want 计量用本地估算不依赖 provider, so that 机制 provider 无关且零新依赖
9. As a 框架维护者, I want 治理只发生在投影读侧, so that 事件溯源的回放/审计语义不被破坏
10. As a 框架维护者, I want compaction 摘要本身也是普通投影产物, so that 摘要机制不需要特殊持久化
11. As a Web 面使用者, I want 上下文治理对界面透明（卡片/回放照旧）, so that 界面不需要跟着改
12. As a 排查问题的人, I want 被修剪/卸载/压缩在可观测输出中留痕, so that 我知道 agent"看到"的不是全部原文

## Implementation Decisions

- **挂载点（唯一 seam）**：`ToolCallingAgent.buildRequest()`——今日它调 `session.deriveMessages()` 后直转 ChatMessage；治理作为投影后、请求前的**上下文转换管线**插入。会话域（session 模块）零改动——治理属 agent 编排关注点
- **token 计量**：`ContextBudget`（agent 域新类）——`estimate(text) = 字符数 / 4` 取整上浮；对全部消息文本 + system 提示求和；本地纯函数，无 IO 无依赖
- **工具结果修剪（pruner）**：投影后的 tool 消息超 `pruneThresholdChars`（默认 8_000，对齐 DSH）→ 替换为"头 2K + `\n…[修剪 N 字符]…\n` + 尾 1K"；完整原文仍在 JSONL
- **compaction**：估算超 `thresholdRatio × 模型窗口`（默认 0.8 × 128K，窗口可配）→ 远端消息（保留近端 20% 原文，对齐 DSH 保留 16% 量级）折叠为一段结构化摘要消息（固定小节：主要请求 / 关键结论 / 已做操作 / 未决事项），作为首条 user 侧上下文注入；摘要由 LLM 生成（复用 LlmAdapter 直答形态，非流式、单次调用）；近端原文永不折叠
- **spill**：工具结果超 `maxInlineChars`（默认 50K 对齐 DSH 50KB 量级）→ 原文写 `~/.duo/agent-sessions/<会话id>/spill/<序号>-<toolName>.txt`，给模型的消息 = 头尾预览 + 定位符路径；写失败保留原结果（治理永不丢数据）；spill 在 pruner 之前（能卸载就不修剪）
- **配置**：agent 装配层可选项（AgentRepl / Web 装配以默认值启用），全部阈值常量先行、yml 化延后——避免为四个数字动 Boot config 面板
- **可观测**：发生修剪/卸载/压缩时经现有 `run/error` 之外的常规日志输出一行摘要（字符数变化），不进会话事件流
- **模块归属**：全部新类落 `duo-harness-agent`（治理是编排关注点）；llm / session / tools / web 零改动（web 面显示的是会话日志，本就不受读侧治理影响）

## Testing Decisions

- **唯一测试 seam：`buildRequest` 的产物（ChatRequest 消息列表）**——治理前后对比：超长结果被修剪、超阈值触发折叠且近端保留、spill 文件落盘且消息含定位符。不测内部实现
- 先例：ToolCallingAgent 既有测试形态（mock LlmAdapter 断言请求内容）；估算与修剪为纯函数可直接表驱动
- compaction 的 LLM 摘要调用以 mock 适配器注入固定摘要文本
- 全量回归 + 教程/工具目录对账不受影响（docs 零改动预期）

## Out of Scope

- provider usage 捕获（stream_options include_usage）——估算够用后的增强项
- `/compact` 手动命令、Zstandard 压缩、JSONL 代际迁移、flock 跨进程单写（DSH 的工业级强化，未到规模）
- 上下文注入插件（@file / time-context）、subagent（依赖本期的会话隔离基础，M10+ 候选）
- 治理阈值的 yml 配置面板（常量先行）

## Further Notes

- DSH 关键经验（研究材料 45 行）：pruner 前置可能让 compaction 不必触发——修剪与压缩是协同不是并列
- 治理只影响"模型看到什么"，不影响"日志记了什么"——这条是本 spec 的第一性约束
