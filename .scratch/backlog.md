# 待办清单（backlog）

> 还不值得立里程碑、但确定要做的增强项。每条注明来源与建议时机；启动新里程碑时先来这里挑。

## 上下文治理（M9 衍生）

- [x] **Web 状态面显示 token 占用**：状态面加"上下文占用"一行，治理可见化。→ 进 M10（2026-09-14），升格为真实 usage 展示，见 `.scratch/m10-web-hardening/`
- [x] **治理阈值 yml 化**：spill/修剪/压缩/窗口四阈值目前是 `ContextGovernance` 常量，改为 WebPlugin/装配 config 可配。来源：M9 spec Out of Scope 有意延后。→ 0.8.0 已交付（M13，ADR-0013：web/cli 插件 config governance 段六字段，2026-09-17 对账销账）
- [x] **provider usage 捕获**：`stream_options: include_usage` 拿真实 token 数替代本地估算。→ 进 M10（2026-09-14），治理判定切真实值+估算兜底（ADR-0009），见 `.scratch/m10-web-hardening/`
- [x] **/compact 手动压缩命令**：CLI 与 Web 各加一个手动触发入口。来源：DSH 参照（研究材料 45 行）。→ M19-03 已落地：/compact 双面命令 + 压缩点事件化（ADR-0020 决策 6，2026-09-19）

## Web 呈现域（M10 grill 衍生）

- [ ] **鉴权与局域网暴露**：loopback-only 维持。用户 2026-09-14 裁定"局域网多设备目前不需要"——要给别人用时重启（yml 静态访问令牌 + bind 配置 + "非回环绑定必须配令牌"的 fail-closed 联动，grill 已议定未落盘实施）。→ M16（ADR-0016）先落 Host/Origin 栅栏（封 DNS rebinding，不涉令牌），令牌 + bind 仍挂账于此
- [x] **会话内容恢复范式：尾部窗口快照 + 向上分页 + 切换无刷新**（DSH 范式，2026-09-14 研究确定）：刷新/重连/切会话统一取**尾部 N 条消息**快照（携 `hasMore` + 投影基线）整窗替换，更早历史按页向上加载（DSH `PAGE_MESSAGES=50`、跳跃 200 条参照）；同时消除会话切换的 `location.reload()`。**DSH 不持久化游标、无全量重放路径**——用"限制快照大小"替代"增量游标"，一套机制覆盖三场景。需服务端分页参数 + 前端向上加载 UI；新开 ADR（ADR-0010 只覆盖连接层游标，不可改）。来源：M10 工单 05 刷新策略裁定 + DSH 源码精查。→ 0.8.0 已交付（M13，ADR-0013，2026-09-17 对账销账）
- [ ] **代码语法高亮**：MD 渲染随 M10 落地（vendor marked.js + DOMPurify），highlight.js（约 100KB+）待界面稳定后评估。→ 建议时机 M23 视觉打磨轮
- [ ] **计费口径统计**：cache 命中率、分桶明细——usage 已随 M10 落 assistant/message 日志，按需投影展示。→ 建议时机 M21（与会话查询/导出同期能力域）
- [ ] **工具名协议化渲染**：前端散布 ask_user/exit_plan_mode 硬编码、后端"拒绝"魔法串判定审批语义，改元数据驱动。→ 后端魔法串部分已入册 M16（/api/answer 结构化协议）；前端元数据渲染建议时机 M23
- [ ] **状态面轮询统一**：5s setInterval 与 SSE 双通道并存，统一事件通道或论证保留轮询。→ 1.0 后菜单
- [ ] **父呈现位动态时间上下文**（M16 工单 04 降级入账）：prompt 注册表仅静态片段（注册时刻冻结，长会话内变陈旧），动态时间需注册表扩展（按请求组装时刻注入）——子代理侧已由环境段覆盖（spawn 时新鲜生成），父呈现位待注册表支持。来源：M16 工单 04
- [x] **迭代上限终止的 Web 用户可见化**（BUG-20260917-03 验收 B 发现）：达上限返回 completed=false 的 AgentReply，CLI 打印 [异常终止] 而 Web 面 /api/message 不看返回值也不渲染——页面无提示地停住。可用 run/error 帧机制做提示（方向：completed=false 时直推一帧错误卡）。来源：用户验收 B 实测（会话 213120-b3c8）。→ 进 M17 搭车（ADR-0018 决策 7，2026-09-18 对账销账）

## 文档与呈现（M8.5 衍生）

- [ ] **视觉打磨**：原型评审时用户原话"可用非惊艳"——卡片视觉细节、动画过渡在界面稳定后统一打磨。来源：M8 工单 03 记录。→ 已入册 M23 1.0 收口打磨轮（ADR-0016）
- [ ] **03-高级章节**：技能编写指南、MCP 深入、多插件协同（组装第二个 agent）。来源：M8.5 spec Out of Scope。→ 已入册 M23（ADR-0016）

## 容器与测试基建（M12 衍生）

- [x] **插件可选依赖**：`inject()` 目前全是硬依赖（缺失即 PENDING 挂起/启动失败），CLI 想"不带 fs 工具"的纯对话装配无法表达——需要声明可选服务名与降级语义（如 `/permission` 在无 workspace 服务时提示未挂载）。来源：M12-02 审查修复（/permission 接线引入 CLI→fs 硬依赖）。→ M18-01 已落地：`optionalInject()` + CLI 纯对话装配（ADR-0019 决策 7-9，2026-09-18）
- [ ] **测试进程收割**：surefire fork 被强杀（或异常退出）时 MiniFileSystemServer 等 stdio 子进程不回收，机上是曾累计 150+ 僵尸进程； graceful dispose 路径正常。可考虑 McpClientPlugin 挂 shutdown hook 兜底杀子进程。来源：M12-02 回归排查（2026-09-15） 补充案例（M12-05）：cli 行的 REPL 线程非守护且阻塞在 System.in——单跑含 cli 行装配的测试类时测试本体已完成但 **JVM 退出挂起**（surefire forkedProcessTimeoutInSeconds=240 未兜住此形态，其只约束测试执行段）；修法=Boot 前 System.setIn 空流（ToolCatalogTest 已修），机制级收敛随 awaitStartup 超时语义一并评估。→ M16 已书面化兜底结论（CliPlugin.stop 的 in.close() 即 REPL 阻塞解除）；M18-02 评估结论（2026-09-18）：awaitStartup 超时与等待点名日志已消除"编程挂载静默卡死"这一测试挂起成因，与本条两形态（fork 强杀遗留 stdio 子进程、REPL 线程阻塞 IO）均正交；stdio 子进程兜底方案已明确（ConnectionSupervisor 持活动连接注册幂等 shutdown hook，JVM 退出时 destroyForcibly，约 40 行），但 fork 强杀路径无法在 surefire 内稳定复现、测试成本与收益不成比例——方案记档，继续挂账
- [x] **awaitStartup 超时语义**：编程挂载 `Context.plugin(...).awaitStartup()` 缺依赖时无限等待且无日志，测试/演示易静默卡死（yml 路径有 Boot 校验点名报错）——评估加可配超时 + 点名缺失服务。来源：M12-02 回归排查（2026-09-15）。→ M18-02 已落地：`awaitStartup(Duration)` 超时点名缺失服务、无参版等待前点名日志（ADR-0019 决策 10，2026-09-18）

## 交互路由（M12-02 验收衍生）

- [x] **审批/提问的呈现位路由（谁发起谁作答）**：双呈现位并存时按注册序固定路由（web 优先），CLI 发起的工具调用审批会跳到 Web 卡片、**终端零提示**——M12-02 验收第 6 步用户实际被绊住（误以为卡死）。来源：M12-02 验收（2026-09-15）。→ M19-05 已落地：ToolExecution 携 presenterId，ask 请求发起方回答者优先、缺席/放弃才轮注册序（ADR-0020 决策 7，2026-09-19）
- [x] **Web 面斜杠命令缺口**：`/permission` 等 REPL 内置命令只在 CLI 呈现位拦截（ADR-0012 归 CLI），Web 聊天输入框不识别、透传给模型当普通消息——M12-03 验收第 8 步用户在浏览器输入即踩坑（模型自己排查出了根因）。来源：M12-03 验收（2026-09-15）。→ M19-02 已落地：Web 输入框斜杠前置命令解释，与 CLI 共享命令注册表入口（ADR-0020 决策 3/5，2026-09-19）

## 工具并发域（M17 grill 衍生，2026-09-18，ADR-0018）

- [ ] **MCP 工具并发白名单**：MCP 远端工具现为恒独占（ADR-0018 决策 2）；yml 按 `服务器名/工具名` 点名放开并发（部署者裁量，与 ADR-0017 的 opt-in 精神同构）。来源：M17 grill Q2 裁定留后续。
- [ ] **skill 工具并发放开**：技术上纯只读，M17 保守起步未标安全（fail-closed）；后续放开的第一候选。来源：M17 grill Q2 标注盘点。
- [x] **双呈现位叠挂管线超时监听器**：cli+web 双开共享 ToolsService 时 `mountPipelineTimeout` 各挂一次（嵌套超时、短者先生效，行为不破坏但配置语义含混且多一层线程跳换）——补查重先到先得（与 registerTodoWriteTool 同模式）。来源：M17 双轴审查 P2（2026-09-18）。→ M18-05 已修复：`PipelineTimeout.mount` 按 ToolsService 实例查重先到先得、摘除后可重挂（2026-09-18）

## hooks 域（M18 衍生，2026-09-18，ADR-0019）

- [ ] **项目级 hooks 配置**：仓库内 `.duo/hooks.json` 与用户级合并（团队共享钩子场景）——需先立"项目根"概念（git 定根或 cwd 锚定），M18 只做了用户级。来源：M18 grill Q1（2026-09-18）。
- [ ] **钩子事件扩面（UserPromptSubmit / Stop 等）**：非工具事件需 agent 循环新增挂点，Stop 的 exit 2（禁止停止）语义重；M18 一期只做工具两事件。来源：M18 grill Q3（2026-09-18）。
- [ ] **钩子 updatedInput 入参改写**：allow + 改写工具入参（格式化、脱敏类用法）——需 ToolExecution.args 可变与"改写后参数进日志"的语义钉子（M17 日志同构承诺不可轻动）。来源：M18 grill Q3（2026-09-18）。
- [ ] **钩子载荷上下文透传（session_id / transcript_path / tool_use_id）**：Claude Code 的 stdin 载荷含此三字段（会话关联与日志检视用），duo 管线载荷（ToolExecution）无会话与调用标识——透传需执行入口携带上下文。**presenter_id 已随 M19-05 消化**（ToolExecution.presenterId 进载荷，ADR-0020 决策 7）；session_id / transcript_path / tool_use_id 仍缺席（ToolExecution 无会话引用，补齐需载荷对象再扩展）。载荷实发：hook_event_name / tool_name / tool_input / cwd（PostToolUse 增 tool_response）+ presenter_id + DUO_HOME。来源：M18-03/04 实现裁定（2026-09-18），M19-05 更新（2026-09-19）。

## 运行中治理（M19 衍生，2026-09-19，ADR-0020）

- [ ] **Web 斜杠命令执行异步化**：/compact 等命令同步执行于 HTTP 线程，LLM 摘要无超时兜底可长挂（CLI 同步可接受）；方向 = 202 受理 + 结果经既有 command/done 事件流呈现。来源：M19 双轴审查 P2（2026-09-19）。
- [ ] **CLI 运行中 steer 入口**：注入收件箱做在 agent 域，Web 已接（工单 04）；CLI 未接——终端行缓冲天然排队（执行中输入下一轮 readLine 即得，体验已够），接入需主循环线程拆分、改动面大。来源：ADR-0020 决策 9 裁定记档（2026-09-19）。
- [x] （已排期）**/model 运行时切换** → M24 搭车（ADR-0024 修订，随 /effort 同批）：同 provider 换模型名 + 会话事件 + resume 保存意图/执行绑定分离；跨 provider 需 yml 预声明。销账于 M24 收口

## 1.0 后菜单（DSH 全景复审补充，2026-09-17，ADR-0016 拒绝项对应池）

> DSH 有对应物、但为防 parity 蔓延不入 1.0 前版图的项；按需从这里挑。

- goal 跨轮目标域（每 session 持久目标 + CAS 修订 + 人类权限门 + 自动续跑；与 todo 轮内清单正交，M17 grill Q6 裁定不做）
- PTC run_code（程序化工具调用传输，模型写代码并发调工具）
- workflow / ralph（脚本化多子 agent 编排）、agent-team（roster/任务 DAG，DSH experimental）
- LSP（seam + stdio + 诊断工具）、PTY 持久终端六件套、持久 shell
- 跨 harness 委派后端（把真实 Claude Code/Codex 当子 agent 运行；duo 后端接口位已留）
- Anthropic-messages 协议适配（现仅 OpenAI 兼容）、prompt 更新 in-history 前缀缓存策略（reasoningEffort 分级已排期 M24——ADR-0024 修订）
- settings 热重载 + schema 驱动设置页（即 M1 遗留限制 1 的完整形态）、credentials OAuth 授权流
- @session 跨会话引用、schedule 会话内定时提醒、JSONL 压缩帧/代际迁移链、agents[] 声明式自启
- MCP resources/prompts 桥接（DSH 也仅桥接 tools——做了即超越对照系）

## M22 探测里程碑对账（2026-09-21，ADR-0023）

（已排期）**glob/grep .gitignore 语义**（M12#2）→ M23 正式范围（ADR-0024），本条销账于 M23 收口
（已排期）**Web 鉴权令牌 + bind** → M24 正式范围（ADR-0024，含标签级会话绑定最小版），本条销账于 M24 收口
- [ ] **参考项目工程化探测**（原探测批 B11）：DSH/ZCode 测试哲学/CI 门禁/打包分发（SEA/electron-builder）/更新通道的机制级探查。来源：ADR-0023 裁定砍出探测范围。→ 1.0 后菜单；DSH 测试哲学已见 docs/research/DSH/总览.md §3
