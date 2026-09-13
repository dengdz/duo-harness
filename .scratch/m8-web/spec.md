# M8 Spec：Web 双面最小面

> ADR-0007 v3 渐进序列第六站。M6 把"人"接进了交互 seam（CLI answerer），M7 把能力与纪律接进了 agent；M8 换一个呈现位——浏览器——验证 ADR-0008 的核心承诺：**机制不变，换呈现位不动机制**。参照物：DSH host/client 分面与 ui-* 组件族（[docs/research/DSH/核心功能全景.md](../../docs/research/DSH/核心功能全景.md)）。
>
> **过程记录**：M7 理解关卡延后补考（与 M6 同批，见 .scratch/m7-skills-plan-mode/comprehension-pending.md）。界面原型作为独立工单环节（用户增设）：先出 HTML 原型过目确认，再作为对话面/交互卡片的实现蓝图。

## Problem Statement

M7 之后 harness 的全部交互都在终端 REPL：agent 的执行过程（工具调用、审批、提问、提醒）只能由恰好开着终端的人看到。换一台机器、合上笔记本、或者想让不懂命令行的人看一眼 agent 在干什么——都做不到。同时"状态面"（插件六态、工具清单）只存在于启动日志里，事后无法查看。Web 不是锦上添花：它是验证"机制与呈现分离"（ADR-0008）的最终考场。

## Solution

新呈现域 `duo-harness-web`（不立则已，立则正式）：JDK 内置 HttpServer 承载的本地 Web 服务，一个亮色主题的单页双区界面——左区对话（消息流实时渲染 + 输入框 + 交互卡片），右区状态（插件六态表 + 工具清单）。会话事件经 Session 监听器转 SSE 实时推送；审批与提问以交互卡片形式出现在对话区，点选/输入即完成回答（HITL Web answerer，走 M6 交互 seam）；页面断连时悬空请求立即 fail-closed。

## User Stories

1. As a harness 使用者, I want 在浏览器里打开一个页面就能与 agent 对话, so that 我不依赖终端也能使用全部能力
2. As a harness 使用者, I want agent 的回复流式逐字出现在页面上, so that 长回答不需要干等
3. As a harness 使用者, I want 工具调用与结果以卡片形式实时出现在对话流中, so that agent 的执行过程一目了然
4. As a harness 使用者, I want 写操作的审批以按钮卡片出现在页面上, so that 我点一下就完成批准或拒绝
5. As a harness 使用者, I want 模型的提问（ask_user）以选项卡片呈现并可点选或输入, so that 我不需要理解协议就能回答
6. As a harness 使用者, I want 关闭页面后悬空的审批自动按拒绝处理, so that 关页不会留下"被默许"的执行
7. As a harness 使用者, I want 计划模式的呈交与复核也在页面上完成, so that /plan 工作流不依赖终端
8. As a harness 使用者, I want 右侧随时看到全部插件的六态状态, so that 系统健康度一眼可见
9. As a harness 使用者, I want 右侧看到当前挂载的全部工具（含 MCP 远端）, so that 我知道 agent 有哪些能力
10. As a harness 使用者, I want 服务只监听本机回环地址, so that 局域网内的其他设备无法访问我的 agent
11. As a 插件开发者, I want Web 呈现位是标准插件（Boot yml 一行）, so that 不想要 Web 面就不挂载，零残留
12. As a M6 机制维护者, I want Web answerer 只实现 seam 的回答接口而不触碰策略与管线, so that 机制核零改动即验证呈现分离
13. As a 框架维护者, I want Web 独立成 `duo-harness-web` 模块, so that HTTP 呈现的失败不连累编排域，依赖方向保持单向
14. As a 框架维护者, I want 界面为无构建的静态单页, so that 不引入前端构建链，模块保持纯 Java
15. As a 视觉关注者, I want 界面采用 DSH 风格的亮色主题（deepseek 品牌蓝、浅色分层、柔光阴影）, so that 观感与现代 agent 产品一致
16. As a 会话使用者, I want 页面上能开新会话, so that 话题分段不依赖终端

## Implementation Decisions

- **新模块 `duo-harness-web`**：依赖 core + tools + session + agent（依赖图顶层汇合，与 example 平级）；Boot yml 一行启用：`WebPlugin`，config `{port}`（省略默认 8080），**只绑定 127.0.0.1**，启动时打印访问地址；无鉴权（本地个人工具场景，鉴权 M9+）。
- **技术形态（零新依赖）**：JDK 内置 `com.sun.net.httpserver.HttpServer`（MockOpenAiServer 先例的放大）；界面为**无构建静态单页**（原生 JS，classpath 提供静态资源）；事件推送用 **SSE**（HttpServer chunked 写 `text/event-stream`，浏览器原生 EventSource 接收）——WebSocket 与前端框架均不引入。
- **两个地基 API（本里程碑交付，其他消费方同样受益）**：
  - core：`Context.snapshots()` 只读快照 API——返回 `List<PluginSnapshot>`（插件标识 + 六态状态），内核注册表在挂载/卸载/迁移时维护；状态面的数据源；
  - session：`Session.addListener`（Consumer<SessionEvent>，返回注销器）——事件流的推送源。
- **事件流**：Web 层订阅 Session 监听器，把新事件（user/message、assistant/chunk、tool/call、tool/result、approval/*、plan/mode…）原样转 SSE 推送；页面按事件类型渲染（消息流 / 工具卡片 / 交互卡片 / 状态刷新）。
- **HITL Web answerer**：实现 M6 `Answerer` 接口注册进交互 seam；`answer()` 用 `CompletableFuture<InteractionAnswer>` 阻塞等待 HTTP POST 回答（虚拟线程阻塞友好，ADR-0002 同款）；待答请求经 SSE 推送为交互卡片（审批两按钮 / 提问选项+自由输入）；**SSE 断连 → 立即以 fail-closed 完成全部悬空请求**（ADR-0008 语义延伸到 Web 呈现位）；POST `/api/answer` 携带选项序号或自由文本完成等待中的 Future。
- **对话面与 agent**：Web 服务装配自己的 `ToolCallingAgent`（经 PromptRegistry/技能/AGENTS.md 全套 M6/M7 装配），续接最新会话；**CLI 与 Web 同时操作同一会话的并发问题不在本期解决**（文档约定单入口使用）。
- **界面（亮色主题，DSH 风格）**：单页双区——左对话区（消息流：用户消息 / 助手流式文本 / 工具调用与结果卡片 / 审批与提问交互卡片；底部输入框），右状态区（插件六态表 + 工具清单，SSE 触发刷新）。样式取 DSH ui-theme 亮色规格：白/浅灰表面分层、deepseek 品牌蓝 `rgb(86,134,254)` 主操作色、`rgb(237,243,254)` 浅蓝选中底、柔光阴影分层（lv1~lv3）、描边分离、系统字体栈（-apple-system…PingFang SC）+ 等宽代码字体、大圆角。**暗色主题不做**（用户定案；token 结构预留）。
- **界面原型工单**：实现对话面前，先产出静态 HTML 原型页（假数据、完整样式），用户过目确认后作为对话面/交互卡片/状态区的实现蓝图。

## Testing Decisions

- **只测外部行为**：HTTP 起停与绑定地址、端点返回的 JSON/流形态、SSE 推送的事件序列、answerer 的完成与 fail-closed 语义；不测页面 JS 内部（浏览器端以人工验收为准）。
- **测试 seam（6 个，4 个复用）**：
  - 内核状态快照（**新 seam**）：挂载/卸载/状态迁移反映到快照；
  - Session 监听器（**新 seam**）：append 触发回调、注销器生效；
  - HttpServer 起停与端点（先例 MockOpenAiServer 的 HttpServer 用法）：静态页可达、/api/status JSON 形态、SSE 流事件序列；
  - POST /api/message（mock agent 先例）：消息进入会话、流式 chunk 经 SSE 呈现；
  - Web answerer（先例 InteractiveApprovalTest 的 seam 装配）：POST 回答解除阻塞、断连 fail-closed；
  - REPL 既有回归（AgentReplMainTest）：确认 Web 加入后 CLI 面不受影响。
- **原型验收**：原型 HTML 由用户过目确认（非自动化），确认后冻结为界面蓝图。

## Out of Scope

- 暗色主题（用户定案亮色优先；token 命名预留双主题扩展）
- 鉴权 / token / 多用户——loopback-only 已定（M9+ 按需）
- 会话删除 / 重命名 UI、会话 fork——M9+ 候选（列出 / 切换 / 新建已随会话侧栏进入 M8）
- 设置页面、主题切换、消息反馈、文件上传——DSH 菜单项，非最小面
- 页面插件（第三方插件贡献 UI 区块）——远期菜单
- CLI 与 Web 并发写同一会话的协调——文档约定单入口使用
- WebSocket、前端框架、构建链——技术定案排除

## Further Notes

- 两个地基 API（Context.snapshots、Session.addListener）是 core/session 的公开增量，纯新增向后兼容；后续 compaction、遥测等同样受益。
- ADR-0008 的最终验证标准：Web answerer 的实现不触碰审批策略、guard、六段管线的任何一行；若实现中需要改机制核，即本架构证伪。
- 原型工单的产物（HTML）直接演进为工单 02 的静态页——原型不是抛物线，是蓝图。
- 关卡欠账汇总：M3/M4/M6/M7 理解关卡均延后（M5 豁免），建议 M8 收官验收前集中补齐一轮。
