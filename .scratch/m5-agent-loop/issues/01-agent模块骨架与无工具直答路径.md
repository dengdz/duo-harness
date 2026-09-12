# 01 — agent 模块骨架与无工具直答路径

## What to build

新模块 `duo-harness-agent`（依赖 llm + session + tools）——agent 循环的契约与骨架：`ChatAgent` 服务（会话绑定的对话入口）、`AgentListener` 过程回调（onChunk / onToolCall / onToolResult）、中立 `ToolSpec`（llm 模块）、`ChatRequest` 立 `tools` 字段（可空）。本票打通"无工具直答"路径：send → 会话记录（user/message + assistant/message）→ 请求（system + 投影历史，无 tools）→ 流式回复 → AgentReply；迭代上限骨架（超限错误说明的完整逻辑属 02）。mock LLM 单测可完整验证直答路径。

## Blocked by

None (can start immediately)

## Status

ready-for-agent

## Checklist

- [ ] 根 pom 注册模块；新模块 pom（依赖 llm + session + tools + core）
- [ ] llm：`ToolSpec(name, description, parametersJson)` 中立类型；`ChatRequest` 加 `tools` 字段（可空 + 兼容旧构造）
- [ ] 契约：`ChatAgent.send(userText, listener) → AgentReply`、`AgentListener`（onChunk / onToolCall / onToolResult）、`AgentReply`（finalText + toolInvocations）、`ToolInvocation`
- [ ] internal 循环骨架：会话写入（user/message + assistant/message）+ 投影转换 + 流式回调 + 迭代上限常量（执行桥属 02）
- [ ] mock 单测：直答回调序列 / 会话事件落盘 / 投影含历史 / AgentReply 内容
