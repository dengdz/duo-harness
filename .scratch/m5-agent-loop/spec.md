# M5 Spec：工具循环（真 agent）

> ADR-0007 渐进序列第三站。M3 让模型"能说话"，M4 让它"记得住"，M5 让它"会干活"——LLM 自主决定调用工具，工具经六段治理管线执行，结果回填直至最终回答。这是治理链（审批 / guard / 输出契约）第一次获得真实消费者。

## Problem Statement

M4 之后 ChatRepl 是一个"有记忆的聊天套壳"：用户提问、模型回答，仅此而已。harness 的核心投资——工具域（六段管线）、MCP 接入、审批、guard、输出契约——全部没有真实消费者。用户想让 LLM"读一下这个文件"、"写个配置"，只能自己手动调工具再把结果粘贴给模型。没有工具循环，harness 与普通聊天 API 无异。

## Solution

新模块 `duo-harness-agent`：agent 循环——LLM 自主决定调用工具（协议层 Function Calling），工具经六段治理管线执行，结果回填直至模型给出最终回答。`AgentReplMain` 演示端到端：用户说"读一下 notes.txt"→ LLM 调用 MCP 文件工具 → 真实文件内容回填 → LLM 总结回答；配置 always-deny 后，LLM 想写文件会被审批拒绝，并向用户解释原因。

## User Stories

1. As a harness 使用者, I want 在对话中让 LLM 自主调用工具（如读取文件）, so that 我不需要手动调工具再复制结果给模型
2. As a harness 使用者, I want LLM 调用工具的过程在终端可见（调了什么、结果如何）, so that 我能理解与信任 agent 的行为
3. As a harness 使用者, I want 工具被审批拒绝后 LLM 告知我原因, so that 我知道为什么任务没完成、如何调整
4. As a harness 使用者, I want 多轮工具调用有迭代上限, so that 异常任务不会无限循环烧 token
5. As a agent 循环开发者, I want 工具执行走六段治理管线, so that 审批 / guard / 输出契约对 LLM 驱动的调用与手动调用同样生效
6. As a Web 面开发者（M8）, I want 会话日志含结构化的 tool/call 与 tool/result 事件, so that 界面能完整渲染 agent 的工具调用过程
7. As a 框架维护者, I want agent 循环独立成模块（依赖 llm + session + tools）, so that 三条被依赖边保持零交叉、编排层可独立演进
8. As a harness 使用者, I want 会话文件回放含工具调用的完整过程, so that 我能事后审计 agent 做过什么
9. As a MCP 使用者, I want MCP 远端工具与本地工具一样出现在 LLM 的工具清单里, so that 远端能力无需特殊对待

## Implementation Decisions

- **工具暴露走协议层 Function Calling**（不做提示词注入文本协议）：请求带 `tools` 清单（每个工具的名称 / 描述 / 参数 JSON Schema），响应解析结构化 `tool_calls` 执行。目标 provider（DeepSeek / 通义 / Kimi / GPT / Claude）全部原生支持。
- **llm 契约演进（三处）**：
  - 新中立类型 `ToolSpec(name, description, parametersJson)`——工具描述形态与 provider 无关；
  - `ChatRequest` 加第三字段 `List<ToolSpec> tools`（可空 = 无工具直答）；
  - 流式聚合演进：tool_calls 在流式响应中分片传输（函数名与参数 JSON 分段到达），chunk 聚合需支持工具调用片段的累积——聚合完成后产出结构化调用。
- **会话事件词汇扩展**：新增 `tool/call`（载荷：工具名 + 参数 JSON）与 `tool/result`（载荷：结果文本 + 是否错误）。JSONL 按 type 判别，追加新类型不破坏旧会话文件（M4 决策的延续）。
- **消息角色扩展**：`ChatMessage.Role` 新增 `TOOL`（协议的 tool 结果消息，携带 tool_call_id 关联）；投影规则新增 `tool/result` → TOOL 消息——工具结果必须回填给 LLM 才能继续推理。
- **agent 循环（最小串行闭环）**：`send(userText)` → 会话记录 → 构造请求（system prompt + 投影历史 + tools 清单）→ 流式调用 → 无 tool_calls 则最终回复结束；有则**串行**逐个经 `ToolsService.execute`（六段管线）执行 → 结果回填 → 继续循环；**最大迭代上限**（默认 10，超限返回错误说明而非无限循环）。
- **治理链激活语义**：工具执行失败（审批拒绝 / guard 拦截 / 违约）已由管线收敛为 error 结果，**原样回填给 LLM**——模型看到拒绝原因后自行调整行为（换方案 / 向用户解释），而非 harness 层终止。
- **审批交互**：M5 = 策略硬拒（always-deny 即 LLM 无法执行写操作，只能解释原因）；CLI 交互确认（y/n）属 M6 HITL。
- **模块**：新模块 `duo-harness-agent`（依赖 llm + session + tools，依赖图顶层汇合）；契约（`ChatAgent` 服务 + `AgentListener` 过程回调：onChunk / onToolCall / onToolResult）+ internal（循环实现）。
- **demo 装配**：独立 `AgentReplMain`（example）：Boot 配置（tools 插件 + 审批 always-deny + MCP files 连接）→ `ToolsService` 与会话目录注入 `ChatAgent`；ChatRepl（M3/M4 纯聊天）保持不动，两形态可对照。

## Testing Decisions

- **只测外部行为**：agent 用例走"send → listener 回调序列 / 会话日志内容 / 最终回复"的外部观测量，不窥循环内部结构。
- **mock LLM**：扩展 `MockOpenAiServer` 脚本——tool_calls 响应（含分片聚合路径）与纯文本响应可控切换。
- **真工具 + mock LLM**：工具用真实 `ToolsService`（注册测试用 ToolDefinition），LLM 用 mock——验证"LLM 要调工具 → 六段管线真实执行 → 结果回填"的完整闭环。
- **先例**：`MockOpenAiServer`（脚本化 SSE/错误回放）、`ChatReplMainTest`（脚本输入 + 输出叙述断言）。

## Out of Scope

- 并发工具调度（依赖并发安全分类，M9+）；Inbox / steer 中途插话（M8 Web 交互形态）
- compaction（M9+）；system-prompt 组装注册表（M6——M5 用固定模板 + 工具 description）
- CLI 交互审批（M6 HITL）；Web 面的审批交互（M8）
- 多 provider 适配器（Anthropic 等，出现真实消费需求再加）；重试策略（打磨期）
- 会话多文件管理（列表 / 删除 / 改名）——最小集之外按需

## Further Notes

- tool 消息的 `tool_call_id` 关联是 OpenAI 兼容协议的硬要求——assistant 消息带 tool_calls 后必须紧跟对应 id 的 tool 结果消息，实现时以协议文档为准。
- 会话事件 `tool/call` / `tool/result` 自本里程碑起与 `user/message` / `assistant/*` 同为公开契约（M8 Web 面消费），字段命名与语义变更按公开 API 演进对待。
- LLM 反复重试被拒工具是预期行为边界——迭代上限是兜底，超限的返回消息应包含"已尝试 N 次工具调用"的事实供用户判断。
