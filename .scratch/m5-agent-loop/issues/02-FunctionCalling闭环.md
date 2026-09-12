# 02 — Function Calling 闭环（工具调用执行桥）

## What to build

端到端工具调用闭环——LLM 自主决定调工具，工具经六段治理管线执行，结果回填直至最终回答：llm 侧流式聚合支持 tool_calls 分片（函数名与参数 JSON 分段累积）；`ChatMessage.Role` 加 TOOL；session 词汇加 `tool/call` / `tool/result`；agent 执行桥：tool_calls 逐个经 `ToolsService.execute`（六段管线）→ 结果以 TOOL 消息回填 → 循环直至最终回答；迭代上限生效（超限返回错误说明）。mock LLM（tool_calls 脚本）+ 真 ToolsService（测试工具）可完整验证。

## Blocked by

01

## Status

ready-for-agent

## Checklist

- [ ] llm 流式聚合：tool_calls 分片累积（函数名 + 参数 JSON），聚合完成产出结构化调用
- [ ] `ChatMessage.Role` 加 TOOL；会话投影加 `tool/result` → TOOL 消息（协议 tool_call_id 关联）
- [ ] 会话词汇：`tool/call` / `tool/result` 事件（载荷：工具名 + 参数 JSON / 结果文本 + 是否错误）
- [ ] agent 执行桥：tool_calls → `ToolsService.execute`（六段管线）→ tool/result 事件 → TOOL 消息回填
- [ ] 迭代上限生效（超限返回含"已尝试 N 次"的错误说明）
- [ ] 测试：mock LLM 要求调工具（真 ToolsService 执行）→ 结果回填 → 二轮直答；工具失败原样回填；上限触发
