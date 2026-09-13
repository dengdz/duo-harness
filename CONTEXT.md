# duo-harness

Java 实现的插件化 AI agent harness：内核是自研轻量插件容器，所有能力（工具 / MCP / 页面 / agent 循环）以插件形式组装。架构参考 DSH（deepseek-harness），研究资产在 `docs/research/DSH/`。

## Language

### 容器内核

**Context（上下文）**:
插件能看到的唯一句柄：服务的寻址入口、生命周期副作用的注册点、事件的监听点。内核的 Context 实例是动态代理；一个实例对应一个插件实例的作用域，子插件获得子 Context。
_Avoid_: 容器、App

**视图接口（View Interface）**:
服务域声明的类型窗口：一组以服务名命名的方法接口。插件经 `ctx.as(XxxView.class)` 取得视图，动态代理按方法名解析到字符串服务名。是 DSH "declare module 类型增强"的 Java 对应物，第三方插件自定义视图无需改核心。
_Avoid_: Context 接口、declare module

**服务（Service）**:
具名能力的提供物。身份 = 字符串服务名，类型由视图接口约定，由服务提供者插件发布到注册表。同一服务名在不同作用域可解析到不同实现。
_Avoid_: 组件、Bean

**服务名（Service Name）**:
服务的字符串身份，全局扁平命名空间。harness 保留裸名（`tools`、`llm`、`sessions`…），第三方服务加前缀。
_Avoid_: 服务 ID

**插件（Plugin）**:
扩展单元：一段声明依赖（inject）并提供 `apply` 入口的代码。插件是描述，加载后才有生命。
_Avoid_: 扩展、模块（模块指 Maven 模块）

**插件实例（Plugin Instance）**:
一次插件加载的运行时实体：持有子 Context、依赖表、副作用栈与生命周期状态。同一插件可多次加载各得一个实例。
_Avoid_: Fiber、插件对象

**inject（依赖声明）**:
插件静态声明的服务名列表，是其启动的前提：全部就绪才启动，任一消失即停止。
_Avoid_: require、dependsOn

**effect（可逆副作用）**:
随插件实例生命周期自动回滚的注册物（监听器、子服务、子插件…），回滚按注册逆序。插件卸载即全部 effect 回滚——"拔掉插件"的全部含义。
_Avoid_: 监听注册、资源

### 生命周期

**epoch（依赖指纹）**:
插件实例全部依赖实现的指纹串。指纹变化触发实例自动重载或停止，是免写启动顺序与运行期插拔自动传导的机制核心。

### 事件

**waterfall（瀑布管线）**:
事件分派模式之一：监听器收到 `(参数, next)`，可改写参数、包装返回值或不调 next 直接否决（否决含默认行为）。洋葱模型，工具管线与内部钩子的地基。
_Avoid_: 拦截器链、中间件

**bail（投票）**:
事件分派模式之一：同步顺序询问，首个非空返回值即终止并作为结果。

### 工具域

**三段管线**:
工具执行的固定 waterfall 阶段序列：pre-execute（准入，可否决）→ execute（around 包裹本体）→ post-execute（结果改写或拦截）。审批、超时、结果治理都作为监听者挂在这三段上，不进工具本体。

**config record**:
插件声明的强类型配置载体（Java record），由内核从配置树绑定，绑定失败即加载失败。是插件对外的配置契约。

### MCP 域

**MCP 服务器（MCP Server）**:
按 Model Context Protocol 对外暴露工具的外部进程（如 filesystem server）。duo-harness 经连接使用其工具，自身不实现协议之外的能力。
_Avoid_: MCP 服务（与服务概念混淆）

**MCP 客户端连接（MCP Connection）**:
duo-harness-mcp 与单个 MCP 服务器之间的会话：首连、重连、工具同步都发生在连接上。一个连接对应一个服务器进程。
_Avoid_: 通道、MCP 会话

**工具同步（Tool Sync）**:
把 MCP 服务器暴露的远端工具转换为本地工具并注册进工具域的两阶段过程：先取全量列表校验，再原子换新。同步失败保留旧一代工具继续服务。
_Avoid_: 工具导入、工具拉取

**审批策略（Approval Policy）**:
`ask` 决策的裁决者：治理插件或工具声明某次调用需审批时，由它判定放行或拒绝并署名策略来源。M2 提供预设实现（always-deny 默认 / auto-approve 白名单）；交互式审批是未来的一种策略实现，机制与形态分离。
_Avoid_: 权限、许可、授权

**ask（审批请求）**:
pre-execute 的第三种决策（与 allow / deny 并列）：声明"此调用需要审批"而不自行裁决。声明与裁决分离——声明者（治理插件或工具）只置位，裁决由审批策略服务承担；请求无人解析时按"未配置即拒"处理。
_Avoid_: 待审批、挂起

**输出契约（Output Contract）**:
工具经 `output()` 声明的结果 JSON Schema 与执行后的校验行为：结果违约转 error 结果并点名原因（与工具异常同一出口）。本地工具与远端工具（声明 outputSchema 者，同步时带入）同标准；未声明者宽松透传。
_Avoid_: 返回值校验

**guard（单调否决）**:
工具执行前（审批之后、本体之前）的动态否决检查：返回理由即拒绝，没有"允许"结果，后续 guard 与监听器都无法把拒绝翻回允许。guard 随注册作用域销毁自动摘除；与三段管线的可否决监听器是两种能力。
_Avoid_: 拦截器、守卫



### LLM 域

**LLM 适配器（LLM Adapter）**:
provider 中立的流式调用契约：stream（直答，chunk 回调）与 streamTurn（agent 循环，聚合为结构化一轮）。换 provider 只换适配器实现，消费方不感知协议差异。
_Avoid_: 客户端、SDK 封装

**思考内容（reasoning content）**:
思考模型（thinking mode）在流式响应 `delta.reasoning_content` 中携带的推理过程：适配器按序捕获，工具调用链中按 provider 要求回传最近一轮。不落会话日志（已知限制清单）。
_Avoid_: 思维链、CoT

### 会话域

**会话事件溯源（Session Event Sourcing）**:
会话以不可变事件序列为唯一事实：`append` 是唯一写入原语并同步落 JSONL，读取侧投影出对话消息。崩溃安全、可回放，旧格式文件向后兼容。
_Avoid_: 聊天记录、历史消息表

**投影（Derive Messages）**:
从事件日志推导对话消息视图的纯函数（`deriveMessages`）：user/assistant 消息与工具调用事件按规则入列，流式 chunk 不投影。投影不回写事件。
_Avoid_: 缓存、快照

### agent 循环

**工具循环（Tool Loop）**:
agent 的核心循环：LLM 自主发起 tool_calls → 工具经三段管线与治理链执行 → 结果以 TOOL 消息回填 → 继续调用直至最终回答或迭代上限。harness 不替模型决策，只执行与治理。
_Avoid_: 自动执行、链式调用

**Function Calling**:
模型发起工具调用的协议机制：请求携带工具清单（ToolSpec），响应返回带 id 的调用请求，结果按 id 关联回填。OpenAI 兼容协议形态。
_Avoid_: 插件调用、API 调用

**迭代上限（Max Iterations）**:
单次 send 允许的最大 LLM 往返轮数（默认 10）：超限返回错误说明而非无限循环，防异常任务烧 token。
_Avoid_: 递归深度

**prompt 注册表（Prompt Registry）**:
agent 域的 "prompts" 服务：插件经 `register(registrant, fragment)` 贡献提示片段，随注册作用域自动摘除；每轮请求按注册序动态组装为最终 system 提示。yml 的 `llm.systemPrompt` 是排在最前的用户指令片段，全部为空才落内置缺省。M7 技能指令段的挂载点。
_Avoid_: 模板引擎、prompt 管理

**prompt 片段（Prompt Fragment）**:
注册进 prompt 注册表的最小提示单元：(source 名, content)。source 标记贡献者身份，便于审计与点名。
_Avoid_: 模板、段（与分节语义混淆）

**交互 seam（Interaction Seam）**:
审批与提问共用的机制层：策略或模型只声明"需要人来答"，实际作答交给已注册的回答者。机制（声明、遍历、fail-closed）与呈现（终端 / Web）分离——随宿主演进只换回答者，不动机制。
_Avoid_: UI 回调、弹窗

**回答者（Answerer）**:
注册进交互服务的呈现端实现：接收交互请求（审批 / 提问），呈现给人并返回回答。AgentRepl 注册 console answerer（M6），Web 面注册 web answerer（M8）。随注册作用域自动摘除。
_Avoid_: 监听器（listener 只观察不作答）、回调

**fail-closed（无答即拒）**:
交互请求没有任何回答者在场、或人未作答（EOF / 中断 / 超时）时，一律按拒绝处理——交互缺失永不等于默许。
_Avoid_: 缺省放行、超时通过

**提问工具（ask_user）**:
模型发起的交互工具：参数含 question 必填文本、可选 options 选项数组与 multiSelect；执行本体即"经交互 seam 等人作答"，回答作为工具结果回填，模型据此继续。问与答复用 tool/call、tool/result 事件留痕。
_Avoid_: 问卷、表单



### 技能域

**技能（Skill）**:
SKILL.md 定义的流程能力包：name + description（frontmatter）+ 指令正文。启动时从发现根扫描加载；模型经 skill 工具按名加载指令、用户经 `/技能名` 直调。
_Avoid_: 插件（插件是代码）、命令

**发现根（Discovery Root）**:
技能目录的优先级序：`.duo/skills`（项目）→ `.agents/skills`（项目，行业标准）→ `~/.duo/skills`（用户）→ `~/.agents/skills`（用户），同名高优先根胜。仅启动扫描，不做热加载。
_Avoid_: 搜索路径、classpath

### 计划模式

**计划模式（Plan Mode）**:
引导式的工作形态：激活时挂计划指导片段（先探索再设计、不做修改性操作），状态存于 plan/mode 会话事件（续接恢复）。不硬禁工具——写操作的防线是交互审批。
_Avoid_: 只读模式、沙箱

**计划呈交（exit_plan_mode）**:
模型完成设计后调用：计划全文经交互 seam 呈交用户复核——批准写 exited 事件并开始执行，打回反馈进结果继续改计划；fail-closed 保持计划模式。
_Avoid_: 确认弹窗

**AGENTS.md 注入**:
用户全局（~/.duo/AGENTS.md）与项目根（.git 定根）的 AGENTS.md 内容按序拼接（64KB 预算截断），注册为 prompt 注册表片段——项目约定对运行时 agent 自动可见。
_Avoid_: 系统提示词模板

### 运行环境

**Duo home（~/.duo）**:
duo-harness 的用户级默认目录：会话、配置等运行时数据统一收在其下（`DUO_HOME` 环境变量可整体重定向）。密钥与个人配置放这里，天然不入仓库。
_Avoid_: 工作目录、安装目录
