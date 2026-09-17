# 01: 会话域 subagent 事件与投影规则

## What to build

框架维护者获得 subagent 事件域的会话层基座：`subagent/spawned` 与 `subagent/completed` 事件类型（spawned 携 id/模板名/模式/fork 源引用；completed 携结果概要与最终回答），JSONL 往返一致；投影规则——completed 的最终回答**投影进父 LLM 上下文**（父聚合结果的数据源），spawned 跳过投影（呈现卡片专用）。先例：approval 事件的往返与投影跳过用例。

## Blocked by

None（可立即开工）

## Status

done（2026-09-16 用户验收：功能演示 + 全量测试双路径通过）

## Checklist
- [x] SessionEvent 新增 SUBAGENT_SPAWNED / SUBAGENT_COMPLETED 类型与工厂方法（字段沿记录既有可选位），JSONL 往返一致
- [x] 投影规则：completed → 父投影消息（最终回答文本），spawned → 跳过
- [x] 用例：两类事件往返、投影进/跳过断言、旧格式会话向后兼容不受影响

## 实现记录

- `SessionEvent`：新增 `SUBAGENT_SPAWNED` / `SUBAGENT_COMPLETED` 常量与 `subagentSpawned(agentId, templateName, payloadJson)` / `subagentCompleted(agentId, resultText)` 工厂。字段沿既有可选位：toolCallId = 子 agent id（completed 同 id 关联 spawned）、toolName = 模板名、text = 载荷 JSON / 结果文本（会话层透明往返，载荷组装归工单 03）。
- `Session`：`projectsToMessage` 放行 completed（USER 消息进父 LLM 上下文——tool 关联位已被 spawn 调用消费，不能冒充 ASSISTANT）；spawned 跳过（卡片专用）。`deriveMessages` 增对应 case。
- `SessionTest` 新增 2 用例（往返 + 投影分流、旧格式行与新事件混排兼容），35 用例全绿；全仓库 compile 零破坏。
- 文档同步：SessionEvent / Session / Message JavaDoc 投影契约更新。

## 验收对照（应出现的套件叙述与结果，实测快照 2026-09-16）

命令：仓库根目录 `mvn test`（全量，用户手跑）。应出现：

```
=== 套件：SessionTest —— ……、子代理事件往返与投影分流、种子边界标记（36 用例） ===
Tests run: 36, Failures: 0, Errors: 0, Skipped: 0
```

覆盖核心路径：spawned/completed JSONL 往返一致；completed 投影进父上下文（USER 形态）、spawned 跳过；旧格式行与新事件混排读取不崩；seed-boundary 往返且不进投影。
