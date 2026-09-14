# 06: usage 捕获与治理接入

## What to build

真实 token 用量从 provider 流到治理判定：LLM 请求携带 include_usage，流末 usage 作为 assistant/message 事件的可选字段落会话日志（向后兼容）；治理判定数据源切为"真实值优先，provider 未报退本地估算兜底"——治理是硬闸门，provider 不报时不能失效（ADR-0009）。

## Blocked by

无（可立即开工）

## Status
in-progress

## Checklist
- [x] OpenAI 兼容适配器请求携带 stream_options include_usage，捕获流末 usage chunk（mock LLM 流测试）
- [x] assistant/message 事件新增可选 usage 字段落 JSONL；历史会话（无此字段）加载不受影响（事件日志测试）
- [x] 治理判定双路径测试：真实值优先（provider 报告）与估算兜底（provider 未报）各自触发正确
- [x] 无 usage 的 provider 路径下治理照常工作（fail-safe 验证）

## Comments

- 实现（2026-09-14）：三域各一段——llm 域 `TokenUsage` record + `LlmTurn` 可选组件 + 适配器请求带 `stream_options.include_usage`、流末 usage 帧覆盖式捕获（兼容"附在 finish chunk"与"独立成帧"两种到达形态）；session 域同构 `TokenUsage`（两域互不依赖，agent 做映射，DSH 同款划分）+ `assistant/message` 可选 `usage` 字段（reasoning 先例四件套：兼容构造/工厂重载/条件写/容错读）；agent 域 `ToolCallingAgent` 落点单行接线 + `ContextGovernance` 从事件倒查最近 usage 做计量（`govern` 本就持有 session，续接历史会话天然可取；`compact` 保留 2 参估算签名，新增 4 参实测重载，日志标注"实测/估算"口径）。spill/修剪为字符阈值治理，与窗口计量无关，不动。
- 验证：红-绿流程（usage 两用例先红后绿）；llm 25/25、session 75/75、agent 45/45，全量 `mvn -o test` 256 用例 BUILD SUCCESS；真实链路冒烟——DeepSeek 兼容 provider 一轮对话后 JSONL 落 `usage: {promptTokens: 4168, completionTokens: 30, totalTokens: 4198}`。
- 待手动验证：用户亲跑确认后置 done。
