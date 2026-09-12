# Bug 留存台账

> duo-harness 处理过的 bug 留存（用户约定：每遇到一个 bug 都记录在案）。
> 每条：日期 / 症状 / 根因 / 修复 / 防复发。按时间倒序排列（最新在上）。

---

## BUG-2026-0912-03 · TOOL 消息缺 tool_call_id（HTTP 400）

- **日期**：2026-09-12（M5 工单 03 用户手动验收发现）
- **症状**：AgentRepl 中 LLM 成功调用 MCP 工具后，第二轮 LLM 调用返回 `HTTP 400 - messages[6]: missing field 'tool_call_id'`，且两次提问都在同一位置失败。
- **根因**：三层缺陷叠加——(1) SessionEvent 只有 (type/at/text)，工具事件的协议关联 id 无处安放；(2) 会话投影不产出 assistant-with-tool-calls 消息（协议要求 assistant.tool_calls 后紧跟对应 id 的 tool 结果）；(3) 适配器序列化不输出 tool_calls / tool_call_id 字段。mock LLM 不校验协议所以测试全绿——"mock 验证机制、真实 provider 验证协议"的差距。
- **修复**：SessionEvent 加可选 toolCallId/toolName 字段（JSONL 可选字段向后兼容）；session.Message 加 toolCallId/toolCalls（新增中立 ToolCall 类型，不依赖 llm）；投影规则补 tool/call → ASSISTANT(toolCalls) 与 tool/result → TOOL；适配器按协议序列化 tool_calls 数组与 tool_call_id。新增 2 个 session 用例 + llm tools 序列化断言。
- **防复发**：mock 测试无法校验协议兼容性——真实 provider 的验收（路径 B）不可省略；Function Calling 消息形态变更必须以真实 provider 回归。

## BUG-2026-0912-02 · buildRequest 漏发工具清单（agent 退化为聊天套壳）

- **日期**：2026-09-12（M5 工单 03 用户手动验收发现）
- **症状**：AgentRepl 中 LLM 回答"我无法直接访问你的设备"而非调用工具——agent 退化为纯聊天。
- **根因**：`ToolCallingAgent.buildRequest` 只构造 (systemPrompt, 投影历史)，tools 参数恒空——LLM 从未收到工具清单，自然无法发起 Function Calling。执行桥（工单 02）健在但永远等不到调用。
- **修复**：buildRequest 补发 tools 清单（tools.list() → ToolSpec：name/description/parametersJson）；新增用例"注册工具后请求清单必须携带"（防回归）。
- **防复发**：mock 断言"无工具时清单为空"恰好验证了错误方向——**正向断言必须有**（有注册工具时清单非空且内容正确）。

## BUG-2026-0912-01 · MockOpenAiServer 夹具缺 choices 包裹

- **日期**：2026-09-12（工单 02 开发自测发现）
- **症状**：streamTurn 测试聚合结果为空 chunk。
- **根因**：夹具生成的 SSE 载荷直接是 choice 节点，缺协议要求的 `choices` 数组包裹——适配器按协议路径 `choices[0].delta` 找不到数据。
- **修复**：夹具补 wrapInChoices；修复后靠"分片聚合"测试覆盖。
- **防复发**：夹具必须按真实协议形态构造载荷，协议结构变更时夹具同步。

## BUG-2026-0911-01 · 验收命令含行内注释（zsh 当参数）

- **日期**：2026-09-11（M4 验收时用户发现）
- **症状**：`mvn ... exec:java    # 演示路径` 报 `Unknown lifecycle phase "#"`。
- **根因**：验收对照表把注释写在命令行内，zsh 非交互配置下 `#` 不当注释。
- **修复**：M1/M2 两份 acceptance.md 命令与注释分离。
- **防复发**：验收件的命令必须"整行复制即可执行"（duo-acceptance 隐含要求，已修正范本）。

## BUG-2026-0911-02 · 理解关卡后记录（非 bug，流程备忘）

- ChatRepl 按幕刷盘（IDEA 控制台 stdout/stderr 混序问题）——见 0f736e3。

## BUG-2026-0910-05 · ChatRequest 防御性拷贝回归

- **日期**：2026-09-10（M5 工单 01 code-review 发现）
- **症状**：`ChatRequest` 演进为三组件时丢失 `messages = List.copyOf(messages)`（保留的注释仍是"防御性拷贝"——注释与行为不符）。
- **修复**：恢复拷贝 + messages 具名 requireNonNull。
- **防复发**：record 演进时逐组件核对紧凑构造器行为。

## BUG-2026-0910-04 · networknt 1.5.0 缺 Dialects 类（NoClassDefFoundError）

- **日期**：2026-09-10（M4 工单 04 开发自测发现）
- **症状**：MCP 夹具子进程 `NoClassDefFoundError: com/networknt/schema/dialect/Dialects`。
- **根因**：票据写 1.5.0，但 SDK mcp-json-jackson2:0.18.1 的 compile 依赖是 2.0.0——Maven 最近优先让 SDK 撞上旧版缺的类。
- **修复**：networknt 随 SDK 升至 2.0.0，校验代码适配新 API（SchemaRegistry/SpecificationVersion/Error）。
- **防复发**：引入与第三方 SDK 配套的库时，以 SDK 的 pom 声明为准，不以票据历史文字为准。

## BUG-2026-0910-03 · Session.latest 文件名字典序不可靠

- **日期**：2026-09-10（M4 工单 02 code-review 发现）
- **症状**：同秒创建的两个会话，随机后缀字典序与生成序可能不一致（0x1000 < abc），"自动继续"可能选错会话。
- **修复**：id 后缀 %04x 补零（M5 又改为按文件修改时间判定，彻底消除）。
- **防复发**：——已由 mtime 方案根治。

## BUG-2026-0910-02 · isError 被 setResult 覆盖

- **日期**：2026-09-10（M2 工单 02 开发发现）
- **症状**：工具返回 isError=true 时若再 setResult(null)，错误形态被覆盖为非错误。
- **修复**：适配器抛 PluginException 交给管线收敛，不再直接 markError 后返回。
- **防复发**：——已在 ToolCallingAgent 抛错路径固化。

## BUG-2026-0910-01 · 首连成功后缺 countDown（测试全挂起）

- **日期**：2026-09-10（M2 工单 02 开局发现）
- **症状**：`runFirstAttempt()` 永久阻塞——连接成功但调用方不被放行。
- **根因**：工具同步挂钩插在放行点之前时把 `firstAttempt.countDown()` 挤掉了。
- **修复**：同步完成后才 countDown（同步属于"连接就绪"的一部分）。
- **防复发**：放行点必须跟在最后一步之后——已固化在 ConnectionSupervisor 结构与注释中。
