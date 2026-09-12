# Bug 留存台账

> 由 duo-bug-ledger 技能管理：每条含日期/症状/根因/修复/防复发，新条目插在头部。
> 阶段收官时回顾（防复发落实 / 同族根因升级）。

> duo-harness 处理过的 bug 留存（用户约定：每遇到一个 bug 都记录在案）。
> 每条：日期 / 症状 / 根因 / 修复 / 防复发。按时间倒序排列（最新在上）。

---

## BUG-20260913-02 · agent-demo.yml repeat-reminder 行缺 config 块——boot 整树失败

- **日期**：2026-09-13（M6 工单 05 用户手动验收首跑发现）
- **症状**：AgentReplMain 启动即崩——BootException：repeat-reminder 插件声明了 JsonNode 配置类型却未提供配置，boot 整树点名失败。
- **根因**：装配遗漏 + 测试盲区——yml 行没带 config 块（内核约定：声明配置类型即须给块，字段可省）；yml→Boot 装载路径零测试断言，缺陷直通验收。
- **修复**：yml 行补 `config: {}`（默认阈值 3/5/8）；新增 agentDemoYmlBootsCleanly 用例锁定 yml 装载路径。提交 5354c38。
- **防复发**：新增/修改 demo yml 必须配 boot 冒烟用例；插件可选配置约定固化为"JsonNode configType ⇒ 行带 config: {}，免配置用 Plugin<Void>"。档案见 .scratch/bugs/BUG-20260913-02.md。

---

## BUG-20260913-01 · 文档与代码多面不一致（审计发现：README/导航/模块划分/术语/词汇表/包结构）

- **日期**：2026-09-13（M5 收官推送后用户发起全库文档审计发现）
- **症状**：README 停在 M1 时代（版本/模块表/死数字）、站点导航漏 ADR-0007（0.2.0 漏 ADR-0006 的同族复发）、模块划分状态行与正文自相矛盾且依赖列过时、"六段管线"术语与 JavaDoc/词汇表权威"三段"漂移、词汇表缺 M3-M5 三域术语、LlmConfig 违反仓库自己记录的平铺约定。
- **根因**：跨里程碑门面文档（README/导航/词汇表/架构篇）没有同 diff 同步的触发点，收敛被推迟到"收官"且收官清单无对应检查项；术语在会话中新生成时未回查词汇表权威即渗入正式文档。
- **修复**：17 文件——导航补条目、README 重写、模块划分五处对齐、"六段"清零（.scratch 历史档案有意保留）、词汇表补 3 节 7 词条、LlmConfig 归位根包（6 处引用面 + CHANGELOG Changed 记账）。回归：mvn -o test 314/0/0 + docs:build 通过。
- **防复发**：duo-bug-ledger 台账范围扩为"代码缺陷 + 文档与代码不一致缺陷"（用户裁定，本例即首例）；文档计数要么可复现要么不写死；导航对账属 push 前必查的执行纪律。档案见 .scratch/bugs/BUG-20260913-01.md。

---

## BUG-20260912-05 · 思考模型 reasoning_content 未回传（HTTP 400）

- **日期**：2026-09-12（M5 工单 03 用户手动验收发现，tool_call_id 修复后）
- **症状**：工具调用链第 3 轮 LLM 调用（两次工具结果回填后）返回 `HTTP 400 - The reasoning_content in the thinking mode must be passed back to the API`。
- **根因**：思考模型（deepseek-flash）流式响应在 `delta.reasoning_content` 携带思考过程，Function Calling 链中 provider 要求把 assistant 消息的 reasoning_content 原样传回；适配器只捕获 delta.content、LlmTurn/ChatMessage 均无该字段——思考内容首轮即被丢弃。
- **修复**：LlmTurn/ChatMessage 加可空 reasoningContent（兼容构造保旧调用点）；aggregateTurn 按序聚合 reasoning 增量；ToolCallingAgent 逐轮跟踪 pendingReasoning 并附加到最近一条 assistant(tool_calls) 消息；适配器序列化该字段（仅 assistant 工具调用消息）。新增 4 用例（分帧聚合 / 非思考模型 null / 序列化位置 / agent 跨轮回传）。
- **防复发**：协议字段在响应侧与请求侧各有约束，接入新字段必须两侧同查 provider 文档；"mock 验证机制、真实 provider 验证协议"第三次出现——真实 provider 冒烟固化进验收件清单。档案见 .scratch/bugs/BUG-20260912-05.md。

---

## BUG-20260912-04 · 自动继续旧会话时投影 NPE 崩溃（旧格式工具事件无 id）

- **日期**：2026-09-12（M5 工单 03 用户手动验收发现）
- **症状**：自动继续修复前的旧会话（其 tool/call 行无 toolCallId 字段）→ `NullPointerException: id` at ToolCall.<init> → 整个 REPL 进程退出。
- **根因**：SessionEvent 演进加可选字段后，解析层容错但投影层 `deriveMessages` 对 null toolCallId 直接 `new ToolCall(null,...)` 撞上紧凑构造器的非空校验——"向后兼容"只做了解析一半。
- **修复**：投影对 null toolCallId 的工具事件跳过（不投影不崩溃）；AgentReplMain REPL 循环逐轮兜底捕获 RuntimeException。回归测试：旧格式 JSONL 手写样例（session 9 用例之一）。
- **防复发**：record/JSONL 演进时新增可选字段必须同步核查所有消费分支（本次遗漏投影分支）；档案见 .scratch/bugs/BUG-20260912-04.md。

---

## BUG-20260912-03 · TOOL 消息缺 tool_call_id（HTTP 400）

- **日期**：2026-09-12（M5 工单 03 用户手动验收发现）
- **症状**：AgentRepl 中 LLM 成功调用 MCP 工具后，第二轮 LLM 调用返回 `HTTP 400 - messages[6]: missing field 'tool_call_id'`，且两次提问都在同一位置失败。
- **根因**：三层缺陷叠加——(1) SessionEvent 只有 (type/at/text)，工具事件的协议关联 id 无处安放；(2) 会话投影不产出 assistant-with-tool-calls 消息（协议要求 assistant.tool_calls 后紧跟对应 id 的 tool 结果）；(3) 适配器序列化不输出 tool_calls / tool_call_id 字段。mock LLM 不校验协议所以测试全绿——"mock 验证机制、真实 provider 验证协议"的差距。
- **修复**：SessionEvent 加可选 toolCallId/toolName 字段（JSONL 可选字段向后兼容）；session.Message 加 toolCallId/toolCalls（新增中立 ToolCall 类型，不依赖 llm）；投影规则补 tool/call → ASSISTANT(toolCalls) 与 tool/result → TOOL；适配器按协议序列化 tool_calls 数组与 tool_call_id。新增 2 个 session 用例 + llm tools 序列化断言。
- **防复发**：mock 测试无法校验协议兼容性——真实 provider 的验收（路径 B）不可省略；Function Calling 消息形态变更必须以真实 provider 回归。

## BUG-20260912-02 · buildRequest 漏发工具清单（agent 退化为聊天套壳）

- **日期**：2026-09-12（M5 工单 03 用户手动验收发现）
- **症状**：AgentRepl 中 LLM 回答"我无法直接访问你的设备"而非调用工具——agent 退化为纯聊天。
- **根因**：`ToolCallingAgent.buildRequest` 只构造 (systemPrompt, 投影历史)，tools 参数恒空——LLM 从未收到工具清单，自然无法发起 Function Calling。执行桥（工单 02）健在但永远等不到调用。
- **修复**：buildRequest 补发 tools 清单（tools.list() → ToolSpec：name/description/parametersJson）；新增用例"注册工具后请求清单必须携带"（防回归）。
- **防复发**：mock 断言"无工具时清单为空"恰好验证了错误方向——**正向断言必须有**（有注册工具时清单非空且内容正确）。

## BUG-20260912-01 · MockOpenAiServer 夹具缺 choices 包裹

- **日期**：2026-09-12（工单 02 开发自测发现）
- **症状**：streamTurn 测试聚合结果为空 chunk。
- **根因**：夹具生成的 SSE 载荷直接是 choice 节点，缺协议要求的 `choices` 数组包裹——适配器按协议路径 `choices[0].delta` 找不到数据。
- **修复**：夹具补 wrapInChoices；修复后靠"分片聚合"测试覆盖。
- **防复发**：夹具必须按真实协议形态构造载荷，协议结构变更时夹具同步。

## BUG-20260911-01 · 验收命令含行内注释（zsh 当参数）

- **日期**：2026-09-11（M4 验收时用户发现）
- **症状**：`mvn ... exec:java    # 演示路径` 报 `Unknown lifecycle phase "#"`。
- **根因**：验收对照表把注释写在命令行内，zsh 非交互配置下 `#` 不当注释。
- **修复**：M1/M2 两份 acceptance.md 命令与注释分离。
- **防复发**：验收件的命令必须"整行复制即可执行"（duo-acceptance 隐含要求，已修正范本）。

## BUG-2026-0911-02 · 理解关卡后记录（非 bug，流程备忘）

- ChatRepl 按幕刷盘（IDEA 控制台 stdout/stderr 混序问题）——见 0f736e3。

## BUG-20260910-05 · ChatRequest 防御性拷贝回归

- **日期**：2026-09-10（M5 工单 01 code-review 发现）
- **症状**：`ChatRequest` 演进为三组件时丢失 `messages = List.copyOf(messages)`（保留的注释仍是"防御性拷贝"——注释与行为不符）。
- **修复**：恢复拷贝 + messages 具名 requireNonNull。
- **防复发**：record 演进时逐组件核对紧凑构造器行为。

## BUG-20260910-04 · networknt 1.5.0 缺 Dialects 类（NoClassDefFoundError）

- **日期**：2026-09-10（M4 工单 04 开发自测发现）
- **症状**：MCP 夹具子进程 `NoClassDefFoundError: com/networknt/schema/dialect/Dialects`。
- **根因**：票据写 1.5.0，但 SDK mcp-json-jackson2:0.18.1 的 compile 依赖是 2.0.0——Maven 最近优先让 SDK 撞上旧版缺的类。
- **修复**：networknt 随 SDK 升至 2.0.0，校验代码适配新 API（SchemaRegistry/SpecificationVersion/Error）。
- **防复发**：引入与第三方 SDK 配套的库时，以 SDK 的 pom 声明为准，不以票据历史文字为准。

## BUG-20260910-03 · Session.latest 文件名字典序不可靠

- **日期**：2026-09-10（M4 工单 02 code-review 发现）
- **症状**：同秒创建的两个会话，随机后缀字典序与生成序可能不一致（0x1000 < abc），"自动继续"可能选错会话。
- **修复**：id 后缀 %04x 补零（M5 又改为按文件修改时间判定，彻底消除）。
- **防复发**：——已由 mtime 方案根治。

## BUG-20260910-02 · isError 被 setResult 覆盖

- **日期**：2026-09-10（M2 工单 02 开发发现）
- **症状**：工具返回 isError=true 时若再 setResult(null)，错误形态被覆盖为非错误。
- **修复**：适配器抛 PluginException 交给管线收敛，不再直接 markError 后返回。
- **防复发**：——已在 ToolCallingAgent 抛错路径固化。

## BUG-20260910-01 · 首连成功后缺 countDown（测试全挂起）

- **日期**：2026-09-10（M2 工单 02 开局发现）
- **症状**：`runFirstAttempt()` 永久阻塞——连接成功但调用方不被放行。
- **根因**：工具同步挂钩插在放行点之前时把 `firstAttempt.countDown()` 挤掉了。
- **修复**：同步完成后才 countDown（同步属于"连接就绪"的一部分）。
- **防复发**：放行点必须跟在最后一步之后——已固化在 ConnectionSupervisor 结构与注释中。
