# M24 权限与安全深化——spec

> 决策依据：[ADR-0026](../../../docs/adr/0026-M24权限与安全深化六裁定.md)（六裁定 + 决策七 Anthropic 适配器与 provider 声明）+ ADR-0024 M24 节（范围）+ 本期 grill 二十问逐题裁定（2026-09-22）+ spec 期三问（effort 落地面、provider 识别、测试 seam）。事实基础：docs/research/ 两家探测文档——工单 08（只读命令识别与规则，ZCode/DSH 注记）、07（审批与提问交互，两家）、20（存储配置与凭据，两家）、14（MCP，ZCode）、18（桌面端）、01（投影恢复与分页/模型保存意图，ZCode）、09（LLM 调用层，两家）。

## Problem Statement

M23 放开了后台执行，用户享受「跑得起的命令行」的同时，控制面的缺口变得扎手：bash 与未知工具一律弹审批，同一类安全命令反复批，烦了要么手滑全放（danger-full-access）要么被卡死；审批卡没有「总是允许」，记忆规则无处安放；计划模式只靠提示词劝阻，模型仍能看见并调用写工具，「先探索再设计」防线名不副实；两个 MCP 服务器工具清洗后同名直接启动失败，服务器挂掉工具静默消失；Web 面零鉴权（局域网裸奔）、多标签互踩同一会话（后台任务时代一踩一个准）；换模型要改 yml 重启、思考深度完全没有调档入口。

## Solution

权限规则引擎落地「放得开也收得住」：审批卡新增「总是允许（项目）/仅本会话」（CLI 键位 a/s），项目级规则持久在 `.duo/settings.json`、会话级随会话事件流走（resume 恢复）；deny 手写且恒优先；sudo/rm 等十个高危根命令生成与运行时双拦，永不免审。约 30 个只读命令 + git 四件套三态识别，true 即免审批——只读探索不再打断人；复合结构一律 fail-closed 回审批。计划模式改硬禁：非白名单工具不注入模型 + deny 兜底。MCP 工具名一律哈希后缀防坍缩，重连耗尽发会话事件并状态面标注。Web 面加鉴权令牌（启动打印带 token URL、fail-closed 403、可显式关但横幅警示），标签级会话绑定让新标签默认新建会话、多标签各干各的。/model 在 yml 白名单内运行时切模型、resume 提示不自动切；/effort 四档思考等级按 provider 映射（本期连 Anthropic-messages 适配器一起补齐，四行映射全量真落地），不支持即显式降级标注。

## User Stories

**权限规则引擎（CLI/Web/模型）**

1. 作为 CLI 用户，同类 bash 命令反复批准不胜其烦，我在审批卡按 a（总是允许·项目），此后该项目匹配前缀的调用直接放行不再弹卡。
2. 作为 CLI 用户，我只想临时放行当前会话，我按 s（仅本会话），规则不写进项目文件、会话结束为止。
3. 作为用户，我 resume 续接会话时会话级规则仍生效（随会话事件恢复），不用把刚批过的重新批一遍。
4. 作为用户，我在 `.duo/settings.json` 手写 deny 规则后，匹配的命令/工具直接拒绝，且 deny 恒优先于任何 allow（与 guard 单调否决同构，翻不回来）。
5. 作为用户，审批卡对 sudo/su/doas/rm/dd/mkfs/chmod/chown/shutdown/reboot 十个高危根命令不出现「总是允许」键，我不可能一键永久放行提权/破坏命令。
6. 作为用户，即使我手写 `Bash(sudo:*)` allow，运行时也拦下并日志说明——高危是「永不放行」不是「别一键生成」。
7. 作为用户，我用 `/permission rules list` 看到项目级与会话级全部生效规则（含来源与作用域署名），用 `/permission rules rm <n>` 删掉误生成的规则。
8. 作为用户，`Bash(ls:*)` 规则不会误放行 lsof/lsblk——词边界匹配，短前缀吞不掉危险长命令名。
9. 作为 Web 用户，审批卡有「总是允许（项目）/仅本会话」按钮，语义与 CLI 键位一一对应。
10. 作为 Web 用户，多卡排队时我点某张卡的「总是允许」，生成规则与放行的是那张卡对应的调用（按卡片 ID 回填），绝不错卡。
11. 作为模型，被规则放行的调用我直接拿到工具结果，被 deny 规则拒绝时拿到带规则来源的理由，能向用户解释是谁拦的。

**只读免审批（bash）**

12. 作为用户，模型跑 git status/log/diff/show 与 ls/cat/head 等只读命令不再弹审批卡，探索阶段不再被人肉闸门切碎。
13. 作为用户，管道、命令替换、重定向、别名等复合结构仍走审批——识别 fail-closed，疑罪从有。
14. 作为用户，git 四件套在无 `.git` 的目录不免审（信任分类），`git -C` 指向任意仓库的逃逸被堵死。
15. 作为模型，只读调研我可以连续执行不被打断，任务推进节奏由我掌握。
16. 作为用户，首期约 30 个只读命令的清单在 spec/工单里明确列名，我清楚哪些命令不再弹卡，没有黑箱。

**计划模式硬禁**

17. 作为用户，我进计划模式后模型看不见写/执行类工具（定义不注入），「计划中偷改文件」从劝阻变为不可能。
18. 作为用户，计划模式下模型仍能用只读命令与只读工具充分调研，计划质量不因硬禁而下降。
19. 作为用户，异常路径下仍有写调用到达时，pre-execute deny 兜底拒绝并把理由回给模型，不留悬置态。
20. 作为用户，我批准计划（exit_plan_mode）后工具全量恢复，执行阶段不受任何残留限制。

**MCP 加固**

21. 作为用户，两个 MCP 服务器的工具清洗后同名也不再启动失败——名字带短哈希后缀稳定区分。
22. 作为用户，MCP 工具名在审批卡、权限规则、会话历史里前后一致，重启不漂移，我建的规则永远指向同一条工具。
23. 作为用户，某 MCP 服务器重连预算耗尽时，我收到会话事件通知、状态面标注不可用，而不是工具静默消失让我猜。
24. 作为模型，MCP 服务器恢复后工具原子换新回来，我继续可用，不需要重启进程。

**Web 鉴权与标签绑定**

25. 作为部署者，我启动 Web 后拿到带 token 的 URL，浏览器打开即完成鉴权，全程无感。
26. 作为部署者，无 token 的请求一律 403（fail-closed），局域网里其他设备进不了我的会话。
27. 作为部署者，我在 yml 显式关闭鉴权时启动横幅明示「鉴权已关闭」，裸奔至少裸得明白。
28. 作为用户，我开两个标签页各自独立会话并行操作，互不踩对方的会话状态与审批队列。
29. 作为用户，新标签打开默认新建会话（不弹选择页），要恢复历史仍走面板 resume 入口。
30. 作为用户，升级前打开的旧标签（无本地记录）等同新标签处理，不会绑到别人的会话。

**/model 与 /effort（含 Anthropic 适配器）**

31. 作为用户，`/model` 无参列出当前模型与 `llm.models` 白名单清单，可切面一目了然。
32. 作为用户，`/model <name>` 切清单内模型下一 turn 即生效并落会话事件；清单外名字直接拒切——模型名决定成本面，不能拼错字烧钱。
33. 作为用户，resume 旧会话时若上次模型与当前配置不同，我看到横幅提示与 `/model` 建议，切不切我拍板（不被静默换回贵模型）。
34. 作为用户，`/effort off|low|medium|high` 调思考深度，无参显示当前档；切换下一 turn 生效并落会话事件。
35. 作为 Anthropic 用户，思考档位映射为 thinking + budget 真生效（本期新增 Anthropic-messages 适配器，SSE + tool_use 全链路）。
36. 作为 OpenAI 兼容用户，思考档位映射 reasoning_effort。
37. 作为 GLM 用户，思考档位映射 thinking 开关。
38. 作为 DeepSeek 用户，我调档时看到「思考请切 reasoner 模型」的显式降级标注，而非静默无效。
39. 作为用户，标题生成等辅助请求强制低档，不因我调 high 而烧大钱。
40. 作为配置者，我在 yml 声明 `llm.provider` 四值之一（openai-compat 缺省/anthropic/deepseek/glm），适配器选型、鉴权头形态、effort 映射策略随之确定；不声明时零改动兼容现状。
41. 作为用户，provider 不支持某档位时看到显式降级标注（哪档映射到什么/为什么不生效），永不静默。

## Implementation Decisions

**权限规则引擎（ADR-0026 决策一）**

- 存储两级：项目级持久于项目根 `.duo/settings.json` 的 `permissions` 段（duo 首个项目级设置文件写入器，Jackson 直写，写入失败降级提示不静默）；会话级随会话事件流持久（新会话事件类型），resume 经 latest-wins 投影恢复——与 permissionMode 事件同机制。
- 规则模型：工具名 + 匹配体（bash 为 `Bash(prefix:*)` 记法）+ 决策（allow）+ 作用域（project/session）；deny 规则同构仅来源为手写文件。bash 前缀按词边界匹配（命令名后须紧跟串尾或空白）。
- 裁决序：deny（查全部命令）→ 只读判定（true 即放行，见下）→ allow（只查非只读）→ 既有档位/ask 链。deny 恒优先，落点在 pre-execute 瀑布内、审批闸门之前。
- 高危根命令十名单（sudo/su/doas/rm/dd/mkfs/chmod/chown/shutdown/reboot）：生成拦（审批卡不出「总是允许」键）+ 运行时拦（手写 allow 不生效，命中走 ask/档位并日志说明）。git 破坏子命令级不进首期（backlog）。
- 管理面：`/permission` 扩 `rules list`（两级全量、来源署名）与 `rules rm <n>`；busySafe 沿用既有声明；Web 端本期不做规则管理页。
- 审批四值：决策枚举 allow/deny/always-allow-project/always-allow-session；CLI 键位 y/a/s/n（空回车仍= deny）；Web 卡片对应按钮；Web `/api/answer` 回填改按卡片 ID 关联（销 M23 工单 03 记档的按位置回填坑）。
- 项目根定位复用技能发现根先例（`.git` 标定根）。

**只读免审批（ADR-0026 决策二）**

- 三态策略表组件（工具域 fs 包）：每命令 true/false/undefined，undefined 一律按非只读；首期约 30 个 allowAnyArg 只读命令 + git 四件套 status/log/diff/show（清单本体见工单，落实现时逐条核对参数面无写路径）。
- git 四件套必叠 cwd `.git` 存在性信任分类，防 `git -C` 逃逸。
- 复合结构（管道、`&&`、命令替换、重定向、别名）一律 undefined；解析失败同。
- 接入经「运行时 capability 覆盖静态 metadata」seam：判定结果覆盖工具的 requiresApproval 面貌，落 pre-execute 裁决序，不改工具本体声明。

**计划模式硬禁（ADR-0026 决策三）**

- plan 态工具面收缩：非白名单工具定义不注入模型请求（工具集合按 plan 态过滤）；pre-execute deny 兜底（异常路径到达即拒，理由回模型）。
- 白名单 = 只读策略表判定通过 + 既有只读工具（read/glob/grep/read_image 等）；web_fetch/web_search 维持只读档 ask 语义不放开；ExitPlanModeTool 恒在（批准闭环）。
- plan/mode 事件、续接恢复、指导片段全保留；术语表口径已改写（硬禁）。

**MCP 加固（ADR-0026 决策四）**

- 工具名一律有损规范化 + 短哈希后缀（`mcp__{server}__{tool}__{hash8}` 形态）：哈希对规范化后的全名计算，任何服务器组合下稳定；废弃「清洗坍缩重名即抛错」。
- 重连耗尽（GAVE_UP + 注销既有语义之上）补：会话事件通知（模型与用户可见）+ Web 状态面标注不可用；断连期 tools/list_changed 忽略语义不变。

**Web 鉴权令牌 + 标签绑定（ADR-0026 决策五）**

- 令牌：启动随机生成（安全随机数），控制台打印 `http://127.0.0.1:<port>/?token=<t>`；浏览器首载存 localStorage，后续 HTTP 头 + SSE query 携带；校验失败一律 403；yml `web.auth: none` 显式关闭，关闭时启动横幅警示。token 进程生命周期一次一发，无过期轮换。
- 标签绑定：前端每标签生成持久 tabId（localStorage），握手时上报；服务端单全局会话字段改 `Map<tabId, session>` 多会话并存；新标签默认新建会话（不弹选择页）；无记录旧标签视为新标签；resume 仍走面板手动入口（绑定到发起标签）。审批/提问卡片按 tabId 路由到所属标签。

**/model + /effort + Anthropic 适配器（ADR-0026 决策六、七）**

- yml：`llm.models` 白名单清单（新增，空/缺席=不可切，/model 提示配置方法）；`llm.provider` 四值显式声明（openai-compat 缺省 / anthropic / deepseek / glm）——决定适配器选型、鉴权头、effort 映射策略；provider 不再由 baseUrl 隐式表达。
- Anthropic-messages 适配器（新增，与 OpenAiCompat 并列的仅有的两个协议实现）：SSE 流式、tool_use/tool_result 块映射、system/messages 结构映射、`x-api-key` + `anthropic-version` 鉴权头；effort 映射 thinking + budget。
- /model 命令：无参列出清单+当前；带参仅准切清单内，切换落会话事件（保存意图），下一 turn 生效；首期限同 provider（baseUrl 不变）。
- resume 语义：会话投影暴露意图模型；resume 时意图 ≠ 当前配置模型则横幅提示（含 /model 建议），不自动切。
- /effort 命令：四档 off/low/medium/high 缺省 medium；无参显示当前；切换落会话事件、下一 turn 生效；映射——anthropic→thinking+budget、openai-compat→reasoning_effort、glm→thinking 开关、deepseek→显式降级标注（提示切 reasoner 模型）；请求侧映射由 provider 声明驱动，不支持即显式降级标注（不静默）；辅助性请求（标题生成等）强制 low 档。

**模块改动面**：duo-harness-tools（规则引擎裁决、只读判定器、高危双拦、settings.json 读写器、plan 白名单过滤协作）、duo-harness-llm（Anthropic-messages 适配器、provider 声明与选型、effort 参数映射）、duo-harness-agent（plan 态工具注入收缩、模型意图/effort 会话事件、resume 提示投影）、duo-harness-session（新事件类型：会话级规则/模型意图/effort）、duo-harness-cli（审批四键、/permission rules、/model、/effort、resume 横幅）、duo-harness-web（token 鉴权、tabId 多会话、卡片按钮与按 ID 回填、状态面 MCP 标注）、duo-harness-mcp（命名哈希、耗尽事件回调）、duo-harness-example（yml 字段接线与装配）。

## Testing Decisions

- **好测试标准**：只断言外部行为——裁决结果与策略署名、会话事件序列、模型请求里的工具集合与参数、HTTP 状态码、适配器请求体；不断言内部缓存/线程/Map 实现细节。核心逻辑（前缀匹配、三态判定、哈希命名）走 tdd 红绿循环。
- 八个测试面全部复用既有 seam（spec 期三问确认，零新建）：
  1. **工具域裁决链**：deny 恒优先、allow 只查非只读、高危双拦（手写 allow 仍拦）、会话/项目规则生效、plan deny 兜底。先例：ToolsServiceTest、WorkspacePolicyTest、WorkspaceGatePolicyTest、ApprovalPolicyTest。
  2. **只读判定器 + 词边界前缀（纯函数矩阵）**：三态全矩阵、复合结构全 undefined、git 四件套 + `.git` 信任分类、`ls:*` 不匹配 `lsof`、多词前缀。先例：IgnorePolicyTest（判定器纯函数风格）。
  3. **交互 seam**：y/a/s/n 四值应答、总放行生成规则（高危卡无此二键）、Web 按 ID 回填。先例：InteractionRegistryTest、ConsoleAnswererTest、WebAnswererTest。
  4. **ChatAgent seam（fake LLM）**：plan 态请求工具集合收缩与批准后恢复、模型切换/effort 会话事件与下一 turn 生效、resume 意图提示。先例：SteerInjectionTest、ToolCallingAgentTest。
  5. **LLM 适配器**：Anthropic-messages 请求体（system/messages/tool_use/thinking+budget/x-api-key）与 SSE 解析、reasoning_effort、GLM thinking 开关、DeepSeek 降级标注。先例：OpenAiCompatAdapterTest、LlmConfigTest（provider/models 字段解析）。
  6. **MCP**：哈希后缀命名稳定性（同输入同名、跨服务器组合不漂移）、耗尽会话事件。先例：McpToolSyncTest、ReconnectPolicyTest、ConnectionLifecycleTest。
  7. **Web 面**：无 token 403 / 带 token 放行（含 SSE query）、`auth: none` 关闭路径、tabId 多会话隔离与卡片路由。先例：WebFaceTest。
  8. **装配级**：settings.json 加载→规则生效端到端、`llm.provider` 选型装配、token 横幅。先例：BootTest、CliPluginAssemblyTest。

## Out of Scope

- /model 跨 provider 路由（yml 预声明路由表，1.0 后菜单）；Anthropic 适配器之外的新协议适配。
- safeFlags 只读策略体系（M24 后按需扩，ADR-0024）；git 破坏子命令级高危排除（spec 评估：解析粒度深入子命令参数，backlog）。
- Web 规则管理页（/permission rules 仅 CLI）；deny 规则的审批卡生成；规则多文件/目录式存储。
- 完整多会话协调（跨标签会话列表同步、会话迁移，1.0 后）；token 过期与轮换机制（进程生命周期一次一发）。
- plan 态放开 web_fetch/web_search（维持 ask）；审批枚举第五值（modify/escalate）。
- prompt 更新 in-history 前缀缓存策略（留 1.0 后菜单）。

## Further Notes

- 工单拆分建议按依赖序：规则引擎与只读判定先行（裁决序是全期地基），审批四值与回填身份紧随，plan 硬禁、MCP 加固、Web 鉴权与标签绑定并行面，/model+/effort+Anthropic 适配器收尾（LLM 域自成一体）——以 /to-tickets 输出为准。
- 验收必须覆盖行为迁移的正反两面（ADR-0026 Consequences 显式化要求）：只读放行不弹卡 vs 高危十命令手写 allow 仍拦；双标签并行操作互不踩 vs 单标签行为不回归；token 缺省 403 vs 显式关闭横幅警示。
- limitations 对账随实现销账：M7#1（计划模式硬禁）、M8#4（标签级会话绑定最小版）；backlog 销账于 M24 收口：「/model 运行时切换」「Web 鉴权令牌 + bind」，另 Anthropic-messages 协议适配已从 1.0 后菜单提进本期（ADR-0026 决策七，spec 期对账裁定）。
- 本 spec 与 ADR-0026 决策七增补、术语表 provider 词条、backlog 对账同一批提交（沿 M23 惯例：grill 产出 + spec + 工单一笔提交）。
- 用户可见变更按红线 6 随各实现工单同 diff 记入 CHANGELOG；「M24 启动规划落盘」记账随 0.19.0 发布段（家规无 Unreleased 段）。
