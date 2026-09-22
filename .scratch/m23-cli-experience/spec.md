# M23 执行与 CLI 体验——spec

> 决策依据：[ADR-0025](../../../docs/adr/0025-M23执行与CLI体验三裁定.md)（三裁定）+ ADR-0024 M23 节（范围）+ 本期 grill 十二问逐题裁定（2026-09-21）。事实基础：docs/research/ 两家探测文档——工单 03（本机执行工具族，ZCode/DSH 各一）、05（任务管理，DSH）、01（Agent循环与状态机，两家）、07（审批与提问交互，两家）、17（终端呈现，DSH/ZCode）、扩展机制/skills与命令（DSH，热加载参照）。

## Problem Statement

CLI 用户在 agent 执行期间键入的输入沉在行缓冲里，turn 结束后被静默当新输入消费——纠偏时机错过、新任务误触发，这是本期要解决的唯一真实缺陷。围绕它的一串日常痛点：bash 长命令只能干等 120s 被杀树；大输出被内存截断丢弃、无法回读；审批一个一个弹、无法感知还有几个待答；完全自动化消费（脚本/CI）只能抓屏读文本；glob/grep 不认 .gitignore，噪声大且 node_modules 全量扫；技能改一条要重启进程；模型在 10 轮上限前没有收敛感知，烧满才知道被掐。

## Solution

CLI 主循环改为事件驱动（虚拟线程读 stdin → 收件箱）：执行期键入即时插队（next-step，模型下一步就看到）；Ctrl+C 协作式中断——在跑先停（已流出内容保留并标记、未派发工具补合成结果、会话停在可恢复态），再按才退出。bash 支持 `run_in_background` 转后台：模型经 task-output/task-stop 操作，完成通知必达，大输出 spill 落盘可回读；CLI 提示行与 Web 状态面可见。审批升级为小队列：逐个呈现带 i/N 计数、y 允许、n 或空回车拒绝、中断余项合成 deny。`duo --json "任务"` 提供 NDJSON 七类事件流 + 退出码契约，脚本不碰 TUI 拿稳定词汇。glob/grep/@file 补全共用一套自研 .gitignore 判定器。技能目录 watch 热加载，改完即生效。剩 2 轮时提醒模型收敛。

## User Stories

**steer 与暂停（CLI/Web）**

1. 作为 CLI 用户，执行 agent 途中想起遗漏的约束，我要直接打一行字且下一步就生效（提示行回显「已插队」），不用等 turn 跑完再重说。
2. 作为 CLI 用户，执行途中想换成全新任务，我先 Ctrl+C 暂停当前 turn，再发新消息开新轮，两者互不污染。
3. 作为 CLI 用户，暂停时已流出的回答内容要保留在会话里并带中断标记，我不丢失已生成的部分。
4. 作为 CLI 用户，暂停时还没执行的待派发工具调用要在会话日志里补上合成结果，我重放会话时不看到悬空调用。
5. 作为 CLI 用户，暂停后我发下一条消息即从中断现场续接，不需要额外的 resume 命令。
6. 作为 CLI 用户，运行期误按一次 Ctrl+C 只中断不退出，我要连按两次才真正退出进程。
7. 作为 CLI 用户，空闲时按 Ctrl+C 直接退出（维持终端本能）。
8. 作为 Web 用户，agent 跑偏时我点输入框旁的停止按钮即暂停当前 turn，语义与 CLI 一致。
9. 作为部署者，当运行环境不支持 SIGINT 拦截时，我仍可用 `/stop` 行命令完成同样的暂停。

**后台任务与输出转存（模型/CLI/Web）**

10. 作为模型，遇到长命令（构建、测试、安装），我以 `run_in_background` 转后台立即拿回 taskId，继续做别的事。
11. 作为模型，我用 task-output 带 block/timeout 等待或轮询后台任务输出，读的是尾部窗口。
12. 作为模型，我用 task-stop 终止不再需要或失控的后台任务。
13. 作为 CLI 用户，后台任务完成时 agent 在跑则本轮收口后收到合并通知，空闲则立即开新轮——通知必达、不喧宾夺主。
14. 作为模型，bash 输出超过 inline 预算时我拿到 spillPath，可用 read 回读全量输出而不丢信息。
15. 作为 CLI 用户，执行区提示行显示当前后台任务数与最近完成情况，我知道有什么在飞。
16. 作为 Web 用户，状态面新增后台任务区块（taskId/命令摘要/状态），我不开终端也知道后台在跑什么。
17. 作为用户，我暂停当前 turn 时后台任务继续跑、完成照常通知，暂停不误杀长任务。

**审批小队列（CLI/Web）**

18. 作为 CLI 用户，一次 turn 里多个工具先后请求审批时，我逐个作答且界面显示「第 i/N 个」，不会答丢也不会一次性糊我一屏。
19. 作为 CLI 用户，我按 y 允许、按 n 或直接回车拒绝（空回车=拒绝的 fail-closed 兜底），误触不会放行危险操作。
20. 作为 CLI 用户，我在审批等待中按 Ctrl+C 中断时，队列里剩余待答项全部合成 deny 收场，不留悬挂请求。
21. 作为 Web 用户，多个审批/提问卡片排队展示，我按 Esc 等价于拒绝当前项。

**headless `--json`（自动化消费方）**

22. 作为自动化脚本，我用 `--json` 跑任务拿到逐行 JSON 事件流（session/status/thinking/text/tool_call/tool_result/error/final），不碰 TUI 就能解析全过程。
23. 作为自动化脚本，我用进程退出码判断成败（completed→0 否则 1；SIGTERM→0、SIGINT→130），不解析文本猜结果。
24. 作为自动化脚本，最终答案在 final 帧完整无损（豁免截断），我不必从 text 帧自己拼。
25. 作为自动化脚本，我用 `--session-id` 恢复既有会话续跑，多步流水线可拆段执行。
26. 作为自动化脚本，headless 模式下审批与提问自动 deny 并发显式 error 帧，流程永不静默挂死；诊断信息只走 stderr。

**.gitignore 判定（工具消费方）**

27. 作为用户，glob/grep 自动遵循逐级 .gitignore 与 .git/info/exclude，搜索不再被构建产物与依赖目录淹没。
28. 作为用户，@file 补全索引与 glob/grep 同口径——补全里看得到的目标 grep 一定也看得到，反之亦然。
29. 作为用户，`!` 反选、`**`、字符类、目录限定、锚定等常用 .gitignore 语法都生效；写坏的行被静默跳过不报错。
30. 作为用户，仓库没写 .gitignore 时，node_modules/target 等产物目录仍被硬编码清单兜底排除。

**搭车：技能热加载与上限感知**

31. 作为用户，我改了 SKILL.md 或新增技能后，当前会话无需重启即生效，目录消息按内容变化只重发一次。
32. 作为用户，文件 watch 在我的环境不可用时，技能退化为启动扫描并给出日志警告，功能不劣化到不可用。
33. 作为模型/用户，agent 距离迭代上限还剩 2 轮时收到 system-reminder 提醒，主动收敛并交付结论而不是被硬掐。
34. 作为用户，达限行为保持现状（completed=false 可见化），提醒不会偷偷放宽上限。

## Implementation Decisions

**架构底座（ADR-0025 决策一）**

- CLI 主循环从阻塞 readLine 改为事件驱动：虚拟线程常驻读 stdin，每行注入 agent 域收件箱；渲染与输入解耦。不引 NIO/jline。
- 收件箱由 M19 单级升级为两级，CLI 与 Web 双呈现位同源：`next-step` 级在 step 边界注入当前 turn（CLI 执行期键入默认去处，提示行回显确认）；`next-turn` 级在 turn 收口后生效（后台完成通知等内部事件与显式排队消息）。注入 API 演进既有 `injectUserMessage` seam，消费语义向后兼容（Web busy 注入 202 路径不变）。
- 收件箱为内存态（个人工具，消费时才落 user/message），不做事件溯源持久化。
- 暂停（协作式中断 + 恢复）：SIGINT 经 `sun.misc.Signal` 拦截（不可用降级 `/stop` 行命令）；中断 = 置中断标志 → 当前工具调用终止（复用 bash 杀树）→ 已流出文本落 assistant 消息并打 interrupted 标记（新增会话事件类型，投影渲染中断标记）→ 未派发工具调用补合成 tool/result（复用 M16 合成闭合先例）。会话停在可恢复态，下一条用户消息续接。运行期单击中断、再按退出；空闲单击退出；Web 输入框旁停止按钮（新增停止端点）。

**后台任务（ADR-0025 决策二）**

- bash 工具新增 `run_in_background` 参数：转入内存任务注册表返回 taskId；超时语义维持 120s 杀树不变，不做超时自动转后台。
- 新增 task-output（block/timeout/尾窗读输出）与 task-stop 两工具，注册进工具域；不加 task-list。
- 输出三层预算：inline 尾窗 30k 字符 / spill 落盘帽 64MiB / task-output 尾窗 32k，yml 可配（挂 fs-tools 行 config，沿用既有解析器先例：缺席=缺省、错误即插件 FAILED）；超帽告警不静默（修 DSH 静默删 spill 坑）；spill 文件落 Duo home 临时区，read 可回读。
- 完成通知 first-wins 每任务至多一条：agent 空闲即开新轮；busy 挂 next-turn 级、turn 收口多条合并为一条注入。暂停不杀后台任务；进程退出注册表全灭。
- 后台任务可见化：CLI 提示行（后台数/最近完成）+ Web 状态面区块（/api/status 扩展字段）。

**审批小队列（grill Q8）**

- 决策枚举 allow/deny（既有 InteractionAnswer 语义，不扩值）；CLI 端 answerer 升级队列形态：逐个呈现带「第 i/N」计数，y=允许、n 或空回车=拒绝、其他输入提示后仍视为拒绝；中断时余项全部合成 deny 移除。Web answerer 由单 Pending 升级为排队，卡片真监听 Esc=deny。「总是允许（项目/会话）」归 M24 权限规则引擎。

**headless `--json`（grill Q9）**

- 入口形态：`DuoMain` 参数扩展——`--json` 标志进 headless 模式，positional 参数为任务文本，`--session-id` 恢复既有会话；yml 路径参数兼容现状。无任务文本且非 TTY 时 usage error。
- NDJSON 七类词汇：session / status（turn_start/step_start/step_end/turn_end，usage 缺样本整项省略）/ thinking / text / tool_call / tool_result / error / final。text/thinking 仅在 assistant/message 提交点发射（commit-point 投影）；final 帧承载无损答案、豁免截断。
- 截断降级链：单值 8KB、单行 32KB → `truncated:true` → 降级标量 → 退化 `{type,truncated}`。
- 退出码契约：completed→0 否则 1；SIGTERM→0、SIGINT→130。诊断只走 stderr；流内禁交互——审批/提问请求自动 deny + 显式 error 帧。
- headless 复用既有装配链（agent/治理/工具全套在位），仅呈现位换为 NDJSON 投影器。

**.gitignore 判定器（ADR-0025 决策三）**

- 自研 Java 判定器组件（放工具域 fs 包，agent 模块的 @file 索引经依赖引用）：逐级堆叠解析 `.gitignore` + `.git/info/exclude`；语法全常用子集（`!` 反选、`**`、`*`、`?`、`[]`、尾 `/` 目录限定、前导 `/` 锚定、`\` 转义）；坏行静默跳过。
- 判定并集 = .gitignore 规则 ∪ 硬编码产物目录（node_modules/target 等，现 @file 索引清单为底本）∪ VCS 元数据目录；三个消费点（glob、grep、@file 补全索引）全部替换各自写死的排除逻辑，共用同一判定器实例（同口径）；性能护栏沿用既有条目上限。全局 core.excludesFile 不做。

**搭车（grill Q10/Q11）**

- 技能热加载：SkillRegistry 增 watch（WatchService 监视发现根，含目录创建），变更 → revision 递增 + 缓存失效 → 重扫描；技能目录消息按内容 sha256 digest 去重，变化才重发、只重发一次（经 prompt 注册表片段刷新）；watch 不可用降级启动扫描 + 日志警告，不阻断发现。
- 迭代上限感知：send 循环剩 2 轮时在下一请求组装时附加 system-reminder 提醒段（不落用户消息）；达限行为不变（completed=false）。

**模块改动面**：duo-harness-agent（收件箱两级、中断语义与事件、上限提醒、技能 watch、@file 索引换判定器）、duo-harness-cli（事件驱动 REPL、Ctrl+C/`/stop`、提示行、审批队列键位）、duo-harness-web（停止端点与按钮、状态面区块、审批队列与 Esc）、duo-harness-tools（bash 后台与 task 面、输出分层、忽略判定器与 glob/grep 接线）、duo-harness-example（headless 入口参数）。

## Testing Decisions

- **好测试标准**：只断言外部行为——会话日志事件序列、工具返回 JSON、stdout NDJSON 帧、退出码、Answerer 应答结果；不断言内部队列/线程/缓存实现细节。核心逻辑走 tdd 红绿循环。
- 七组测试面全部复用既有 seam（grill 确认，不新建测试面）：
  1. **agent 域 ChatAgent seam**（fake LLM 驱动）：收件箱两级消费时序、通知合并、暂停中断（interrupted 标记 + 合成结果 + 可恢复）、上限感知提醒。先例：SteerInjectionTest、ToolCallingAgentTest。
  2. **交互 seam**：审批队列 i/N、n/空回车=deny、中断合成 deny、亲和路由不回退。先例：InteractionRegistryTest、ConsoleAnswererTest、WebAnswererTest。
  3. **工具域 bash seam**：run_in_background 返回 taskId、task-output block/timeout、task-stop、spill 落盘与超帽告警、暂停不杀后台。先例：FsBashToolTest、PipelineTimeoutTest。
  4. **忽略判定器**：纯函数单测（语法子集矩阵、堆叠、并集）+ glob/grep/@file 三消费点各一条集成断言。先例：FileReferenceIndexTest、FsToolsTest。
  5. **装配级 headless**：测试 yml + 假 LLM 装配，捕获 stdout 断言帧序列与退出码契约。先例：BootTest、CliPluginAssemblyTest。
  6. **技能热加载**：@TempDir 发现根 + 文件变更驱动，断言 revision/digest 与目录消息重发一次。先例：SkillRegistryTest。
  7. **Web 端点**：停止端点触发中断语义、状态面后台字段、Esc=deny。先例：WebFaceTest。
- SIGINT 拦截不可用环境的降级路径（/stop）必须有测试覆盖（sun.misc.Signal 在 CI 环境的可理性不能假设）。

## Out of Scope

- task-list 第三工具；超时自动转后台（后续可配项，ADR-0024 拒绝项）。
- 「总是允许（项目/会话）」与权限规则引擎、只读命令免审批、Bash(prefix:*)——全部 M24（ADR-0024）。
- 审批枚举的 modify/escalate 值（无 hook 改写场景）；全局 core.excludesFile。
- TUI/raw mode/单键 Esc（jline3 引入被拒）；NIO。
- 收件箱事件溯源持久化（内存态够用）；headless 流内的 steer 交互。
- safeFlags 只读策略体系（M24 后按需扩）；迭代上限「按任务类型自动放宽」。
- Web 标签级会话绑定（M24）；完整多会话协调（1.0 后）。
- PTY/持久终端（1.0 后菜单）。

## Further Notes

- 工单拆分建议按依赖序：事件驱动主循环与收件箱先行（喂饱全期），后台任务、暂停、审批队列次之，headless/.gitignore/搭车收尾——以 /to-tickets 输出为准。
- 验收必须覆盖行为迁移的正反两面：执行期键入插队生效、开新任务走暂停（ADR-0025 Consequences 显式化要求）。
- limitations 对账随实现销账：M7#2（技能热加载）、M12#2（.gitignore）、M16#1（上限感知）、CLI steer 缺口。
- 本 spec 与 ADR-0025、术语表增改、backlog 销账注记同一批提交（用户裁定：grill 产出 + spec + 工单一笔提交）。
- 用户可见变更按红线 6 随各实现工单同 diff 记入 CHANGELOG 未发布段。
