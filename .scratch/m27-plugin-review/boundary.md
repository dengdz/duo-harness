# M27 内核边界矩阵——插件能挂什么、内核独占什么

> 工单 01 产物。用途：03 预演、04 demo、06 扩展点清单升格的**共同事实底座**——三者引用的挂点事实以本册为回溯源。
> 口径：「扩展点 / 内核独占面 / 硬连点」依 [术语表](../../docs/05-参考/术语表.md) 词条；事实核实基准 2026-09-27（0.22.0 分支，含 file:line 供复核，行号漂移以符号名为准）。
> 硬连点的病丑定性不在本册（02 扫描册负责），本册只划「能挂 / 不能挂」的边界。

## 1. 插件能挂什么——扩展点全清单（22 项）

### A. 装载与生命周期

| # | 扩展点 | 挂接方式 | 能力边界 | 先例 |
|---|---|---|---|---|
| A1 | yml 插件行装载 | `Boot.from(yml)`；行格式 `id / name(FQCN) / config / disabled`，反射装载 | 同 JVM 直类加载；无 jar/目录扫描（见独占面 C7） | agent-demo.yml 全部行 |
| A2 | 编程挂载 | `ctx.plugin(plugin, rawConfig)` 同步阻塞至 apply 返回，得 PluginHandle | 仅代码可达处（宿主/测试）；非部署者通道 | DemoMain 内嵌 TempProvider/TempConsumer |
| A3 | 子插件挂载 | 任意插件 apply 内再 `ctx.plugin()`，销毁级联回滚 | 深度不限，作用域父子 | DemoMain 级联演示 |
| A4 | config record 绑定 | `configType()` 声明 record，内核绑定、失败即加载失败（点名插件与字段路径） | 强类型契约，原始形态 Map/JsonNode | GreetingPlugin、cli/web 治理配置段 |
| A5 | 依赖声明 | `inject()`（全就绪才启动、任一消失即停止）/ `optionalInject()`（ADR-0019，出现/消失双向自动重载） | 错误前移，免写启动顺序 | GreetingClientPlugin（inject）、CliPlugin 纯对话装配（optionalInject） |
| A6 | 状态快照读取 | `ctx.snapshots()` 全量插件六态即时视图 | 只读、不缓存 | Web 状态面六态表 |

### B. 服务面

| # | 扩展点 | 挂接方式 | 能力边界 | 先例 |
|---|---|---|---|---|
| B1 | 服务发布 | `ctx.provide(name, instance)`；同名互斥点名报错；发布即作用域副作用（销毁自动注销+唤醒依赖方） | 全局扁平命名空间；harness 保留裸名、第三方须加前缀 | ToolsPlugin（23 行）、GreetingPlugin（28 行） |
| B2 | 服务便利基类 | 继承 `Service` 构造即发布 | **全仓零使用**（形态存在但先例全走 B1 直发）——第三方可选形态 | 无 |
| B3 | 服务消费 | `ctx.as(视图接口)` 动态代理按方法名寻址；读取须先 inject/optionalInject 声明（未声明即拒读，错误前移） | 方法名=服务名、返回类型须兼容；错配调用时点名 | GreetingClientPlugin（32 行）、SubagentPlugin（115 行） |
| B4 | 视图接口自定义 | 第三方插件自带视图接口，无需改 core | 动态代理对任意接口生效 | （呈现位私有视图族是同机制的反面用法——每呈现位重声明一遍，见独占面 C2 注） |

### C. 工具域（插件可挂面的主力区）

| # | 扩展点 | 挂接方式 | 能力边界 | 先例 |
|---|---|---|---|---|
| C1 | 工具注册 | inject "tools" 后 `tools.register(ctx, ToolDefinition)`；同名冲突报错；注册即生命周期硬契约（注册方停止工具自动注销） | 工具对模型可见；Web 渲染无插件路径（见独占面 C6） | EchoToolPlugin（65 行）、SubagentPlugin 自装五件 |
| C2 | 三段管线挂钩 | `ctx.on("tools/pre-execute" / "tools/execute" / "tools/post-execute", WaterfallListener)` 洋葱模型，可改写参数/结果、不调 next 即否决 | 载荷 ToolExecution；类型一致性靠约定（擦除，错配派发时才炸） | HooksPlugin（271 行外部命令形态）、ToolGuardPlugin（45 行 Java 否决形态） |
| C3 | guard 单调否决 | `tools.registerGuard(...)`：理由即拒绝、无"允许"结果，后续无法翻回 | 审批之后、本体之前 | ToolGuardPlugin |
| C4 | 审批策略替换 | 发布 "approval" 服务（ApprovalPolicyService）——ask 的裁决者 | 同名互斥，装配按 yml 行选一：ApprovalPlugin / InteractiveApprovalPlugin / WorkspaceApprovalPlugin 三实现互斥在场即替换 | 三审批插件（策略可插拔的活证） |
| C5 | 回答者注册 | 发布/注入 "answers" 服务（InteractionService），注册呈现端回答者 | 机制（遍历、fail-closed）内核定；**注册回答者是扩展点，「呈现位身份」不是**（见独占面 C1） | CliPlugin console answerer、WebPlugin web answerer |

### D. 命令、提示与内容域

| # | 扩展点 | 挂接方式 | 能力边界 | 先例 |
|---|---|---|---|---|
| D1 | 斜杠命令注册 | inject "commands" 后 `commands.register(ctx, CommandDefinition)`（含 busy-safe 声明、适用呈现位标记 CLI/WEB/ANY） | 同步执行于呈现位进程内、不进模型历史；落审计事件 | CliPlugin /export、WebPlugin 斜杠前置 |
| D2 | prompt 片段贡献 | inject "prompts" 后 `register(registrant, fragment)`，随注册作用域自动摘除；每轮按注册序组装 system | 静态片段（注册时刻冻结）；动态按请求注入无插件口（backlog 挂账） | 技能指令段组装（M7）；PromptPlugin 发布注册表 |
| D3 | 技能发现 | "skills" 服务（SkillRegistry）+ 发现根目录扫描 | 技能是 SKILL.md 非代码；与插件分界见术语表 | SkillsPlugin |
| D4 | 内容域服务在场 | memory / agentsMd / fileRefs / attachments / sessionQuery 等服务：插件发布即在册 | **服务在册 ≠ 被消费**：消费接线在装配层硬连（见独占面 C4），"插件替内容域换实现"目前走不通 | MemoryPlugin、AgentsMdPlugin、AttachmentPlugin、SessionQueryPlugin |

### E. 事件面

| # | 扩展点 | 挂接方式 | 能力边界 | 先例 |
|---|---|---|---|---|
| E1 | 自定义事件五式 | `emit`（广播异常隔离）/ `parallel`（并发聚合）/ `serial`、`bail`（投票）/ `waterfall`（洋葱管线+终端默认行为）——监听器表全树共享 | 任意作用域注册任意作用域派发可见；无持久化、无跨进程 | DemoMain DEMO_LOG_CHANNEL |
| E2 | 内核状态事件 | `ctx.on("plugin/status", ...)` | 载荷 PluginStatus(plugin, from, to) | Demo 观测、状态页 |
| E3 | 工具域事件 | 同 C2 | 插件可听面中唯一携带"模型活动"信号的事件（见第 3 节） | HooksPlugin |

## 2. 内核独占什么——独占面清单（9 项）

| # | 独占面 | 现状 | 独占后果（插件挂不上什么） | 开放代价预估 |
|---|---|---|---|---|
| C1 | **呈现位身份** | `PRESENTER_CLI="cli"` / `PRESENTER_WEB="web"` 常量钉在 ChatAgent；亲和路由、后台任务归属按常量判 | 第三呈现位无合法身份（headless 只能在 example 自造常量绕行）；"注册新呈现位"不存在 | 呈现位自报身份/注册表服务化——中型接口手术 |
| C2 | **agent 执行链装配** | chatAgent 全参重载 14 参（PresenterAssembly 内 10 个叠层重载）+ ToolCallingAgent 14 参构造；CLI/Web/HeadlessRunner 三处同构接线串；`CliPlugin.registerCommands` 17 参为全仓最大装配点 | 新能力挂进执行链只能"加构造参数"=改内核；三呈现位锁步接线（M23 headless 漏挂事故根因） | 参数对象化+装配模板化——已列 M28 挂账 |
| C3 | **agent 循环边界** | turn 起止、收件箱两级注入、steer、协作式中断全部内嵌 ToolCallingAgent 与呈现位 | goal/调度类插件无法感知 turn 边界、无法触发新一轮、无输入通道 | 循环挂点设计（agent 域新增接口）——大体量，1.0 后 |
| C4 | **会话事件面** | `Session.append` 只写 JSONL + 本会话私有监听器列表；Session 实例经构造器/Holder 直连呈现位（CliPlugin SessionHolder、WebFace currentSession），**从未作为服务发布**；全仓 main 代码无一处把会话事件转发 Context 总线 | 插件听不到任何消息流（统计/审计/自动化类插件无数据源）；CLI 过程行、headless 投影全靠装配期直连 | 会话事件桥或会话服务化——超出接口手术精度，M28/1.0 后 |
| C5 | **WebFace 端点** | 端点在私有 registerEndpoints，路由/标签/审批/导出全内嵌（单文件 1800+ 行） | 插件加不了 HTTP API、加不了页面 | 端点注册口设计 + WebFace 拆分——M28 挂账 |
| C6 | **前端渲染** | app.js 按工具名字面量硬编码渲染三类卡片；未知工具静默忽略 | 新工具插件在 Web 无呈现路径（后端注册成功、前端隐身） | 元数据驱动渲染——backlog「工具名协议化渲染」 |
| C7 | **装载机制** | Boot yml 手写 FQCN 行反射装载；无目录扫描、无 jar 级装载、无批量自启声明 | 第三方插件接入必改配置+classpath（本里程碑 demo 口径 Q1 的边界来源） | 装载器扩展——1.0 后按需 |
| C8 | **llm / session 实例** | LLM 适配器、Session 实例不走服务注册表，装配期构造直连 | 插件无法换 provider、拿不到当前会话（听、查、注入全部不可达） | 与 C2/C4 同域，随其解 |
| C9 | **治理与准入内核面** | 治理链内嵌 ToolCallingAgent；llm.models 白名单、权限 deny 规则恒优先等 fail-closed 面归内核 | 插件不能改治理判定（**设计取向非缺陷**：治理权不外放） | 有意不开放；本册记边界现状 |

## 3. 插件可听事件面（事实卡，04 demo 与 03 预演的直接依据）

**可听**（全部经 Context 事件总线，监听器表全树共享）：
1. `plugin/status`——插件状态迁移广播
2. `tools/pre-execute` / `tools/execute` / `tools/post-execute`——工具三段瀑布（前提 "tools" 在册；载荷 ToolExecution 含工具名与结果）
3. 插件间自定义事件（任意通道名、五式派发）

**不可听**：
- **全部会话事件**（user/assistant message、tool/call、tool/result、todo/write、context/compacted 等）——只写 JSONL + Session 私有监听器，无桥（2026-09-27 全仓核实：main 代码 `.emit(` 在 session/agent/web/cli/session-query 模块零命中）
- Web SSE（HTTP 通道，非插件总线）

**结论**：插件最接近"会话活动"的信号是 `tools/post-execute`（有工具调用与结果、无消息文本）；推送式会话监听现状不可行——缺的挂点即独占面 C4。

## 4. 服务名全表 ×19（提供方实测）

| 服务名 | 模块 | 提供方（实测 provide 调用点） | 主要消费方 |
|---|---|---|---|
| tools | tools | ToolsPlugin | HooksPlugin、各工具插件、装配层 |
| answers | tools | InteractionPlugin | CliPlugin / WebPlugin（回答者注册） |
| approval | tools | ApprovalPlugin / InteractiveApprovalPlugin / WorkspaceApprovalPlugin **三选一互斥** | 工具执行链 |
| workspace、backgroundTasks | tools | FsToolsPlugin | fs 工具族、装配层 |
| permissionRules | tools | PermissionRulesPlugin | 审批链 |
| connectorStatus | tools | McpClientSupport（单例 shared()，装配期发布） | CLI 状态行、Web 状态面 |
| prompts | agent | PromptPlugin | 装配层（chatAgent 每轮组装） |
| commands | agent | CommandsPlugin | CliPlugin / WebPlugin |
| skills | agent | SkillsPlugin | skill 工具、/技能名 直调 |
| memory | agent | MemoryPlugin | 装配层（每轮 meta_user 通道） |
| agentsMd | agent | AgentsMdPlugin | 装配层（每轮现发现现读） |
| fileRefs | agent | **无常驻提供方**——WebPlugin 装配期自产自用（WebPlugin.java:131 构造、:256-257 直传 WebFace，注释原话「不走服务声明」）；服务名常量在册但生产路径不经注册表（⚠️ 服务机制旁路，02 扫描册素材） | WebFace @提及补全（直传） |
| subagents | agent | SubagentPlugin | spawn/fork/send_message 工具族 |
| **presenter** | agent | **PresenterAssembly 装配期发布 SubagentHost**（⚠️ 与「呈现位」词汇撞名，02 扫描册条目） | 子代理过程行回传呈现位 |
| attachments | attachment | AttachmentPlugin | read_image、Web 授权读取 |
| sessionQuery | session-query | SessionQueryPlugin | session_search 工具、Web 搜索框 |
| greeting、temp-news | example | GreetingPlugin / DemoMain | 演示 |

**边界要点**：llm 与 Session 无服务名（装配期直连，独占面 C8）；模块依赖方向 core ← 基础域(llm/session/attachment) ← 功能域(tools/agent/mcp/hooks/session-query) ← 呈现位(cli/web) ← example 全家桶——功能域服务"同 classpath 才可消费"，第三方独立交付依赖 jar 化装载（独占面 C7）。

## 5. 对后续工单的直接结论

- **04 demo**：所需四个插口（E3 事件监听、D1 命令、C1 工具、B3 服务消费）全部在册且先例齐全——**可行，无预期卡点**。
- **03 预演**：goal 卡点对应独占面 C3（+C4 再触发）；schedule 卡点对应 C3/C4（无调度服务 + 提醒无进对话通道）；agents[] 卡点对应 C7（+C1 呈现位无关）。三件预判均可在本册找到对应独占面，推演即验证。
- **06 升格**：第 1、3、4 节为「插件扩展点清单」文档站底稿的主体素材。
