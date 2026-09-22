# 05: MCP 命名哈希后缀与耗尽终态

## What to build

MCP 远端工具名有损规范化（非法字符替换）后一律追加短哈希后缀（`mcp__{server}__{tool}__{hash8}` 形态，哈希对规范化后全名计算）——任何服务器组合下名字稳定可预期，工具名在审批卡/权限规则/会话历史里前后一致；废弃「清洗坍缩重名即抛错」旧语义（双服务器同名工具共存不炸）；重连耗尽（GAVE_UP + 注销既有语义之上）补会话事件通知（模型与用户可见）+ Web 状态面标注不可用，消灭静默消失；断连期 tools/list_changed 忽略语义不变。

决策依据：ADR-0026 决策四；探测 docs/research/ZCode/扩展机制/MCP.md。

## Blocked by

无（可立即开工）

## Status
ready-for-agent

## Checklist
- [ ] 规范化 + 一律哈希后缀（稳定性测试：同输入同名、跨服务器组合不漂移）
- [ ] 重名不再抛错（双服务器同名工具共存场景）
- [ ] 重连耗尽会话事件通知 + Web 状态面标注
- [ ] 测试（先例 McpToolSyncTest / ReconnectPolicyTest / ConnectionLifecycleTest / WebFaceTest）
- [ ] 工单级验收件：同名工具共存 + 拔服务器可见通知演示，用户手动确认
- [ ] CHANGELOG 记账（0.19.0 段）
