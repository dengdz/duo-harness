# M31 全站大纲与篇目清单

Status: 待用户确认（确认记录记入工单 01 Comments；确认即解锁工单 02-11）

> 本文是 M31（0.27.0）文档官方化的施工总图：逐篇列改造点 / 新增小节 / 引用锚点。范围锚定 [spec.md](spec.md)（立项裁定见 [ADR-0033](../../docs/adr/0033-M31文档官方化立项决策.md)）；口吻标准见 [产品文档口吻规范](../../docs/agents/product-doc-voice.md)（工单 01 同批产出）。代码面清单（命令/参数/配置段）经探索代理盘点并实测抽查（2026-10-01）。

## 总览

| 批 | 工单 | 篇目 | 动作 |
|---|---|---|---|
| 口吻重写 | 02 | 快速开始（原《运行Demo》） | 形态反转改写 |
| 口吻重写 | 03 | 02-指南 1 篇 + 03-高级 3 篇 | 口吻微调 |
| 口吻重写 | 04 | 04-架构 2 篇 + 05-参考 6 篇 | 剥离历期 + 对账补漏 |
| 指南新增 | 05 | 权限与审批、会话管理与恢复 | 新写 |
| 指南新增 | 06 | 斜杠命令、模型与思考档位 | 新写 |
| 指南新增 | 07 | 导出与检索、子代理 | 新写 |
| 指南新增 | 08 | hooks、MCP 接入 | 新写 |
| 参考新增 | 09 | config.yml 全量字段参考 | 新写 |
| 参考新增 | 10 | CLI 参考 | 新写 |
| 参考新增 | 11 | Web 界面使用说明 | 新写 |
| 收尾 | 12 | 首页 index + 全站对账 | 收拢改造 |

---

## 一、口吻重写批

### 快速开始（工单 02，原 docs/01-入门/运行Demo.md 改写）

- **改造点**：形态反转——`java -jar` 一条命令为主干；M1/M2 机制演示叙事全剥；演示入口（DemoMain 等）一句话指针或删。
- **新增小节**：①准备（JDK 21 + `~/.duo/config.yml` `llm` 段最小样例：provider/baseUrl/apiKey/model，key 占位符）；②跑起来（`java -jar duo-harness-<version>.jar` → 终端 REPL + 浏览器双面）；③从源码跑（mvn 命令整块降为次要小节）；④headless 一句话（`--json` 指向 CLI 参考）。
- **引用锚点**：README 三行快速开始（同源）；`DuoMain`；`HeadlessArgs`（--json/--session-id/退出码 2）。

### 02-指南《组装你的第一个 agent》（工单 03）

- **改造点**：口吻核对（用户任务视角已对，剥开发史表述）；demo 装配步骤与 agent-demo.yml 现状对账。
- **锚点**：agent-demo.yml；《插件配置参考》。

### 03-高级三章（工单 03，M29 交付）

- **改造点**：三章（技能编写指南 / MCP 深入 / 多插件协同）按口吻规范逐篇核对——源码锚定与活体引用零松动，仅动叙事；确认无「建设中」类过程残留。

### 04-架构两篇（工单 04）

- **改造点**：设计主线 / 模块划分——保留「为什么这样设计」叙事；剥离 M 期号引用（如「M9 起严格……」类）；模块表与根 pom 13 模块对账（README 已对过，同步）。
- **锚点**：根 pom 模块清单。

### 05-参考六篇（工单 04）

- **改造点**：逐篇与当前版本对账补漏——①插件配置参考（342 行）：段清单与探索盘点对账（web/cli/fs-tools/web-tools/attachment/session-query/prompts/agents-md/memory/skills/mcp/subagent/repeat-reminder）；②工具目录（179 行）：与 ToolCatalog 现状对账；③会话事件类型表：与事件面现状对账（含 assistant/reasoning、command/run|done）；④技能写作规范、⑤插件扩展点清单：口吻核对；⑥术语表：本批不动词条内容（新词条由各工单按需增补）。

---

## 二、指南新增批（02-指南，每篇 = 「我想做 X 怎么操作」）

### 权限与审批（工单 05）

- **小节骨架**：①权限预设三档（/permission read-only | workspace-write | danger-full-access）；②规则引擎（项目 `.duo/settings.json` `permissions` 段 + 会话规则；规则字段 tool/prefix/decision/scope；deny 恒优先）；③审批卡交互（批准/拒绝/总是允许-项目/仅本会话）；④只读命令免审（三态策略表边界）；⑤计划模式（/plan 只读推进、/plan off）。
- **锚点**：`PermissionRules`（settings.json #111/#333/#404-409）；CliPlugin#685；ADR-0026。

### 会话管理与恢复（工单 05）

- **小节骨架**：①会话从哪来（deferred 语义——发首条消息才落盘、空会话不入列）；②侧栏与标签（tabId 绑定、跨进程占用灰点）；③恢复与分页（尾部窗口快照、向上翻页）；④/new、/title、/exit 的会话语义（锁释放、插件保持挂载）。
- **锚点**：CHANGELOG 0.26.0 defer 化条目；ADR-0013（快照分页）；ADR-0026 决策五（标签绑定）；CliPlugin#575-590。

### 斜杠命令（工单 06）

- **小节骨架**：①命令怎么发（CLI 输入行 / Web 输入框同注册表；运行中也可发——busySafe 命令清单）；②按场景分组讲 11 命令：会话流（/new /title /exit /stop）、模型与档位（/model /effort）、权限与计划（/permission /plan）、上下文与成果（/compact /export /toolstats）；③scope 与双面差异（CLI/WEB/ANY——/title /compact /export /permission /toolstats 双面可用）。
- **锚点**：`CommandsRegistry`（#91 分发、#116 审计事件）；CliPlugin#575-718；PresenterAssembly#375-449；ToolStatsPlugin#89。

### 模型与思考档位（工单 06）

- **小节骨架**：①provider 声明与切换（llm.provider 四值：openai-compat 缺省 / anthropic-messages / deepseek / glm 映射）；②/model 与 `llm.models` 白名单（空 = 不可切；resume 意图不自动切）；③/effort 四档（off|low|medium|high 缺省 medium）与 provider 参数映射、不支持时显式降级标注；④思考过程在哪看（Web 思考折叠卡，指向 Web 界面说明）。
- **锚点**：`LlmConfig`#41-44（12 字段）；ADR-0026 决策六/七；ADR-0031 思考卡。

### 导出与检索（工单 07）

- **小节骨架**：①/export（markdown|json 缺省 markdown；CLI 写盘当前目录 / Web 自动下载；重复下载防护——回放不重触发）；②导出报告构成（对话 + 交付清单章节 + 变更摘要章节——模型自报与系统 git 快照对账）；③历史怎么找（Web 侧栏搜索框 + 模型侧 session_search 工具；cwd 授权边界——跨目录会话不进结果）。
- **锚点**：ADR-0028；术语表「会话检索」「交付声明」「变更摘要」；PresenterAssembly#439-449。

### 子代理（工单 07）

- **小节骨架**：①什么时候拆子代理（并行探索/大块独立子任务）；②模板配置（subagent 插件行 `templates[]{name, tools, prompt, maxIterations}`，未知字段 fail-fast）；③知识可见性 opt-in；④任务管理（后台任务查看 task_output / 停止 task_stop / 完成通知）。
- **锚点**：`SubagentTemplates`#60-104；ADR-0015/0017/0018。

### hooks（工单 08）

- **小节骨架**：①hooks.json 在哪（`~/.duo/hooks.json`，用户级）；②两个事件（PreToolUse / PostToolUse 触发时机）；③配置两形态（matcher 组 / Codex 扁平）；④处理器字段（type=command / command / args / timeout 秒缺省 600）；⑤载荷字段（hook_event_name / tool_name / tool_input / cwd / PostToolUse 增 tool_response / presenter_id / DUO_HOME）；⑥退出码语义（deny/审批联动）。
- **锚点**：`HooksConfig`（#40-44、#121-207）；ADR-0019。

### MCP 接入（工单 08）

- **小节骨架**：①从零接第一个 stdio server（mcp 插件行 config：serverName/command/args/env）；②连接行为（启动失败策略 failOnStartupError、重连退避时间线、请求超时）；③确认工具到位（状态面 connectorStatus、工具命名规范化）；④常见排障五分钟（连不上/工具没出现/名字变了）；机制深入一律链接《MCP 深入》不重复。
- **锚点**：`McpConnectionOptions`#26-51；ADR-0006；03-高级/MCP深入。

---

## 三、参考新增批（05-参考）

### config.yml 全量字段参考（工单 09）

- **结构前提**（探索核定）：配置实为「两文件 + 两外置」——`~/.duo/config.yml` 仅 `llm` 顶层段（12 字段：baseUrl/apiKey/model/systemPrompt/retryMaxAttempts/retryInitialBackoffMs/streamIdleTimeoutMs/vision/imageDelivery/provider/models/effort，env `DUO_LLM_*` 逐项覆盖）；Boot 装配 yml（如 agent-demo.yml）`plugins:` 行内各插件 config 段；`~/.duo/hooks.json`（→ hooks 指南）；`.duo/settings.json` permissions（→ 权限指南）。**无顶层 mcp/governance/bind 聚合段**（governance 为 web/cli 行内子段；web 监听地址硬编码 loopback）。
- **小节骨架**：llm 段逐字段表 → 各插件 config 段逐字段表（每字段：类型/缺省值/约束）→ 与《插件配置参考》分工声明（本篇 = 部署者字段手册；编程形态细节归彼）。
- **锚点**：`LlmConfig`#41-44；WebPlugin#43-48/#220/#312；PresenterAssembly#76-110（governance 八字段，keepRecentRatio 已停用）；FsToolsPlugin#96-143；WebToolsConfig#12-39；AttachmentConfig#10-21；SessionQueryPlugin#22-24；PromptPlugin#13-26；AgentsMdPlugin#22；MemoryPlugin#26；SkillsPlugin#24-25；McpConnectionOptions#26-51；SubagentTemplates#60-104；RepeatReminderPlugin#25。

### CLI 参考（工单 10）

- **小节骨架**：①启动参数表（`--json` headless NDJSON / `--session-id <id>` / positional yml 路径 / positional 任务文本——headless 必需缺失退出码 2 / usage 错误契约）；②常驻模式（可选 yml 参数；行序契约：web 行必须先于 cli 行）；③REPL 键位表（Ctrl+C 三态：运行中单击=协作中断、再按=强制退出 130、空闲=退出 0；Ctrl+D=/exit 语义；SIGINT 不可拦环境兜底 = /stop）；④运行中输入行为（busy 期文本 = next-step 插队回显「已插队」；多条 = next-turn 合并开新轮；应答行优先路由审批/提问卡）。无方向键历史/行编辑（阻塞 readLine，如实说明）。
- **锚点**：`HeadlessArgs`#53-90；`DuoMain`#110-147/#39-72；CliPlugin#1107-1145/#66-71/#772-777；ADR-0025。

### Web 界面使用说明（工单 11）

- **小节骨架**：①打开页面（地址/令牌——`web.auth` 缺省随机 token 横幅）；②会话侧栏（deferred「新会话」/状态点两态/标题渐隐滚动/搜索框）；③消息流（打字机流式/工具卡四分型：终端卡·编辑卡·文件卡·技能卡/思考折叠卡/产物预览卡/语法高亮）；④交互卡（审批/提问/计划——谁发起谁作答、双呈现位路由）；⑤状态面五分区（折叠语义）；⑥多标签与会话绑定。
- **锚点**：CHANGELOG 0.25.0（M29 九项 + 工具卡分型 + 思考卡 + 产物卡）；ADR-0026 决策五；ADR-0013。

---

## 四、收尾（工单 12）

- **index 重写**：产品一句话（Java 插件化 agent harness）+ 快速开始前置 + 五章导航（含全部新增篇目）；「文档之外」段收编——research/、agents/ 退出站内导航（文件本体不动，路径契约不变）。
- **nav/sidebar**：指南分组 8 篇、参考分组 3 篇入导航；构建绿；口吻终查抽查；CHANGELOG 0.27.0 段逐单核对。
