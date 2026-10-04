# M37（1.3.0）桌面端——Electron 壳承载的 Web 呈现位桌面化

Status: ready-for-agent

裁定与版图见 [ADR-0039](../../docs/adr/0039-M37桌面端立项决策.md)（十二问已批准）；术语（桌面壳 / 呈现位 / 启动器 / Duo home）以[术语表](../../docs/05-参考/术语表.md)为准；壳机制参考 [ZCode/呈现与UI/桌面端.md](../../docs/research/ZCode/呈现与UI/桌面端.md) 与 [DSH/呈现与UI/桌面端.md](../../docs/research/DSH/呈现与UI/桌面端.md)（2026-10-04 增量补扫版，「对 duo 的启示」两节为直接输入）。

## Problem Statement

- **启动链手工**：现在用 duo 要「开终端 → 敲 `java -jar`（或 mvn 命令）→ 从 stdout 抄带 token 的 URL → 手开浏览器粘贴」——每一步都是门槛，URL 换次启动换一次。
- **长任务无到达通道**：agent 跑长任务时切走干别的，turn 完成、审批/提问卡在等人、执行出错三种情况全靠人肉盯屏——审批卡长期无人答会被 fail-closed 宽限期拒掉，任务静默死。
- **无桌面身份**：没有图标、没有托盘、没有窗口——关掉浏览器标签页 duo 就「消失」了，再回来要重走启动链；外部应用也无法唤起它（无深链）。
- **误杀长任务**：终端里 Ctrl-C 或直接关终端 = 后端连同跑着的 agent 一起没了，没有「还有任务在跑」的拦截。

## Solution

**桌面壳**（Electron，术语表新词条）承载现有 Web 呈现位：壳拉起 Java 后端（fat-jar 子进程；壳选空闲端口经环境变量注入、读后端 stdout 机器锚点行拿带 token 的 URL）→ 开窗直连 `http://127.0.0.1:<port>` 加载现有 Web UI（后端零改造）→ 托盘常驻 / 系统通知 / 应用菜单 / `duo://open` 深链 / 退出前探活确认。桌面形态 = Web 呈现位的桌面载体：同一 WebPlugin、同一套会话与配置（共享 `~/.duo`）、审批路由与 fail-closed 语义不变。后端改动面收敛为两处行级：stdout 锚点行、端口环境变量覆盖。

## User Stories

1. As a duo 使用者, I want 双击桌面应用图标就得到一个完整可用的 duo（自动拉起后端、开窗、免抄 URL）, so that 不用记命令、参数和带 token 的地址.
2. As a duo 使用者, I want 关闭窗口后应用在托盘常驻、点托盘图标回到窗口, so that 会话不因关窗而断、桌面不增杂乱窗格.
3. As a duo 使用者, I want agent 卡在审批/提问等待时收到系统通知, so that 不盯屏幕也不会让任务干等超时.
4. As a duo 使用者, I want 一个 turn 跑完时收到系统通知, so that 长任务出结果不用轮询回来看.
5. As a duo 使用者, I want 执行出错/中断时收到带错误摘要的系统通知, so that 失败不静默.
6. As a duo 使用者, I want 点通知或托盘图标一步聚焦主窗, so that 到达通道不折腾.
7. As a duo 使用者, I want 退出应用时后端还有 agent 在跑会被拦下二次确认, so that 长任务不被误杀.
8. As a duo 使用者, I want 后端进程意外崩溃时看到恢复对话框（重启 / 退出，附诊断摘要）, so that 崩溃不是静默白屏.
9. As a duo 使用者, I want 从浏览器或其他应用发起 `duo://open` 唤起并聚焦主窗, so that duo 是一等桌面公民、可被外部流程拉起.
10. As a duo 使用者, I want 桌面开的会话与 CLI 互通（共享 `~/.duo`）, so that 换呈现位不丢会话与上下文.
11. As a duo 使用者, I want 桌面版与 CLI 直跑共用同一份装配与插件（`plugins.yml` 唯一事实源不动）, so that 配置和插件只装一遍.
12. As a CLI 直跑用户, I want 不装/不跑桌面壳时后端行为完全不变（缺省 8080、stdout 人读文案原样）, so that 桌面化对我零打扰.
13. As a duo 使用者, I want 应用菜单含 macOS 惯例基本项（关于/编辑/退出）与「打开数据目录」, so that 壳不违反平台习惯、排障有入口.
14. As a 排障者, I want 壳拉起失败或后端启动超时时看到明确错误对话框（含子进程 stderr 尾部摘要）, so that 问题可自行诊断或带信息求助.
15. As a duo 使用者, I want 本机没有可用 JDK 21 时得到明确指引而不是莫名启动失败, so that 前提缺失可自愈.
16. As a 验收者, I want 端到端走通「双击图标 → 窗口可用 → 发任务 → 切走 → 收审批通知 → 点回作答 → 收完成通知 → 退出被探活拦 → 确认后干净退出」, so that 原生体验全链路可演示.

## Implementation Decisions

**裁定来源**：以下全部锚定 ADR-0039（grill Q1–Q12 用户裁定），本 spec 只做工程化落钉；缝勾稿已经用户确认（四缝，见 Testing Decisions）。

- **壳工程**：仓库根新增 `desktop/` 目录（Node/TS 工程，非 Maven 模块）：Electron + TypeScript，测试链 vitest，打包链 electron-builder（macOS dmg/app；本机跑与轻打包优先——Q1 未选「双击即用」，安装器/签名/公证不进本期）。Node 依赖引入已在 ADR Q2 获用户同意（红线 4）。
- **JDK 21 前提**：壳 spawn 前探测 `java`（login shell 环境；mac GUI 启动不继承 shell PATH——DSH 教验，探测形态照抄其 login-shell 探测），缺失/版本不符弹指引对话框；内置 JRE（jlink 随壳分发）留「双击即用」后续期，本期不做。
- **进程编排**：壳 main 进程 spawn `java -jar <fat-jar>`（产物路径与定位方式工单期定）；**stdin 用 pipe 保持打开、从不写入**——cli 行的 REPL 主循环将阻塞在输入等待而非 EOF 退出（M27 机制认知：cli apply 即 REPL、其后行不装载；桌面装配沿用用户 `plugins.yml`，cli 行通常是末行）。实现首日必须实测「空 stdin 下 REPL 阻塞形态」并留记录——这是实现期头号风险。纯桌面用户可在 `plugins.yml` 删 cli 行（文档提示，不强制）。
- **端口注入**：壳找空闲端口（试绑 0 端口取实际值后释放）→ 环境变量 `DUO_WEB_PORT` 传给子进程。后端 `WebPlugin` 读端口优先级：系统属性 `duo.web.port`（测试注入口）> 环境变量 `DUO_WEB_PORT`（壳注入口）> web 行 `config.port`（缺省 8080）——与 `DuoHome` 解析优先级（sysprop > env > 缺省）同构。CLI/Web 直跑路径行为不变。
- **令牌交付锚点行**：`WebPlugin` 在现有带 token URL 的人读打印处旁新增一行机器锚点 `duo:web-ready url=<完整 URL 含 token>`（人读文案原样保留，1.x 兼容承诺内）。壳逐行扫 stdout 认锚点不认文案；启动超时（60s 量级，工单期定值）→ 失败对话框（含 stderr 尾部摘要）。
- **通知管线**：**前端 Web Notification API 路径**（ADR 留给 spec 期的落钉）——触发源事件（turn 完成/审批等待/错误中断）渲染层已全知，Electron 将 Web Notification 透传到 macOS 通知中心；渲染层以 `document.visibilityState` 门控（窗口聚焦中不扰）。壳侧仅承接通知点击→聚焦主窗。若 Electron 通知权限/行为有平台出入，壳侧订阅兜底为备选开关（工单期验证）。
- **窗口与托盘**：主窗关闭（红点）= 隐藏不退出（mac 惯例，dock/托盘保持）；托盘常驻：点击切换窗口显隐，菜单含 打开主窗 / 打开数据目录（`~/.duo`）/ 退出。应用菜单用 macOS 默认模板（About/Edit-Copy-Paste/Quit 等平台惯例项）+「打开数据目录」。
- **退出编排**：Cmd-Q / 菜单退出 / 托盘退出 = 真退流程：先探活既有 `/api/status` 判 agent 活跃 → 活跃则弹确认对话框（取消 = 回到常驻；确认 = 继续）→ SIGTERM 子进程（后端 shutdown hook 级联 dispose 已有，会话锁确定性释放）→ 壳退出；探活失败（后端已死）按可退处理。
- **崩溃看护**：子进程在非退出流程中 exit → 原生恢复对话框（重启后端并重连 / 退出应用），附 stderr 尾部诊断摘要（长度截断，防凭据泄漏进对话框——DSH 经验）。
- **深链**：壳启动注册 `duo://` 协议（macOS `open-url` + 二次启动 argv 转发两路），仅 `open` 一路由：唤起/聚焦主窗；路由面留白，后续加 path 不动架构。
- **白名单不动**：壳直连 `http://127.0.0.1:<port>`，后端 Host/Origin 回环白名单（`WebEntryGate`）不改；壳不引入自定义协议代理方案。
- **会话与装配**：共享 `~/.duo`（Duo home 语义不变）；不新增装配面、不改行序契约预检（web 先于 cli）。
- **文档义务**：README 增桌面段（前提/构建/运行）；交货指南续章（壳工程构建与打包说明）；已知限制页补「桌面壳 macOS 验证、Win/Linux 未验」「JDK 21 为运行前提」「桌面复用 plugins.yml、cli 行的 REPL 形态说明」。
- **记账口径**：M37 首笔提交同 diff 记 CHANGELOG（含此前预研产物：两家桌面端研究文档增量补扫、ADR-0039、术语表桌面壳词条——「独立研究增量随消费里程碑同 diff 记账」口径，duo-research experience 2026-10-04 条）。

## Testing Decisions

- **只测外部行为**：锚点行可解析、端口覆盖生效、壳编排正确拉起与解析、五场景真机可用——不测 WebPlugin 内部打印实现与壳模块私有协作。
- **四缝（勾稿已经用户确认）**：
  - **S1 后端 stdout 缝**（复用既有 JVM 子进程缝）：拉真进程断言 `duo:web-ready` 锚点行存在、URL 含 token 可解析、人读文案仍在（兼容锁）。锚点行是壳与后端的唯一契约面，必须带回归锁。
  - **S2 端口覆盖缝**（复用 DuoHome 解析优先级测试形态）：sysprop / env / yml 三态优先级各一锁，env 注入口以可注入方式测（`System.getenv` 不可设，生产读 env、测试走 sysprop 路径或注入点——工单期落钉，DuoHome 先例同构）。
  - **S3 壳编排缝**（新开，壳工程 vitest）：mock 子进程，锁 spawn 参数（jar 路径/env/stdin 策略）、锚点解析、启动超时→失败对话框路径、退出编排（探活→确认→SIGTERM）、崩溃→恢复对话框。不锁 Electron API 细节。
  - **S4 真机视觉缝**（macOS 手动验收，duo-acceptance 流程，红线 5）：托盘显隐、三类通知触发与点击聚焦、`duo://open` 唤起、退出探活拦截、崩溃恢复对话框五场景 + User Story 16 端到端叙事；M33 浏览器实测形态，不进 CI。
- **回归锁纪律**：后端两处改动各带回归锁入库；壳编排逻辑 vitest 覆盖；真机验收截图/输出留档（验收件作者先自跑全流程并留证据——M35-09 判据）。

## Out of Scope

- **更新链（electron-updater / 检查更新）、CLI 命令安装（DSH 所有权模型）、内置浏览器受控宿主（ZCode IAB）、强制更新窗**——ADR-0039 显式挂账三项 + 拒绝项，桌面端后续期候选，backlog 记账。
- **Windows / Linux 平台验证**——macOS 优先裁定；壳代码跨平台写法，届时补验证不重写。
- **内置 JRE / 安装器 / 签名公证 / 自动更新 feed**——「双击即用」未选，分发从简；JDK 21 为本机前提。
- **壳内自定义协议代理加载**——Host/Origin 白名单不动，壳直连回环 HTTP。
- **多窗口 / 多会话并行窗口管理**——单主窗裁定内；Web 多标签已覆盖多会话。
- **桌面专用装配文件**——`plugins.yml` 唯一事实源不动（Q12 拒绝项）。
- **后端缺省端口改动态**——CLI/Web 直跑行为不变（Q7 拒绝项）。

## Further Notes

- **改动面勘误（2026-10-04，工单 02 壳联调）**：后端改动面实为三处非两处——壳联调逮出 `CliPlugin.stop()` 的 `in.close()` 与阻塞 readLine 读者同锁死锁（SIGTERM 40s+ 挂死，终端 Ctrl-C 同病，BUG-20261004-01），修复为不关 reader + 回归锁；ADR-0039 Consequences 已加勘误注。**实现期头号风险两条均按预判落地**：stdin pipe 保活实测成立（REPL 阻塞等待非 EOF 退出）；通知管线待工单 05。
- **改动面勘误二（2026-10-04，工单 04 探活判据核实）**：本节「探活用既有 `/api/status` 判 agent 活跃」前提不成立——status 载荷无 turn 活跃字段。经用户裁定（grill 逐题，turnActive 方案）加第四处小改：`ToolCallingAgent` 全局在飞 send 计数 + status 新增 `turnActive` 布尔（壳侧缺字段按空闲 fail-open）；ADR-0039 勘误注二在案。
- **实现期头号风险（首日必验）**：spawn 子进程空 stdin 下 cli 行 REPL 的行为（阻塞等待 vs EOF 退出）——决定 stdin 管道策略的成败，实测留记录；若 EOF 退出则壳必须证明 stdin 恒打开路径。
- **通知管线定稿（2026-10-04，工单 05 实证）**：主路径保住「前端 Web Notification API」；门控可见态源**偏离本节措辞**——Electron（macOS）实测 `win.hide()` 后 `document.visibilityState` 仍为 visible（渲染层信号失真），门控可见态改取主进程 `win.isVisible() && !isMinimized()`（preload 经 sendSync），语义不变（可见中不扰）；票面预设「壳侧订阅兜底开关」因此**未启用**（实测出入是可见态信号而非通知透传，第三路径为主路径修源）。通知事件点五个：审批等待 + 提问等待（Q10「审批/提问等待」一类）、turn 完成、执行出错、任务已中断（Q10「错误中断」一类）。计划复核通知不受卡片去重约束（去重早退曾致主路径不可达，审查实锤后改形）。真人项：三类通知触发 + 点击聚焦（与 03 托盘手势并作 mac 验收件）。
- **通知管线备选开关**：前端 Web Notification API 为主路径，Electron 平台行为有出入时壳侧订阅兜底——工单期验证后定稿，不回 ADR。
- **版本分支**：`1.3.0` 于工单开拆、实施启动时自 main 切出（红线 7；ADR-0039 Consequences 在案）。
- **验收口径预告**：done = 用户手动运行验收件确认（duo-acceptance 流程）；端到端叙事 = User Story 16 全链路 + S1/S2 回归锁绿 + 托盘/通知/深链/探活/崩溃五场景证据。
- **参考实现锚点**：DSH 桌面端文档「对 duo 的启示」七条（动态端口/退出探活/恢复对话框/登录 shell 探测/更新分级等）与 ZCode「打包即契约」在案；壳实现逐条对照取用，不重扫源项目。
