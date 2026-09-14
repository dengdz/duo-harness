# M10 Spec：Web 可靠性与体验加固

> ADR-0007 v3 渐进序列第七站（方向经 2026-09-14 grill 裁定，原提案"Web 生产化"）。M8 交付 Web 最小面后，本里程碑不做暴露与部署（用户裁定：局域网多设备"目前不需要"），而是把 Web 面作为**本机个人工具**做扎实：正确性修复（安全缺口、fail-closed 语义、纯 Web 装配残缺）、可靠性升级（SSE 增量回放、会话占位检测）、体验加固（Markdown 渲染、错误可见化），并把 M9 治理的计量源从本地估算切到 provider 真实 usage。参照物：DSH 0.1.5 源码（快照+seq 游标协议、usage 全链路，见 [docs/research/DSH/](../../docs/research/DSH/)）。
>
> **过程记录**：grill 结论——鉴权出局（记 backlog）、零构建维持但允许拆多文件、usage 用真实值（估算降为兜底）、SSE 增量协议做（Q9=A）、CLI/Web 并发做占位检测不做完整锁。理解关卡欠账（M3/M4/M6/M7/M8/M9）维持延后。

## Problem Statement

M8 交付的 Web 面是"最小面"：能对话、能审批、能看状态，但作为每天使用的本机工具，它有几类问题一天天在积累。正确性上：会话切换的 id 未净化可目录穿越；请求体无上限；错误响应回显异常消息；纯 Web 装配下 ask_user 与计划工具根本没注册——前端卡片代码依赖的供给不存在；浏览器刷新的窗口期可能让悬空审批被错误拒绝或永久悬置。可靠性上：SSE 每次连接全量回放整个会话日志，M9 之后 80K token 会话是真实场景，事件数上千时每次刷新都要重放一切；CLI 与 Web 同时开同一会话会静默分脑（JSONL 交错追加、两侧上下文分叉），唯一防线是一行 yml 注释。体验上：agent 回复的代码块与列表全部平铺成纯文本；发送无加载态、出错只认 409 一种、大量错误被静默吞掉。计量上：治理判定与状态展示用的本地估算有 ±10-20% 误差，而 provider 明明能报真实值——估算本该只是兜底，现在却唱主角。

## Solution

沿用 M8 的技术形态（JDK HttpServer + 无构建静态页 + SSE），做四组加固。**正确性**：会话切换 id 白名单校验、请求体上限、错误消息脱敏；WebPlugin 补注册 ask_user 与 ExitPlanModeTool，纯 Web 部署的 HITL 闭环补全；会话文件加进程级占位检测——第二个进程打开同一会话时明确报错退出，不再静默双写。**可靠性**：SSE 帧携带事件序号（id），浏览器重连经标准 Last-Event-ID 只补缺失段，首次连接仍全量快照，游标对不上时全量重发兜底；fail-closed 的触发语义钉死并用测试固化——全部连接离场即拒、刷新"断旧立新"窗口不误杀（新连接回放续答）。**体验**：助手消息经 Markdown 渲染（vendor 单文件库入库，无运行时外联，输出经消毒防注入）；发送有加载与禁用态；错误统一可见，不再静默。**计量**：LLM 请求带 include_usage，流末真实 usage 落入 assistant/message 事件可选字段，治理判定与状态面优先消费真实值，provider 未报时退回估算兜底；状态区新增"上下文占用：输入 N + 输出 M / 窗口 W（占比）"，与治理阈值同屏对照。前端拆为无构建的多静态文件（HTML/JS/CSS 分离），维持 M8 零构建定案。

## User Stories

1. As a Web 面使用者, I want agent 回复里的代码块、列表、标题按 Markdown 正常渲染, so that 我不用在平铺文本里辨认结构
2. As a Web 面使用者, I want 发送消息后按钮立即进入禁用与加载态, so that 我知道 agent 正在处理、不会连点重发
3. As a Web 面使用者, I want 任何请求失败都在页面上看到明确错误提示, so that 问题不再被静默吞掉、我能判断该重试还是该报 bug
4. As a Web 面使用者, I want 刷新页面后从断点续收事件而不是重新下载全部历史, so that 大会话的刷新秒级完成
5. As a Web 面使用者, I want 刷新页面后悬空的审批卡片还在并能正常作答, so that 刷新不会把我的待办误杀
6. As a Web 面使用者, I want 关闭全部页面后悬空的审批确定被拒绝, so that 关页不会留下"被默许"的执行
7. As a Web 面使用者, I want 状态区随时看到当前上下文占用与治理阈值的对照, so that 我知道 compaction/修剪还有多远
8. As a Web 面使用者, I want 状态面显示的是 provider 报告的真实 token 用量, so that 占用数字可信、不再带着估算误差
9. As a 纯 Web 部署的使用者, I want ask_user 提问和计划呈交在只有 Web 的部署下照常弹出, so that HITL 能力不依赖终端在场
10. As a 同时装了 CLI 和 Web 的使用者, I want 第二个进程打开同一会话时得到明确报错, so that 我不会不知不觉制造分脑会话
11. As a Web 面使用者, I want 会话切换请求里的非法 id 被直接拒绝, so that 恶意或损坏的请求读不到会话目录之外的文件
12. As a Web 面使用者, I want 超大请求体被拒绝, so that 一次误粘贴不会拖垮服务
13. As a Web 面使用者, I want 错误响应不回显内部异常细节, so that 本机日志之外的渠道拿不到路径等内部信息
14. As a 框架维护者, I want 前端拆为 HTML/JS/CSS 多文件, so that 724 行单文件不再继续膨胀、改动有落点
15. As a 框架维护者, I want Markdown 渲染库源码入库而非 CDN 外链, so that 离线可用且不引入运行时供应链面
16. As a 框架维护者, I want LLM 输出经消毒后才插入页面, so that 模型输出中的注入脚本不可执行
17. As a 治理机制维护者, I want 治理判定优先用 provider 真实 usage、估算只作兜底, so that 触发阈值不再被 ±10-20% 的估算误差干扰
18. As a 会话域维护者, I want usage 作为 assistant/message 的可选字段落进事件日志, so that 事件词汇向后兼容、历史会话不受影响
19. As a Web 面使用者, I want SSE 断线自动重连后只收缺失的事件, so that 网络闪断不造成重复渲染
20. As a 框架维护者, I want fail-closed 的触发条件有测试钉死语义, so that 后续改动不会无意放宽"无人即拒"的安全底线
21. As a CLI 使用者, I want 我正打开的会话被 Web 进程尝试打开时对方被拒, so that 我的会话不会被外来写入污染
22. As a 插件开发者, I want 以上修复全部限定在 web/llm/session 域的增量改动, so that 机制核（审批策略、guard、管线）零触碰

## Implementation Decisions

- **里程碑更名**：方向由"Web 生产化"更名"Web 可靠性与体验加固"——暴露与部署出局（用户裁定局域网多设备暂不需要），loopback-only 维持 M8 定案不做鉴权。
- **SSE 增量回放**（详见 ADR-0010）：每个事件帧带 `id:`（取事件在会话日志中的序号）；浏览器 EventSource 重连自动携带 Last-Event-ID，服务端只发其后的事件；首次连接（无游标）保持全量快照回放 + `replay/done` 边界帧；游标无法对齐（超出日志范围）时全量重发兜底。run/error、replay/done 等非会话帧不带数字 id。选 SSE 原生游标而非 DSH 式自研协议：DSH 的 mux/generation 分层服务多会话多流场景，duo 单向推送用标准机制即可获得同等语义。
- **fail-closed 语义钉死**：触发判据 = "是否存在还能看见该审批的连接"。全部连接离场 → fail-closed（既有行为）；浏览器刷新"断旧立新"窗口 → 不触发（新连接经回放重新渲染审批卡片、可继续作答）；实现须消除"旧连接写失败摘除、新连接尚未入列"窗口的误杀路径（去抖或等效机制），语义以测试固化。
- **usage 真实值治理**（详见 ADR-0009）：OpenAI 兼容适配器请求带 `stream_options: {"include_usage": true}`，从流末 usage chunk 捕获，作为 `assistant/message` 事件的**可选字段**落日志（M4 可选字段先例，向后兼容）；治理组件（spill/修剪/压缩判定）数据源改为"最近响应的 prompt + completion tokens（真实值优先，无则维持本地估算兜底）"；估算代码路径保留不删——治理是硬闸门，provider 不报 usage 时不能失效。
- **上下文占用展示**：`/api/status` 响应增加占用字段（与治理同源同口径）；状态区新增一行"上下文：输入 N + 输出 M / 窗口 W（占比 %）"，与治理阈值同屏对照；会话累计总量等统计不在本期首屏（数据已具备，后续按需加）。窗口大小沿用治理既有配置。
- **占位检测**：会话打开（load/create/latest 选中）时对 JSONL 文件取得进程级文件锁并持有至会话关闭；取锁失败即明确报错（Web 启动失败并指明被占会话、CLI 提示、/api/session/switch 返回占用错误），不做跨进程写协调。这是"单写者检测"：防静默分脑（BUG-20260914-01 的结构性根源），不解决协作共写。
- **安全/正确性三处**：会话切换 id 按格式白名单校验（拒绝路径分隔符与 `..`）；POST 请求体设大小上限（超限拒收）；错误响应体改通用描述、不回显异常消息（异常细节留在服务端控制台日志）。
- **纯 Web 装配补全**：WebPlugin 装配链补注册 ask_user 与 ExitPlanModeTool（对齐 CLI 装配清单），提问卡与计划卡的供给补齐。
- **Markdown 渲染**：vendor 单文件渲染库（marked.js，约 40KB）+ 消毒库（DOMPurify）入 classpath 静态资源，源码入库、无 CDN、无构建链、无包管理器——符合 M8 零构建定案的字面与精神（依赖已获用户同意：Q6=A；消毒件为渲染的安全必备，同性质 vendor）。代码块本期只做等宽样式，语法高亮留 backlog。
- **前端拆分**：单文件拆为 index.html + app.js + theme.css 三个 classpath 静态资源；交互反馈（发送禁用/加载态、全局错误提示、refreshSessions/refreshStatus 错误处理）随拆分落。

## Testing Decisions

- **只测外部行为**：HTTP 端点请求/响应形态、SSE 帧序列、会话事件日志内容、治理判定输入输出；页面 JS 内部不自动化（M8 定案，浏览器端人工验收）。
- **测试 seam（全部复用既有，无新 seam）**：
  - **HttpServer 端点 seam**（M8 先例 WebFaceTest）：扩展覆盖 SSE 帧内容——回放帧带递增 id、携 Last-Event-ID 请求只收到其后事件、游标越界全量兜底、`replay/done` 无 id；安全三处——非法 id 拒绝、超限请求体拒绝、错误响应无异常消息；`/api/sessions` JSON 形态（M8 零覆盖，补上）。
  - **Session 监听器 + 事件日志 seam**（M8/M4 先例）：usage chunk 到达后 assistant/message 事件含 usage 可选字段；历史会话（无 usage 字段）加载不受影响。
  - **治理判定 seam**（M9 先例 ContextGovernance 测试）：真实值优先、估算兜底两条路径各自触发的判定正确。
  - **mock LLM 流 seam**（MockOpenAiServer 先例）：流末带 usage chunk 的响应；provider 不报 usage 的响应。
  - **占位检测 seam**（新覆盖，基于文件锁行为）：同一会话文件被第二个 Session 实例打开时报错、原持有者不受影响。
  - **装配 seam**（AgentReplMainTest 先例）：WebPlugin 装配后 agent 工具清单含 ask_user 与 ExitPlanModeTool；REPL 回归确认 CLI 面不受影响。
  - **fail-closed 语义**（WebAnswererTest 先例）：全连接离场触发；"断旧立新"刷新序列不触发。
- **测试环境坑**（交接备忘）：跑 WebFaceTest / ToolCatalogTest / AgentReplMainTest 前先杀 18080 监听进程。

## Out of Scope

- 鉴权 / 访问令牌 / 局域网暴露 / HTTPS / bind 配置——用户 2026-09-14 裁定暂不需要，backlog 记档，"要给别人用"时重启
- 部署形态（打包发行 / docker / 常驻服务）——backlog
- 会话切换无刷新（location.reload 消除）——与增量协议是天然一对，留待下期与"加载更早分页"一起做（DSH 50 条/页参照）
- 工具名硬编码的协议化渲染（ask_user/exit_plan_mode 魔法字符串）——backlog
- 状态面 5s 轮询改推送、语法高亮、视觉打磨（"可用非惊艳"）、暗色主题——backlog
- usage 的计费口径统计（cache 命中率、分桶明细）——数据落日志后按需加
- WebSocket、前端框架、构建链——M8 技术定案维持
- CLI 与 Web 的跨进程写协调（占位检测只报错不协作）——真需求出现再升级

## Further Notes

- 两篇新 ADR：ADR-0009（治理计量切 provider 真实 usage、估算降为兜底，修正 M9 spec 的估算定案）、ADR-0010（SSE 快照+游标增量回放，修正 M8 的全量回放定案）。
- DSH 参照勘误：总览.md 所写 `/api/events.mux` 实为 `/api/remote.mux`（"事件"是 mux 上的逻辑端点 `$events`）——研究笔记已顺手修正。
- fail-closed 刷新竞态的侦查结论经复盘修正：M8 现状"新连接先入列则不触发"对刷新场景恰好是**正确语义**（刷新后用户仍在、回放含审批卡片可续答）；真实缺陷是"写失败摘除 + 新连接未入列"窗口的误杀路径——本期的任务是钉死语义并消除误杀，不是把刷新变成触发。
- M8 遗留文档不符（WebPlugin javadoc 称"LLM 缺失时状态面仍可看"与实际整插件 FAILED 不符）随工单顺手修正。
- backlog 变动：provider usage 捕获与 token 可见化两条清账（进本期）；新增鉴权暴露裁定、会话切换无刷新、语法高亮、计费统计等条目。
