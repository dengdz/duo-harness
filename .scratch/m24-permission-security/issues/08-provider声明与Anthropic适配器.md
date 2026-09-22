# 08: provider 声明与 Anthropic-messages 适配器

## What to build

yml 增 `llm.provider` 四值显式声明：openai-compat（缺省，兼容现状零改）/ anthropic / deepseek / glm——决定适配器选型、鉴权头形态与 /effort 映射策略，provider 不再由 baseUrl 隐式表达；新增 Anthropic-messages 协议适配器（与 OpenAiCompat 并列的仅有的两个协议实现）：SSE 流式、system/messages 结构映射、tool_use/tool_result 块双向映射、`x-api-key` + `anthropic-version` 鉴权头；不引官方 SDK（零新依赖，红线 4 精神）；装配按 provider 声明选型接线。

决策依据：ADR-0026 决策七（spec 期对账裁定：/effort Anthropic 行需真适配器落地，原 1.0 后菜单提进本期）；docs/research/DSH/LLM与上下文/LLM调用层.md（双协议适配层形态参照）。

## Blocked by

无（可立即开工）

## Status
ready-for-agent

## Checklist
- [ ] `llm.provider` 解析与缺省兼容（LlmConfigTest 扩展）
- [ ] Anthropic-messages 适配器：SSE + tool_use 双向映射 + 鉴权头（新测试面，先例 OpenAiCompatAdapterTest）
- [ ] 装配选型接线（先例 BootTest）
- [ ] 工单级验收件：provider=anthropic 真端点跑通对话 + 工具调用，用户手动确认
- [ ] CHANGELOG 记账（0.19.0 段）
