# M6 Spec：agent 打磨 + HITL（CLI 版）

> ADR-0007 渐进序列第四站（v3 校准）。M5 让模型"会干活"，M6 让它"可托付"——人能在关键动作上说不，模型能在信息不足时开口问，提示词可组装，连续失败不再一崩到底。机制依据 ADR-0008（交互机制与呈现分离）；DSH 0.1.5 全景研究的 interaction 域（审批 seam / ask_user / answerer waterfall）为直接参照（[docs/research/DSH/核心功能全景.md](../../docs/research/DSH/核心功能全景.md)）。
>
> **过程记录**：M5 理解关卡凭证缺失（M3/M4 同），用户裁定豁免、直接进入 M6 访谈（2026-09-13）；路线图校准经用户确认后落 ADR-0007 v3。

## Problem Statement

M5 收官后，agent 能自主调工具，但离"可用"还有四类硬伤：① 想让 agent 动手写文件，只有"全部拒绝"或"全部放行"两档，没有"这一步问过我再说"——审批的 ask 三态立了两期没有人能回答；② 模型遇到信息不足只能瞎猜或编造，无法停下来问用户一句；③ 提示词是写死的单一字符串，插件无法贡献自己的平台指令（工具使用守则、安全约束），M7 的技能系统也没有挂载点；④ 网络一次抖动 / 5xx 就让整轮对话崩掉（已知限制挂账），模型陷入重复调用同一工具时也只有"跑满 10 轮"这一条硬防线。

## Solution

四个组件把 agent 从演示形态打磨成可托付形态（均为既定架构惯例的延伸，无新模块）：

1. **prompt 注册表**（agent 域 `prompts` 服务）：插件经 `register(registrant, fragment)` 贡献提示片段，随作用域摘除、按注册序动态组装为最终 system 提示；yml 的 `llm.systemPrompt` 是排在最前的用户指令片段。
2. **交互 seam + 审批交互策略**（tools 域，ADR-0008）：回答者注册制 + `interactive` 审批策略——声明"需人作答"的请求交给在场回答者，无人应答 fail-closed；AgentRepl 注册 console answerer（终端 y/n，one-shot，无 remember）。
3. **`ask_user` 提问工具**（tools 域提供定义，装配决定挂载）：模型暂停问人，回答作为工具结果回填；走六段管线；问答复用 tool/call、tool/result 事件留痕。
4. **可靠性小件**：`RetryingAdapter`（llm 域装饰器，网络错与 429/5xx 退避重试，协议/凭证错绝不重试）+ `RepeatReminderPlugin`（example 域治理插件，post-execute 给连续重复调用附加逐级提醒）。

外加打磨小件：AgentRepl 补 `/new`；迭代上限与重试参数进 Boot yml `agent:` 段。

## User Stories

1. As a harness 使用者, I want 工具执行前在终端看到"将做什么"并由我按 y/n 决定, so that 敏感操作在我的视线内发生而不是被策略一刀切
2. As a harness 使用者, I want LLM 在信息不足时通过选项或自由文本向我提问, so that 它不用瞎猜参数或擅自假设
3. As a harness 使用者, I want 我不回答（Ctrl+C / EOF）时一切按拒绝处理, so that 关掉终端绝不会留下"被默许"的半成品操作
4. As a harness 使用者, I want 审批交互只有"允许本次 / 拒绝"两选项, so that 我不需要理解复杂的授权模型也不会误放行
5. As a harness 使用者, I want 会话回放里能看到每次审批的请求与我的决定, so that 事后能审计 agent 被允许做过什么
6. As a harness 使用者, I want 网络抖动后对话自动恢复而不是报错退出, so that 长任务不因一次请求失败前功尽弃
7. As a harness 使用者, I want 模型陷入重复调用同一工具时收到逐级提醒, so that 它更早换方法而不是烧满迭代上限
8. As a harness 使用者, I want AgentRepl 里用 /new 开新话题, so that 两个 REPL 行为一致、长会话可以分段
9. As a 插件开发者, I want 经 register 贡献提示片段并随我的插件作用域自动摘除, so that 我的平台指令与插件同生命周期、拔插件无残留
10. As a 插件开发者, I want 用与 tools / guard 完全同构的注册 API 贡献片段和回答者, so that 学会一个就全会
11. As a 集成者, I want yml 里配置的 systemPrompt 永远拼在组装结果最前, so that 我的显式配置保持最高可见性而不被插件片段淹没
12. As a M8 Web 面开发者, I want 审批与提问都走同一个交互 seam, so that 我只需注册一个 web answerer 而不用重写任何策略
13. As a M8 Web 面开发者, I want 会话日志含 approval/requested 与 approval/decided 事件, so that 界面能回放每次人机决策
14. As a M7 技能系统开发者, I want prompt 注册表作为技能指令段的挂载点, so that 技能加载即注册、卸载即摘除
15. As a 框架维护者, I want 交互 seam 的接口与实现都在 tools 域, so that 依赖方向保持向下（example 注册回答者合法，agent 循环无感知）
16. As a 框架维护者, I want 重试是 LlmAdapter 装饰器而非循环内逻辑, so that llm 契约零改动、装饰可组合可关闭
17. As a 框架维护者, I want 重复提醒走 post-execute 结果改写而非 guard, so that guard 的"单调否决"语义不被 advisory 用途污染
18. As a 安全关注者, I want 400/401 类协议与凭证错误永不重试, so that 配置错误被立刻暴露而不是被重试掩盖
19. As a harness 使用者, I want 被模型提问时看到问题、可选选项并可用自由文本回答, so that 我能用最低成本给出模型需要的答案
20. As a 会话审计者, I want 提问与回答在回放中呈现为普通工具调用对, so that 无需新工具即可理解全部交互史

## Implementation Decisions

- **prompt 注册表**（agent 域，服务名裸名 `prompts`）：`PromptFragment(source, content)`——source 标记贡献者便于审计；`register(registrant, fragment)` 随注册作用域自动摘除（与 tools/guard 同构）；每轮 `buildRequest` 按注册序动态组装，注册表内容不变则最终串不变（DeepSeek 前缀缓存天然不受影响）；yml `llm.systemPrompt` 作为"用户指令片段"排最前参与拼接，全部为空才落 `DEFAULT_SYSTEM_PROMPT`；不分节（片段自带角色语义，分节是过度设计）。
- **交互 seam**（tools 域，ADR-0008）：交互服务提供 `register(registrant, answerer)`，遍历在场回答者处理交互请求；`interactive` 审批策略将 ask 裁决委托给 seam；**fail-closed**：无回答者 / EOF / 中断一律 DENY；**不做 remember**（one-shot y/n；持久授权属策略层未来课题）。seam 放 tools 域由依赖图强制：放 agent 域将成 tools→agent 环。
- **`ask_user` 工具**：tools 域提供 ToolDefinition（装配 yml 一行决定挂载），走六段管线不豁免；参数最小 schema：`question`（必填）+ `options`（可选字符串数组，空 = 自由文本）+ `multiSelect`（默认 false）；`intent` 等字段等 M7 plan-review 真用到再加（schema 先窄后宽）；执行本体 = 经 seam 等人作答，回答文本即工具结果。
- **会话事件词汇扩展**：新增 `approval/requested` 与 `approval/decided`（载荷：工具名、声明来源、决定、回答者来源）——可选字段式演进，旧会话文件向后兼容（M4 先例）；问与答**不**加新类型（复用 tool/call + tool/result）。
- **审批留痕的实现**：装饰性审计回答者——包装真实回答者，委托前写 `approval/requested`、决定后写 `approval/decided`；机制核不依赖会话与监听链，留痕是装配层可组合的关注点；终端呈现由 console answerer 自身承担，`AgentListener` 不扩。
- **`RetryingAdapter`**（llm 域）：装饰任意 `LlmAdapter`，契约零改动；重试范围 = 网络异常 + HTTP 429/502/503/504，指数退避默认 3 次（1s/2s/4s）；400/401 等协议与凭证错误直通不重试；装配处一行包装，REPL 默认挂上。落地即消除 limitations M5 #2。
- **`RepeatReminderPlugin`**（example 域治理插件）：post-execute 结果改写——同一工具 + 相同参数哈希连续重复达阈值（3 次起，逐级加码）时在结果尾部附加提醒文本，不改错误形态；核心域不内置（治理策略属插件生态）；与硬迭代上限软硬互补。
- **打磨小件**：AgentRepl 补 `/new`（对齐 ChatRepl）；Boot yml 新增 `agent:` 段承载 `maxIterations` 与重试参数（`retry: maxAttempts / initialBackoffMs`）。
- **模块边界**：无新模块。agent 域 +prompts 服务；tools 域 +交互服务 / interactive 策略 / ask_user 定义 / approval 事件类型；llm 域 +RetryingAdapter；example 域 +RepeatReminderPlugin / console answerer / 审计回答者 / demo 装配（`agent:` 段 yml）。

## Testing Decisions

- **只测外部行为**：观测量 = ChatRequest 的最终 system 串、ToolsService.execute 的结果与错误形态、REPL 输出叙述、会话 JSONL 事件序列；不窥注册表内部结构、不计重试次数实现细节（只断言"最终成功且未对 4xx 重试"这类外部语义）。
- **测试 seam（5 个，其中 4 个复用现有）**：
  - ChatRequest 捕获（现有，ToolCallingAgentTest 先例）→ prompt 组装、迭代上限配置；
  - ToolsService.execute（现有，ToolsServiceTest 先例）→ interactive 策略三态（无回答者 DENY / allow 放行 / deny 转错误）、ask_user 执行、RepeatReminder 提醒文本；
  - 脚本化 mock LLM 两轮对话（现有，AgentReplMainTest 先例）→ "调 ask_user → 等回答 → 用回答总结"闭环、审批拒绝后模型解释；
  - MockOpenAiServer（现有）→ RetryingAdapter 的 5xx 重试与 4xx 直通；
  - **新 seam 唯一一个：交互服务本身**（回答者注册 / 遍历 / fail-closed）——它就是被测契约，用 mock answerer 直接断言。
- **会话回放断言**：审批交互后读会话 JSONL，断言 approval/requested → approval/decided 事件对与旧格式兼容（SessionTest 先例：旧格式样例手写回放）。

## Out of Scope

- "本次会话始终允许" / 永久放行 / remember（ADR-0008 明确不做；持久授权属策略层未来课题）
- 审批超时定时器（CLI 阻塞等人在场，EOF/Ctrl+C 即拒；超时依赖异步时钟，留给 Web 形态）
- Web answerer 与审批/提问的 Web 呈现（M8）；Inbox / steer 中途插话（M8+）
- 技能系统与 plan-review intent（M7——prompt 注册表是它的挂载点，本期只立注册表）
- 权限预设打包（M9+，依赖沙箱概念）；`intent` 等 ask_user 扩展字段（M7）
- 并发工具调度；AGENTS.md 注入（M7）

## Further Notes

- ADR-0008 是本 spec 的机制立约：M8 实现被迫改机制即证伪。
- 词汇表已新增：prompt 注册表 / prompt 片段 / 交互 seam / 回答者 / fail-closed / 提问工具（CONTEXT.md）。
- 文档同步义务（提交前核对）：CHANGELOG 未发布段（新服务与事件类型）、limitations.md M5 #2（重试落地即删条）、运行Demo.md（M6 演示段与审批交互预期输出）、config.mts（ADR-0008 已挂）。
