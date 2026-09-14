# 06: usage 捕获与治理接入

## What to build

真实 token 用量从 provider 流到治理判定：LLM 请求携带 include_usage，流末 usage 作为 assistant/message 事件的可选字段落会话日志（向后兼容）；治理判定数据源切为"真实值优先，provider 未报退本地估算兜底"——治理是硬闸门，provider 不报时不能失效（ADR-0009）。

## Blocked by

无（可立即开工）

## Status
ready-for-agent

## Checklist
- [ ] OpenAI 兼容适配器请求携带 stream_options include_usage，捕获流末 usage chunk（mock LLM 流测试）
- [ ] assistant/message 事件新增可选 usage 字段落 JSONL；历史会话（无此字段）加载不受影响（事件日志测试）
- [ ] 治理判定双路径测试：真实值优先（provider 报告）与估算兜底（provider 未报）各自触发正确
- [ ] 无 usage 的 provider 路径下治理照常工作（fail-safe 验证）
