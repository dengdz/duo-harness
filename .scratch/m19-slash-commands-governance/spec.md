# M19 斜杠命令与运行中治理：命令注册表、呈现位亲和路由、父级 steer

Status: ready-for-agent

语义权威：ADR-0020（斜杠命令与运行中治理，12 条决策 + 17 条拒绝项）。本文是其面向实现的落地规格；术语以 [术语表](../../docs/05-参考/术语表.md) 为准（斜杠命令、busy-safe、steer、亲和路由、压缩点五词条已入册，会话标题词条已随 /title 修正）。

## Problem Statement

三类用户各被一道墙挡着：

1. **Web 用户**：输入框里敲 `/permission` 被原样透传给模型——进历史、烧 token、模型自己排查"你为什么发这个"（M12-03 验收事故）；`/compact` 等 harness 操作在 Web 无处可敲；agent 执行长任务时发消息直接 409——想中途纠偏只能干等或开新话题。
2. **双开部署用户**：CLI 发起的工具审批按注册序路由给 Web 卡片，**终端零提示**，用户以为程序卡死（M12-02 验收事故）；交互工具的会话绑定也按注册序归属，计划状态可能串位。
3. **CLI 用户与运维者**：`/permission` 切档重启即丢（每次重切）；标题不可改名、页长硬编码；`/compact` 无手动入口（上下文逼近窗口只能被动等自动压缩）；权限档"为什么变了"在日志里查无实据；命令本身只是 CLI 私有 if-else，没有域级抽象——每加一个命令都在呈现位深处堆一层。

## Solution

- **命令注册表**：agent 域发布 "commands" 服务，插件代码注册命令（DSH 同款）；CLI 与 Web 的输入入口统一按"命令注册表 → 技能直调 → 未知命令报错"的顺序解释。命令同步执行于呈现位进程内、**不进模型历史**，落 `command/run` / `command/done` 审计事件；agent 执行期间 busy-safe 命令（如 /permission 切档）立即生效，其余明确回应"执行中需等待"。
- **/compact 手动压缩**：压缩点落 `context/compacted` 事件，投影按最后压缩点拼接——手动与自动触发共用同一事件，刷新/回放天然恢复。
- **呈现位亲和路由**：工具执行携带发起呈现位标记，审批/提问优先路由给发起方的回答者（"谁发起谁作答"），缺席才轮注册序。
- **父级 steer**：Web 移除 409——执行中的用户消息进注入收件箱，agent 在迭代边界排干为普通 `user/message`，下一步即可见（与子代理 send_message 语义对称）。
- **还账四件**：权限档切换落会话事件持久、`/title` 改名命令、页长 yml 可配。

## User Stories

1. 作为 Web 用户，我想在浏览器输入 `/permission read-only` 直接切权限档，所以治理操作不必回终端。
2. 作为 Web 用户，我误输入不存在的 `/xxx` 时想看到"未知命令 + 可用命令清单"，所以知道有哪些操作可用，而不是把斜杠文本发给模型。
3. 作为双开用户，我在 CLI 发起的工具审批想在终端直接作答，所以不再切浏览器找卡片。
4. 作为双开用户，我在 Web 发起的审批仍由 Web 卡片优先呈现（发起方优先），所以既有习惯不被破坏。
5. 作为 CLI 用户，agent 执行长任务时我输入 `/permission read-only` 想立即生效勒住任务，所以跑偏时能马上纠。
6. 作为 CLI 用户，agent 执行中我输入 `/new` 想得到明确"执行中，需等待空闲"的提示，所以不会误以为会话已经换掉。
7. 作为 Web 用户，agent 执行长任务时我发的消息想被注入为下一步指示（带"已注入，待当前步骤完成"的轻提示），所以中途纠偏不用干等任务跑完。
8. 作为 Web 用户，注入的消息想按发送时机落在时间线上（普通 user/message），所以对话顺序与模型记忆一致。
9. 作为运维者，我想在会话日志看到每次命令的 `command/run`（名 + 参数）与 `command/done`（结果），所以权限档变更等治理操作可事后审计。
10. 作为运维者，进程在命令执行中途崩溃时我想留下 `command/run` 断口，所以能定位中断发生在哪条命令。
11. 作为模型，我不想在上下文里看到任何斜杠命令文本，所以上下文预算不被 harness 操作占用。
12. 作为 Web 用户，刷新页面后我想仍能看到历史命令行（名 + 结果），所以命令历史随会话回放完整。
13. 作为 CLI 用户，上下文逼近窗口时我想 `/compact` 手动压缩，所以主动腾空间而不是被动等阈值。
14. 作为 Web 用户，我想要同款 `/compact` 入口，所以两端能力对等。
15. 作为用户，预算触发的自动压缩发生时我想在日志看到压缩点事件，所以"上下文为什么变小"可解释。
16. 作为用户，压缩后刷新或重开会话，我想对话内容按压缩点恢复（总结替换老历史）且不重复总结，所以不重复烧 token。
17. 作为 CLI 用户，我在某会话 `/permission` 切档后重开该会话想档位仍生效，所以不用每次重启重切。
18. 作为用户，我新建会话时权限档想回到 yml 缺省，所以一个会话里的切档不惊扰其他会话。
19. 作为 CLI 用户，我想 `/title 新标题` 改会话名，所以侧栏里的重要会话可辨识。
20. 作为 Web 部署者，我想首屏/每页消息数经插件 config 配置，所以长会话的翻页节奏可按需调节。
21. 作为插件开发者，我想在插件装配时注册命令且随插件停止自动摘除，所以命令生命周期与插件一致、无需手工清理。
22. 作为插件开发者，我想给命令声明适用呈现位（ANY/CLI/WEB），所以不适用的呈现面得到"该命令仅在 X 可用"的明确提示。
23. 作为 harness，工具执行的审批/提问请求我想优先路由给发起呈现位的回答者，所以"谁发起谁作答"。
24. 作为交互工具（ask_user / 计划呈交），我想会话绑定按发起方亲和而非注册序，所以双开部署下计划状态不串位。
25. 作为 CLI 用户，`/exit` `/new` `/plan` 等既有命令迁移到注册表后行为不变，所以升级零惊讶。
26. 作为 Web 用户，执行中发送的消息我想看到"已注入，待当前步骤完成"的轻提示，所以知道消息已被接收而不是被吞。

## Implementation Decisions

**命令注册表（agent 域）**

- agent 域新增 "commands" 服务（保留裸名，与 prompts/skills 同居）：命令 = name + description + 适用呈现位（ANY/CLI/WEB，缺省 ANY）+ busySafe（缺省 false）+ handler；注册 API 沿 `register(registrant, definition)` 惯例，注册即注册方作用域 effect。
- handler 收统一命令上下文：参数文本、当前会话供给、回显通道、发起呈现位标记、请求结束回调；同步执行于呈现位进程内、返回文本结果；不占 agent 单飞窗口；命令异常收敛为回显文本（不影响 agent 单飞与呈现位存活）。
- 入口顺序（两呈现位同款）：命令注册表 → 技能直调（指令前缀注入、进模型历史）→ 未知命令报错附可用清单。技能与命令两张表保持平行。
- busySafe 分级：agent 单飞占用时 busySafe 命令立即执行回显；非 busySafe 回应"执行中，需等待空闲"（Web 409 的命令专用版）。/permission（查看/切档）、/title 声明 busySafe=true；/new、/compact、/exit 缺省 false。
- CLI 的 /new、/permission、/plan、/exit 迁移为注册调用（行为不变，用户故事 25）；Web 新增斜杠入口（命中命令执行并回显，未命中 `/` 前缀报未知命令）。**注记（2026-09-19）**：/permission 落地时改标双面（ANY）——handler 只依赖全局 workspace 服务无呈现位归属，M19 用户故事 1（浏览器直接切档）由此成立；其余三命令维持 CLI 面。

**会话事件与投影（session 域）**

- 新增三类事件：`command/run`（命令名 + 参数）、`command/done`（结果）、`permission/mode`（档位，latest-wins）、`context/compacted`（总结文本 + 压缩范围）——其中 command 两条为一组先 run 后 done。
- 投影语义两处扩展：command 事件被 `deriveMessages` 忽略（模型不可见由投影纯函数保证）；投影以最后压缩点为准——之前以总结替换、之后照常（latest-wins 家族）。`permission/mode` 同 todo/plan 先例做 latest-wins 投影供运行时读取。
- 命令与压缩事件不参与 tool 配对算法（repairToolMessageAdjacency / sealDanglingToolCalls 只认 tool/call|result，零牵动）；不占消息窗口计数（M13 尾窗/分页按消息算）。

**/compact（治理域 + 命令）**

- /compact 注册为 busySafe=false 命令（动上下文必须 idle）；执行 = 触发治理压缩（LLM 总结老历史）→ 落 `context/compacted` → 回显压缩结果摘要。
- **自动压缩同事件化**：预算触发的压缩同样落 `context/compacted`——一处语义两处触发；现状"自动压缩不落盘"的行为被取代。

**呈现位亲和路由（tools 域 + agent 域）**

- ToolExecution 新增 presenterId 只读字段：呈现位构造 ChatAgent 时注入自己的呈现位标记，agent 执行工具时携带进管线。
- ask 请求（审批/提问/计划呈交）携发起方标记；InteractionRegistry 路由规则改为：发起方回答者优先作答，发起方缺席/放弃才轮注册序（注册序兜底保留，单呈现位部署零感）。
- 交互工具（ask_user / exit_plan_mode）的会话供给按发起方亲和（limitations 交互工具绑定条销账）。
- hooks 载荷顺带携带 presenterId（M18 backlog"钩子载荷上下文透传"部分消化——session_id/transcript_path 仍缺席）。

**父级 steer（agent 域 + web 域）**

- ToolCallingAgent 新增注入收件箱（并发队列）：`injectUserMessage(text)` 由 WebFace 在 busy 时调用（替代 409）；send 循环在迭代边界（一轮工具执行完、下一轮请求构造前）排干收件箱——逐条落普通 `user/message` 事件再构造请求。
- 不发明 steer 专属事件；多条注入照排（日志本就允许连续 user/message，需显式断言兼容）。
- WebFace：busy 时 POST /api/message 不再 409，改为注入 + 202（响应体带"已注入"标记）；前端 toast"已注入，待当前步骤完成"。并发测试的 409 断言同 diff 重写为注入断言。
- Web UI 轻提示与命令行渲染均走既有事件流（SSE），无新端点。

**还账四件**

- 权限档持久化：`/permission` 切档时落 `permission/mode` 事件；会话打开投影恢复最后档位（写回 WorkspacePolicy）；新会话/新部署回 yml 缺省。**注记（2026-09-19）**：恢复时机分档（BUG-20260919-03）——启动续接只恢复不重置；占用被迫改开的新会话继承被占会话最后切定档并落继承事件；显式换绑（/new、页面新话题/切换）无记录才重置缺省。
- /title 命令（busySafe=true）：再 append `session/title` 即改名（latest-wins 投影现成）；标题自动演进不做。
- 页长可配：web 插件 config 增首屏/每页消息数（缺省 50 不变），WebFace 分页逻辑参数化。
- /model 不做（backlog："`/model` 运行时切换"条目记档）。

**文档义务（同 diff）**

- CHANGELOG 0.14.0 记账；插件配置参考补 commands 相关段（如适用）与页长 config；backlog 对账（销"呈现位路由""Web 斜杠缺口""/compact"三条，增"CLI 运行中 steer 入口""/model 运行时切换"两条，钩子载荷条目更新 presenterId 消化情况）；limitations 销 M9-M11#3 / M12#3 / M13#1 / M13#2 与交互工具绑定五条。

## Testing Decisions

**接缝（已与用户确认，四缝全是既有）**

1. **agent 单元缝（ToolCallingAgent + 注册表）**：mock LLM 慢响应驱动运行中场景——注入收件箱在迭代边界排干（落 user/message、下一轮请求可见、不打断飞行中工具）；命令注册表的注册/摘除（作用域 effect）/适用面/未知命令报错/busySafe 分级。先例：ToolCallingAgent mock-LLM 测试、SkillRegistryTest。
2. **session 投影纯函数缝**：deriveMessages 对 command 事件排除、`context/compacted` 压缩点拼接、`permission/mode` latest-wins 的单测；同批断言 M10 游标 / M13 尾窗 / M16 闭合既有算法零牵动。先例：投影单测、todo latest-wins 测试。
3. **呈现位双面缝**：CliPluginTest 脚本 REPL（命令执行回显、busySafe 等待提示、/title 改名、/permission 持久化、/compact 回显）+ WebFaceTest HTTP（斜杠命令执行、busy 注入替代 409、页长 config、permission 档恢复）。先例：两套呈现位测试齐备。
4. **tools 亲和路由缝**：InteractionRegistry 发起方优先/缺席轮注册序单测 + ToolExecution.presenterId 传导断言。先例：InteractionRegistryTest、组合矩阵风格。

**好测试标准**：只断言外部行为（事件落盘形态、投影结果、回显文本、路由选择、请求构造内容），不测内部簿记；steer 场景必须用真实异步时序（慢 mock LLM + 并发注入），同步 mock 会让边界排干逻辑永不生效。

**真机验收件**（缝外，duo-acceptance 惯例）：双开 yml 下 CLI 发起审批终端作答（亲和路由）；Web 输入 `/permission` 切档与运行中消息注入提示；`/compact` 后刷新页面压缩态保持；`/title` 改名后侧栏更新。

## Out of Scope

- CLI 运行中 steer 入口（机制留 agent 域，backlog 记档）。
- /model 运行时切换（backlog 记档；注册表落地后加回是增量）。
- 标题随对话自动演进（一次生成 + /title 改名已覆盖）。
- markdown 文件定义命令；命令的 http/prompt 等非本地执行形态；异步命令与命令级超时配置。
- 权限档跨会话全局持久（DuoHome 全局文件——会话间惊吓，已拒绝）。
- 广播式回答者路由、抢占式打断运行中步骤。
- steer 专属事件类型与 Web 双向确认流（注入即单向轻提示）。

## Further Notes

- fail-open 不是本期的主题，但 busySafe 缺省 false 沿同一 fail-closed 哲学：说不清就别在运行中动。
- **注记（2026-09-19）**：① WebFace busy 注入分支对不支持 `injectUserMessage` 的 agent（测试桩/旧实现）保留 409 兜底——防御性细化，注入协议不静默漂移；② CLI 面单线程 REPL 下 busy 探针在 dispatch 时刻恒 false，busySafe 分级的实际受益面是 Web（CLI 运行中输入经行缓冲在本轮结束后生效，与决策 9 自洽）——故事 5 的"立即勒住"在 CLI 实为"本轮结束后立即生效"。
- ADR-0020 明示：DSH 的 `ctx.commands` API 签名研究笔记未展开——实现以本文与 ADR 为准，不臆测 DSH 形状；`command/run|done`、`permission/mode`、`context/compacted` 的字段形状在实现时定稿并回写配置参考/术语表（如适用）。
- steer 使会话日志出现"执行中追加的 user/message"（可能连续多条）——投影与回放需显式断言兼容（日志本就允许连续 user/message，预期零特判）。
- 钩子载荷透传部分消化：presenterId 可随 ToolExecution 进 hooks stdin 载荷（session_id/transcript_path 仍缺席，backlog 条目保留）。
- 版本 0.14.0，版本分支 `0.14.0` 已切。
