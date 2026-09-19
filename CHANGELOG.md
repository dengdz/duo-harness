# Changelog

本文件记录 duo-harness 的用户可见变更。版本号规则见 `.agents/skills/duo-workflow/references/版本号.md`。

## 0.15.0（未发布）

### Changed

- **技能经验文档归位 references/ 并全量预建**：duo-* 技能的 experience.md 从 SKILL.md 平级迁至 `references/experience.md`（对齐技能规范格式，references 承载辅助文档）；经验文档 14 技能全量就位——duo-code-review / duo-skill-evolution 迁移保留既有内容，其余 12 个按元技能规范预建模板；全部 SKILL.md 增补"经验参考"段（执行前读经验、任务后复盘追加同一文件）并统一新路径引用——修正自我进化收尾流程不被触发的问题
- DSH web 工具族研究落盘（`docs/research/DSH/web工具族/` 三件套，锚点 ddefc45f = release 0.1.6-alpha.2）：ctx.web seam 双注册表与执行期选路（配置点名三态 / 唯一可用自动选 / 多可用报歧义）、web-fetch-http 的 SSRF 纵深防线（URL 字面预检 → DNS 全地址集公网校验含 NAT64 → 连接 pinning 防 rebinding → 重定向同源且每跳重校验）、tool-web 的 web_fetch（turndown GFM 转换 + 深度护栏 + 三层限额 + 不可信数据声明）与 web_search（多 query 并发合并、provider 可配、无 key 时工具仍注册执行期报结构化错误）及 deepseek/exa/perplexity 三 provider 对照——供 M20 web 工具族（ADR-0016）设计访谈对照

## 0.14.0（2026-09-20）

### Fixed

- **双开重启后权限档恢复被覆盖**（BUG-20260919-03，工单 06 验收实测）：恢复语义未分档——CLI 占用被迫改开的新会话把另一呈现位刚恢复的档位重置回缺省；修复分两层：启动续接只恢复不重置、显式换绑（/new/新话题）无记录才重置缺省；占用改开的新会话继承被占会话最后切定档并落事件（治理态不因呈现位轮转而丢）
- **压缩切分在工具对收尾形态下永远放弃折叠**（BUG-20260919-01，工单 03 验收实测）：投影尾部为 [助手(工具调用), 工具结果] 收尾（带工具调用轮次的常态）时，切分点向后找 USER 边界一路推到末尾，手动与自动压缩都误判"近端不足"放弃——切分点改为向前回退到最近 USER，近端多留一轮换配对完整，含 USER 消息的投影总能折叠

### Added

- **斜杠命令注册表**（工单 01，ADR-0020）：agent 域发布 "commands" 服务——命令由插件代码注册（名 + 描述 + 适用呈现位 ANY/CLI/WEB + busySafe 缺省 false），CLI 与 Web 共享同一入口顺序：命令 → 技能直调 → 未知命令报错附可用命令与技能清单。CLI 的 /new、/permission、/plan、/exit 迁入注册表（行为不变）；执行落 `command/run` / `command/done` 审计两事件（投影排除——命令操作 harness 不进模型历史，崩溃断口可观测）；/permission 声明 busySafe，agent 执行期间切档立即生效，其余命令空闲才执行。boot yml 装 `dev.duo.harness.agent.commands.CommandsPlugin` 行即启用（cli 插件硬依赖本服务）
- **还账三件**（工单 06，ADR-0020 决策 10/11/12）：① 权限档持久化——`/permission` 切档落 `permission/mode` 会话事件，重开该会话恢复最后切定档、新会话回 yml 缺省（档位跟对话走，不跨会话惊吓）；② `/title 新标题` 改名命令（双面可用，busySafe——纯事件写，侧栏/标签页即时生效，latest-wins 覆盖自动标题）；③ web 插件 config `pageSize` 可配首屏/每页消息数（缺省 50 不变）
- **/compact 手动压缩 + 压缩点事件化**（工单 03，ADR-0020 决策 6）：上下文逼近窗口时 `/compact` 手动压缩（双面可用，CLI 与浏览器都可敲）——远端历史折叠为四小节摘要并落 `context/compacted` 压缩点事件（触发方式 manual/auto 随事件可审计），投影按最后压缩点拼接（之前以总结替换、之后照常——总结输入为压缩点之前全部历史），刷新/重开压缩态天然恢复；预算触发的自动压缩同事件化——一处语义两处触发，且事件化后**不再每轮重复总结**（现状为请求期纯变换、每轮重复折叠调用 LLM）；`governance.keepRecentRatio` 随事件化停用（字段保留解析兼容，值被忽略）。"上下文为何变小"在日志可审计（M9-M11#3 销账）
- **Web 斜杠入口**（工单 02，ADR-0020 决策 3/5）：浏览器输入框识别斜杠——与 CLI 共享同一入口顺序（命令 → 技能直调 → 未知报错）：命中命令在 Web 进程内执行（不进模型历史），命令行与结果经既有事件流渲染（刷新/回放可见）；未命中 `/xxx` 报未知命令附可用清单（按发起面过滤适用性）；CLI 专属命令提示"该命令仅在 CLI 可用"；agent 执行中 busySafe 命令照常执行、其余明确提示等待空闲。斜杠文本不再透传给模型（M12-03 验收事故销账）。`/permission` 为双面命令（handler 只依赖全局 workspace 服务，浏览器直接切档——M19 用户故事 1）
- **呈现位亲和路由（谁发起谁作答）**（工单 05，ADR-0020 决策 7）：工具执行携带发起呈现位标记（presenterId），审批/提问/计划呈交的 ask 请求优先路由给发起方的回答者——双开部署下 CLI 发起的审批在终端 y/n 作答，不再跳 Web 卡片、终端零提示（M12-02 事故销账）；发起方缺席/放弃才轮注册序（单呈现位部署零感）。交互工具（exit_plan_mode）的会话供给按发起方亲和——批准/打回事件写进发起方会话，计划状态不再串位（limitations 交互工具绑定条销账）；hooks 载荷增 `presenter_id`（载荷上下文透传部分消化）
- **父级 steer（运行中消息注入）**（工单 04，ADR-0020 决策 8）：Web 执行中发消息不再 409——进 agent 注入收件箱，send 循环在迭代边界排干为普通 `user/message`（下一轮请求即可见，不打断飞行中的工具组，多条照排），页面 toast"已注入，待当前步骤完成"；与子代理 send_message 的"下一轮生效"语义对称。CLI 不接（行缓冲天然排队，ADR-0020 决策 9）
- **duo-code-review 技能改为"两轮审查即修复"流水线**：第一轮 mattpocock code-review 双轴审查 → 审查报告 → 修复，第二轮 open-code-review 行级审查 → 审查报告 → 再修复，最窄测试收口（不自动复跑 OCR）；技能正文收敛为步骤与调用指向，两段审查的具体用法分别以对应技能为准；仓库静态审查标准迁出技能，沉淀为持续维护的审查总结文档 `.scratch/review-log.md`（已知模式种子 + 逐次审查追加），报告按轮落盘（工单「审查轮」小节 / `.scratch/<feature>/reviews/`）

## 0.13.0（2026-09-18）

### Added

- **hooks 生态兼容扩展**（工单 03/04，ADR-0019）：复用 Claude Code/Codex 的 hooks 配置格式——`~/.duo/hooks.json` 与两家同形（Claude Code settings.json 整文件粘贴即用，未知键宽容、Codex 扁平条目兼容），`PreToolUse` / `PostToolUse` 两事件挂工具三段管线：exit 2 阻断且 stderr 回给模型（PostToolUse 为结果改写、不假装撤销副作用）、exit 0 stdout JSON 裁定三形兼容（`permissionDecision` 三值——deny 阻断 / allow 放行 / ask 交审批段裁决；兼容 legacy `decision`）、matcher（全匹配 / 精确名多选 / 正则）、条目级 `timeout` 缺省 600s。失败语义 fail-open 全线（钩子超时/崩溃/起不来一律放行 + WARN）——**钩子不是执法边界**，硬闸门需求由 guard/审批承担；boot yml 装 `dev.duo.harness.hooks.HooksPlugin` 行即 opt-in，不装行零感知。载荷一期含 hook_event_name / tool_name / tool_input / cwd（PostToolUse 增 tool_response）
- **插件可选依赖**（工单 01，ADR-0019）：插件新增 `optionalInject()` 声明"就绪则用、缺失不拦"——可选服务缺席不再永久 PENDING，服务出现/消失自动重载（升级↔降级双向对称）；CLI 支持纯对话装配：boot yml 不装 fs 工具行照常启动聊天，`/permission` 降级提示"未挂载"
- **awaitStartup 可配超时**（工单 02）：编程挂载新增 `awaitStartup(Duration)` 重载——超时抛点名异常（含缺失服务清单）、零时长即立即探测；超时后插件保持 PENDING、服务到达照常激活；无参版语义不变（无限等待），但进入等待打 INFO 日志点名在等谁——编程挂载的静默卡死从根上消除
- **duo-code-review 技能重写并瘦身**：保留本仓库特有内容——开工前检查（固定点可解析、diff 非空、双轴前提）、覆盖率台账（基数取 `git diff --stat` 全集，每文件只有"已审 / 跳过(附理由)"两个终态）、阻断 / 建议 单一分级与文档核对词表、九项手工维度、报告模板与修复权限闸门；OCR 的用法描述不再自行复述，收敛为一句"按照官方 `open-code-review` 技能执行"（环境检查、命令参数、降级与陷阱以官方技能为准，委托模式同 `open-code-review-delegate`）；保留实测得出的非官方事实——OCR 默认不审 `src/test/**` 与文档（自定义 rule 也捞不回来），测试与文档划归手工必查项；新增报告持久化要求——审查结束即落盘（单工单并入工单「审查轮」小节，批次/分支审落 `.scratch/<feature>/reviews/日期-范围.md`），修复轮同处续写处置结果

### Fixed

- **管线超时双呈现位叠挂**（M17 backlog 双挂债）：cli + web 双开共享工具域时管线超时监听器被各挂一次（嵌套超时、语义含混）——挂载查重先到先得，第二次挂载跳过

## 0.12.0（2026-09-18）

### Added

- **并发工具调度**（工单 01，ADR-0018）：单轮多个 tool_calls 按并发安全性分流执行——`read`/`glob`/`grep` 等纯只读工具进虚拟线程并行池同时跑（调研类轮次总耗时接近最慢者而非逐个累加），写/命令/交互/子代理等独占工具作顺序屏障单独执行（屏障期间不与任何工具同飞），需审批调用永不进池；无论完成先后，工具调用与结果事件严格按 model 序成对提交——事件日志形态与串行时代同构，断线重连、刷新恢复、崩溃闭合行为零变化。并发度 `maxParallelToolCalls` 缺省 10、`web`/`cli` 插件 config 可配，配置为 1 即完全串行（排障开关）
- **工具执行超时推广为管线缺省**（工单 02，ADR-0018）：任何工具最多执行 120s（`pipelineTimeoutMs` 可配）——卡死的工具以超时错误结果回填、循环继续，会话不再被一个挂死的读取永久占住；`ask_user` 等人回答豁免不限时；bash 保留模型可传 `timeoutMs` 的协作式超时与杀进程树语义（管线上限放宽到协作式之上只兜挂死）
- **todo_write 任务分解抓手**（工单 03，ADR-0018）：多步任务开工前先拆成结构化清单（全量整表替换，pending / in_progress / completed 三态）——Web 输入框上方常驻折叠面板（圆圈状态实时流转、刷新恢复、新话题自动清空）+ 会话流工具行摘要 + CLI 计数行；模型只收一句计数回显，完整清单只走事件流，不重复撑大上下文
- **迭代上限 Web 可见化**（工单 04，BUG-20260917-03 验收遗留）：agent 达迭代上限返回未完成时，Web 面直推一帧错误卡（含失败说明）——页面不再无提示地停住（CLI 原有 `[异常终止]` 行为不变）

### Fixed

- **超时后迟到结果覆盖错误**（工单 02 验收实测）：超时中断工具后，被中断工具的迟到返回值可竞态覆盖超时错误（flaky 形态，时序运气下偶现正常）——超时结果终局冻结，迟到的真实结果一律丢弃
- **todo 面板跨会话残留**（工单 04 验收实测）：新建会话后上一会话的任务清单面板残留在输入框上方——整窗替换基线补清空动作，新建/切换会话两路径同治

## 0.11.0（2026-09-18）

### Added

- **会话崩溃恢复——悬空工具调用合成闭合**（工单 08）：进程在工具调用落盘与结果落盘之间中断（崩溃/审批等待被中断），会话留下无结果的悬空调用，后续每轮请求被 provider 以协议错误拒绝、永久无法续聊；现打开会话取锁后自动探测并追加合成闭合（结果注明"因进程中断未知，只可重试只读/幂等操作"），日志自包含、历史损坏会话打开即自愈
- **LLM 流式空闲超时**（工单 05）：连续 90s（`llm.streamIdleTimeoutSeconds` 可配）无新字节即中止流——provider 半开连接不再永久挂死 agent 循环（CLI 卡死/Web 单飞占用）；尚未输出内容时按可重试错误走重试链，已输出内容后中止并保留已生成文本
- **子代理审批钉死**（工单 03，M15 已知限制消除）：子代理调用声明需审批的工具不再挂起等待人工——确定性拒绝并回传理由（附交回父代理指引），子代理循环继续、在最终回答中说明限制；策略为装配处注入的恒否对象，放宽只换注入。**行为变更**：需审批声明涵盖区内写（档位闸门原会静默放行）——子代理的写产出步骤现在回退父代理执行（M15 的 researcher 写报告流程改由父代写）
- **子代理运行环境段**（工单 04，M15 已知限制消除）：子代理 system 携带工作目录/操作系统/当前时间（spawn 时现场生成），模型不再对运行环境两眼一抹黑
- **Web 入口栅栏**（工单 02）：Web 面全部端点前置两级校验——全请求 Host 头白名单（回环地址+端口，封死 DNS rebinding）+ POST 端点 Origin 空/同源校验（跨站 POST 一律 403）；本地 curl 与同源浏览器不受影响，GET/SSE 不校验 Origin
- **日志规范统一**（工单 06）：llm/session/agent/web/cli 五模块诊断日志迁入 SLF4J（级别可关断）——agent 降级诊断 warn、Web 异常与连接观测 info/debug；REPL 交互输出与启动横幅保持 stdout 不变
- **迭代上限呈现位可配**（BUG-20260917-03）：`web` / `cli` 插件 config 新增可选 `maxIterations`（正整数，缺省 10 不变）——单轮对话的 LLM 往返上限从此可按部署调节；非正整数启动即 FAILED 点名

### Changed

- **WebFace 路由表拆分**（工单 07）：注册方法从 333 行内联收敛为 13 行路由表 + 11 个端点处理器 + 统一响应 helper，新增端点不再嵌进巨方法
- **回答端点结构化协议**（工单 07）：`POST /api/answer` 改 `{decision: "approve"|"reject"}` 与 `{answers: [...]}` 两种互斥形态（前端同 diff 切换、无兼容层）——旧实现以自然语言"拒绝"作隐式协议，用户在提问卡输入"拒绝"二字会被误判为审批拒绝；新协议按字段判定，根治该误判
- **静态资源禁缓存**（工单 07）：单页与 /web/ 资源响应加 `Cache-Control: no-cache`——前端更新刷新即生效，不再依赖强刷

### Fixed

- **计划模式在真实任务上被迭代上限硬停**（BUG-20260917-03）：计划模式的引导式探索（连读多份文档/技能再设计）在真实仓库上常超十轮，循环在 `exit_plan_mode` 呈交前以"已达最大迭代轮数"终止、计划卡永不出现；现可经 `maxIterations` 显式调高预算（缺省行为不变，防失控硬停保留）
- **双呈现位下计划呈交卡不可达**（BUG-20260917-04）：CLI 发起的计划复核此前无审计留痕——双开部署（CLI 与 Web 必为不同会话）下浏览器收不到任何计划事件、卡永不渲染，请求又被优先路由给 Web 回答者致终端也无提示，阻塞至超时；现计划复核与审批同通道留痕（`approval/requested` 携 `exit_plan_mode` 身份与计划全文），浏览器计划卡正常渲染与作答，纯 CLI 部署改由终端呈现计划全文与复核选项

## 0.10.0（2026-09-17）

### Added

- **子代理任务分解**（M15，ADR-0015）：父 agent 可经 `spawn`（全新）/ `fork`（带父对话背景播种）把子任务交给同进程内嵌的子代理——立即返回 agent id，子代理在后台虚拟线程用主 agent 同款循环（LLM ↔ 工具执行回填）独立运行，完成后最终回答自动回流父对话；子代理事件独立成档（`~/.duo/agent-sessions/subagents/`，不进侧栏），完成后可打开回放全程；fork 播种父日志平衡完成轮前缀并落种子边界（继承背景与子代理自身行为可区分）
- **子代理控制面**：`send_message`（运行中纠偏——指示写入子会话下一轮生效；空闲/失败则开新轮）/ `interrupt_agent`（协作式中止，子会话留可审计的中止痕迹）/ `list_agents`（状态四态：运行中/空闲/失败/已中断）
- **子代理模板制装配**：yml 的 `subagent` 插件 config 定义子代理能力边界（工具清单 + 可选专属提示 + 可选迭代上限，缺省 30），spawn/fork 时模型只点名模板——工具配置权在部署者；交互工具与控制面五件强制不进子模板（框架过滤不可绕过）；未配置模板的部署零变化
- **子任务卡呈现**：Web 对话面子任务卡三态（运行中/完成/已中断）随 SSE 实时流转，完成态携结果概要折叠与"查看子任务全程"入口——右侧抽屉复用主对话渲染器回放子会话全程（含 fork 播种的背景段，种子边界居中标注）；CLI 打印 `[子任务]` 派生/完成过程行（完成行携最终回答）
- **子代理框架基线**：子代理 system = 框架基线（无跨任务记忆、只做交接的一件事、范围外不深挖、结果被截断改精确查询、结论即交付）+ 模板专属提示——通用纪律归框架一处维护，部署者模板只写角色

### Fixed

- **子代理治理管线未生效**（0.10.0 验收期实测发现）：子代理此前在 yml 未配 governance 段时完全不治理（与父的"缺省常量治理"不一致）——大量工具结果全量灌入上下文；现与父严格同配置（恒建治理实例）
- **治理过程日志刷屏**（M9 既有）：修剪/计量逐轮 println 在子代理后台长跑时淹没对话流——默认静默，诊断用 `-Dduo.governance.verbose=true` 开启
- **子代理未完成时成果丢失**：达迭代上限时父会话只收到一句"未完成"——现回流部分成果（工具调用摘要 + 末次结果摘录）与续跑指引（`send_message` 可带完整上下文续轮）；CLI 同步呈现摘要摘取

## 0.9.0（2026-09-16）

### Changed

- **limitations 收编定稿**（M14 工单 06）：已知限制清单恢复"唯一权威来源"完整性——"M7/M8（未发布）"陈旧标题改正为 0.3.0，补录 M9-M11（0.4.0-0.6.0）留档限制三条（CLI idle 无热恢复、交互工具会话绑定先到先得、compaction 无手动入口），过期去向标注清理；配合分页投影优化，"每次全量投影"限制条目消除（见下）
- **事件快照读侧零拷贝**（M14，ADR-0014）：`Session.events()` 从每次锁内全量拷贝改为共享不可变快照——追加在锁内重建、读侧 O(1) 返回同一引用；对外语义不变（调用时刻稳定视图、与追加并发隔离、遍历无 CME），投影/回放/分页等读侧消费方自动受益
- **分页定窗单趟化**（M14，ADR-0014）：消息窗口计算从三趟全量遍历收敛为单趟（O(max) 下标环形缓冲 + earlier 基线扣减回折前移量），窗口边界语义逐字不变；80K 事件会话"定窗+投影"实测 17.1ms → 7.6ms（-56%，达标线 15ms），基准转正为 `SessionPerfBenchmarkTest`（默认跳过，`-Dperf.benchmark=true` 启用）——已知限制"分页与尾部快照每次全量投影"就此消除
- **agent 按域拆包**（M14 工单 05，duo-project-structure 达标）：根包 27 类收敛为"循环契约门面（6 类）+ 四域子包"——`governance` / `skills` / `plan` / `prompt`；**注意**：yml 插件行中的插件类为全限定名反射加载，prompt/skills/plan 域插件类名随之带子包路径（如 `dev.duo.harness.agent.prompt.PromptPlugin`、`dev.duo.harness.agent.skills.SkillsPlugin`），自写 yml 需同步更新（demo yml 与文档示例已迁移）
- **装配测试密闭化**（M14 工单 01）：DuoHome 解析链新增最高优先级的系统属性 `duo.home`（`duo.home` > `DUO_HOME` 环境变量 > 缺省 `~/.duo`，部署侧 `DUO_HOME` 语义不变）；两处装配用例改临时目录自足——`mvn test` 不再依赖本机 `~/.duo/config.yml`

## 0.8.0（2026-09-16）

### Added

- **会话尾部窗口快照**（M13，ADR-0013）：Web 面首连/刷新/切换不再全量回放——服务端投影取尾部 50 条消息的事件区间下发（头帧携 hasMore 与更早计数），大会话秒开；游标增量回放语义不变
- **历史向上分页**：新端点 `/api/session/page`（按事件序号向前取每页 50 条投影消息，携 hasMore 与更早计数）；前端滚动到顶自动加载更早一页，顶部占位"更早还有 N 条"，视窗不跳屏，耗尽后占位消失
- **切换与新建无刷新**：侧栏切换 / ＋新话题不再整页重载——断流 → 换绑 → 重连收新会话尾部快照整窗替换，页面状态不再因重载丢失（切换/新建时输入框按语境显式清空）
- **治理阈值 yml 化**（ADR-0013）：`web` / `cli` 插件 config 新增可选 `governance` 段——spill/修剪阈值、压缩比例、窗口 tokens、保留比、最小折叠数六字段可省（缺省即 0.7.0 行为）；未知字段、类型与数值越界启动即 FAILED 点名；双开两段互不同步为已知取舍
- **侧栏占用标注**：`/api/sessions` 逐会话占用探测（occupied 字段）——被占会话灰显标"使用中"，只提供预期，点击仍可尝试（撞锁报错保留）
- **会话标题（精简版）**：首条消息落日志后异步生成一次（独立限时直答，失败/超时降级为首条前 20 字），以 `session/title` 事件落会话日志；侧栏显示标题（无标题回退 id）、浏览器标签页实时同步；不重生成、不可改名

### Fixed

- **流式输出中途刷新丢内容**（0.7.0 既有）：刷新/断线重连后，进行中回复的已输出部分不再空窗，流结束整段覆盖不重复、不碎片化
- **切换会话后发消息必报错**（M13 验收期发现）：会话变更回调单槽被标题接线覆盖导致 agent 不重建（分脑回归），已合并为单次注册

## 0.7.0（2026-09-15）

### Added

- **本机 fs 工具族六件**（M12，ADR-0012）：新插件 `tools.fs.FsToolsPlugin`（yml 一行装配）——`read`（三帽窗口 + 精确总行数 + 自描述续读 footer + 二进制拒读）、`write`（原子替换）、`edit`（LF 归一匹配域 + 四态结构化失败 + `replace_all`）、`glob` / `grep`（Java 自实现，跳 VCS 目录，截断回收）、`bash`（每次调用全新进程、工作目录固定 workspace 根、env 硬化、stdin 接空设备、超时 clamp 缺省 120s 上限 600s 并终止进程树、每流 100K 字符护栏超限报省略量、非零退出以 `[exit code: N]` 进正常结果而非错误）。agent 从 MCP 沙箱演示级文件能力升级为 workspace 约束的真实项目操作能力
- **三档权限预设**（M12，ADR-0012）：`read-only` / `workspace-write`（默认）/ `danger-full-access`——路径感知的审批裁决：区内写放行、越界写与 bash 及 read-only 档写一律 ask（档位闸门 `WorkspaceGatePolicy` 前置短路，ask 委托既有审批管线）；CLI 新增 `/permission [档位]` 运行时查看与切档（重启回 yml `mode` 缺省）
- **读前写闸门**：`write` / `edit` 覆盖已有文件须本会话先用 `read` 读取（未读拒绝并提示先读，新建豁免）——不盲改未见过的文件
- **档位审批插件 `WorkspaceApprovalPlugin`**：与 `ApprovalPlugin`（always-deny / auto-approve）、`InteractiveApprovalPlugin`（无档位全 ask）三选一的审批策略，ask 落既有回答者瀑布（Web 卡片 / 终端 y/n）

### Changed

- agent demo（agent-demo.yml）移除 MCP files 沙箱挂载与写保护演示行——本机 fs 工具族取代，模型工具清单不再有 `mcp__files__*` 双写选型噪音；`AgentReplMain` 收敛为纯启动入口（无编程挂载段，启动命令不变）；MCP 机制演示保留在 DemoMain 的 M2 段（`demo-m2.yml`，mcpfs 模块保留）
- Web 状态面工具清单标题改"工具（本机 + MCP 远端）"；运行Demo / 组装你的第一个 agent / 插件配置参考 / 工具目录 / limitations 文档对齐 M12 装配

## 0.6.0（2026-09-15）

### Added

- **CLI 呈现位插件**（ADR-0011）：新模块 `duo-harness-cli`——终端 REPL 成为与 WebPlugin 对称的 Boot 插件（yml 一行启停，`disabled: true` 可保留配置地关闭），`/exit` 只结束终端呈现（会话锁释放、回答者摘除，插件树与 Web 面不受影响）；交互行为与既有 CLI 一致；纯 CLI / 纯 Web / 双开三种部署形态均成立（双开时回答者按 yml 行序路由，详见插件配置参考）
- **通用启动器 `DuoMain`**（ADR-0011）：Boot 装载 + 非守护保活 + shutdown hook 级联 dispose——Ctrl-C 确定性释放全部会话锁；不含业务装配，demo 专属挂载（MCP 沙箱等）经回调注入；`AgentReplMain` 瘦身为兼容壳（启动命令不变）
- **呈现位共享装配器**（agent 模块 `presenter` 包）：CLI 与 Web 的执行链装配单点（LLM 执行链工厂 `llm.LlmAdapters` / 治理 / ChatAgent / 交互工具查重注册），消除双份装配漂移

### Changed

- Web 面重试行为对齐配置：`llm.retry` 段现对 Web 面生效（此前仅 CLI 装配读取，Web 用固定默认）——未配置该段时行为不变（缺省值两侧一致）

## 0.5.0（2026-09-14）

### Added

- **上下文占用可视化**（M10，ADR-0009）：Web 状态面新增「上下文」行——`N / 窗口 tokens（占比 %，压缩阈值 M · 实测/估算）`，与治理判定**同源同口径**（provider 真实用量优先、本地估算兜底，口径显式标注），超阈值整行变红；治理触发不再只存在于终端日志
- **provider 真实 token 用量**（M10，ADR-0009）：LLM 请求携带 `stream_options.include_usage`，流末用量统计作为 `assistant/message` 事件可选字段落会话日志（历史会话向后兼容）；治理判定数据源切为真实值优先、估算兜底——阈值不再被 ±10-20% 估算误差干扰
- **SSE 增量回放**（M10，ADR-0010）：事件帧携带日志序号游标，浏览器断线重连自动经 `Last-Event-ID` 只补缺失段；首连与游标越界仍全量快照（宁可重放不可丢事件）；`replay/start` 帧标注模式，前端快照清空重建、增量保留页面
- **Markdown 渲染**（M10）：助手回复按 Markdown 整段渲染（代码块 / 列表 / 标题 / 引用 / 表格），marked + DOMPurify 单文件 vendor 入库（零 CDN、离线可用）；模型输出经消毒防注入；流式期间保持纯文本（避免半截语法闪烁）
- **交互反馈与错误可见化**（M10）：发送按钮「思考中…」状态机（本轮处理完成才恢复，与服务端单飞精确对应）；页面顶部 toast 提示（网络/服务故障、会话操作失败 5 秒可见，持续故障不刷屏）；原先静默吞错的五处路径全部改为可见处理
- **会话独占锁**（M10）：打开会话即取得文件独占锁——同一会话被第二个进程打开时明确报错（Web 面启动失败点名会话、CLI 提示后可改开新会话、页面切换提示占用），彻底消除"两个进程静默分脑共享同一日志"
- **纯 Web 部署 HITL 补全**（M10）：`WebPlugin` 装配链自行注册 `ask_user` 与计划呈交工具——无终端环境下提问卡与计划卡照常工作
- **Web 服务端加固**（M10）：会话切换 id 白名单校验（目录穿越防护）、POST 请求体 1MB 上限、错误响应不回显内部异常细节（仅服务端日志留痕）
- **fail-closed 语义钉死**（M10）：悬空交互的拒绝判据精确为"是否仍有人能看见它"——全部页面关闭且宽限期内无新连接入列才拒绝，**刷新页面不再误杀悬空审批**（卡片保留可继续作答）

### Changed

- Web 静态单页拆分为 `index.html` + `theme.css` + `app.js` 三件（无构建链维持，`/web/` 前缀白名单资源服务）
- `/api/status` 响应新增 `context` 字段（无治理装配时省略）；LLM 请求增加 `stream_options`（provider 不支持时无用量字段、治理自动回退估算）

## 0.4.0（2026-09-14）

### Added

- 上下文治理四件套（M9，agent 域）：`ContextGovernance` 读侧治理管线——spill（超大工具结果落盘 + 预览定位符）→ 工具结果修剪（超 8K 头尾收窄）→ token 计量（本地估算）→ compaction（超窗口阈值时远端历史折叠为四节摘要，近端原文保留）。**治理只影响模型看到的请求，会话 JSONL 日志永远完整**；各阈值常量集中于 `ContextGovernance`，演示装配默认启用
- `ToolCallingAgent` 新增治理构造器：投影 → 治理管线 → 请求；旧构造器保留（null = 不治理，零行为变化）

## 0.3.0（2026-09-14）

### Added

- 文档站补全（M8.5）：04-架构《设计主线》（框架叙事 + ADR 导览）、02-指南《组装你的第一个 agent》（可照抄教程）、05-参考《插件配置参考》《会话事件类型表》《工具目录》（含对账测试防漂移）与《术语表》（CONTEXT.md 迁入，仓库根留指针）——框架描述 / 设计思想 / 使用方式三层齐备，五章节骨架首次全量
- LLM 适配器（`duo-harness-llm` 新模块）：provider 中立流式调用契约 + OpenAI 兼容适配器——`baseUrl/apiKey/model` 配置化，DeepSeek/通义/Kimi/vLLM 等兼容 provider 开箱即用
- 聊天演示 `ChatReplMain`：REPL 交互（`你> `/`AI> `、`/exit` 退出）、流式打印、错误原样呈现；多轮对话有上下文记忆，启动自动继续最近会话（`/new` 开新话题），会话落 `~/.duo/sessions` 可回放
- `LlmConfig` 支持 `llm.systemPrompt` 可选配置（缺省内置指令；组装注册表属 M6）
- 会话模块（`duo-harness-session` 新模块）：会话事件溯源——`append` 唯一写入原语 + JSONL 同步落盘，`deriveMessages` 投影多轮上下文；旧格式会话文件向后兼容（M4）
- agent 模块（`duo-harness-agent` 新模块）：`ToolCallingAgent` 工具循环——Function Calling 闭环（模型自主发起工具调用 → 经三段管线与治理链执行 → 结果回填 → 最终回答），迭代上限防失控；审批拒绝 / guard 拦截结果原样回填，模型自行调整行为（M5）
- agent 演示 `AgentReplMain`：LLM 驱动 MCP 文件工具的完整闭环——读文件真实生效；写文件被审批拒绝后模型理解原因并向用户解释，过程叙述全程可见（M5）
- 思考模型支持：流式捕获 `reasoning_content`，工具调用链中按 provider 要求回传——DeepSeek thinking 模式下多轮工具调用不再 400（M5）
- 交互 seam（M6 工单 01）：`answers` 交互服务（回答者注册制 + 注册序遍历 + fail-closed）与 `interactive` 审批策略（`InteractiveApprovalPlugin`，inject answers）——ask 三态首次可由人作答；会话新增 `approval/requested` / `approval/decided` 审计事件（可选字段向后兼容，投影跳过）
- 交互审批 fail-closed 语义（ADR-0008）：无回答者、人未作答（EOF/中断）一律拒绝；不做"永久放行"
- prompt 注册表（M6 工单 02，agent 域 "prompts"）：插件经 `register` 贡献提示片段（随作用域摘除、按注册序动态组装）；yml `llm.systemPrompt` 为最前用户片段，全空落内置缺省——M7 技能指令段的挂载点
- `ask_user` 提问工具（M6 工单 03，tools 域）：模型发起的交互——参数最小 schema（question + 可选 options/multiSelect），执行本体经交互 seam 等人作答，回答即工具结果；走六段管线；无人应答 fail-closed 收敛为错误结果
- `RetryingAdapter` LLM 重试装饰器（M6 工单 04，llm 域）：对网络故障与 429/502/503/504 指数退避重试（默认 3 次）；协议与凭证错误（400/401）直通不重试；流式安全——已交付增量后不再重试。消除 M5 已知限制"无自动重试"
- 重复调用提醒插件 `RepeatReminderPlugin`（M6 工单 04，example 治理插件示范）：同一工具相同参数连续重复达阈值（默认 3/5/8）时在结果尾部附加逐级加码提醒（advisory，非 guard）；阈值可配置
- AgentRepl 升级为 HITL 演示（M6 工单 05）：写操作终端 y/n 逐次审批（console answerer + 审计桥，决定落会话）、模型 ask_user 提问（选项序号/自由文本）、`/new` 开新话题；`llm.retry` 段可配重试参数；prompt 注册表演示片段
- 会话 `tool/call` 事件持久化思考内容（reasoning 字段，可选向后兼容）：思考模型长工具链的历史请求天然完整（provider 要求回传），跨进程恢复可回放思考过程（M6 验收缺陷根治，BUG-20260913-03）
- Web 双面地基 API（M8 工单 01）：core `Context.snapshots()` 只读插件状态快照（M8 状态面数据源）+ session `Session.addListener` 事件订阅（M8 事件流推送源，注销器摘除）——纯新增向后兼容
- 对话面（M8 工单 04）：`POST /api/message` 装配全套 M5-M7 对话执行者（工具循环 + 重试 + prompt 注册表）异步执行——chunk 与工具调用/结果经 SSE 实时推送渲染为合一工具卡（状态徽标三色：运行中/成功/失败）；`POST /api/session/new` 开新会话；单飞串行（执行中再发 409）
- 会话事件 `run/error`（M8）：运行错误直推帧（不落会话历史），页面渲染 [错误] 卡
- Web 面接入 demo 装配（M8 工单 06）：agent-demo.yml `web` 行——一条命令同时具备 CLI 与浏览器双入口（会话目录单入口约定）
- HITL Web answerer（M8 工单 05）：`WebAnswerer` 实现交互 seam（M6）——待答审批经 SSE 推送为页面按钮卡片，点选后 `POST /api/answer` 完成；**SSE 断连/超时一律 fail-closed**（悬空请求自动拒绝，人不在环 = 不批准）；ADR-0008 验证：呈现位零改动机制核
- Web 单页重构为三区布局（M8）：会话侧栏（`/api/sessions` 列出 + `/api/session/switch` 切换 + 当前会话高亮 + ＋新话题）/ 对话面 / 状态面，样式对齐冻结原型（亮色 DSH token）；EmptyHero 新会话初始态；合一工具卡（状态徽标 + 可折叠结果 + 治理提醒独立标注）；审批 / 计划呈交 / 提问三张交互卡（事件委托，`approval/decided` 回放冻结）
- `AuditingAnswerer` 审计桥提升至 agent 模块（会话经 Supplier 延迟解析）：CLI 与 Web 装配共用——Web 装配经它把审批请求/决定落会话事件，驱动页面审批卡（M8 工单 05 接线修复）
- Web 双面骨架（M8 工单 02）：`WebPlugin`（Boot yml 一行，只绑 127.0.0.1，默认 18080）+ 静态单页 + `/api/status` 状态 JSON + `/api/events` SSE 会话事件流（存量回放 + 实时推送，虚拟线程执行器）
- 技能系统（M7 工单 01，agent 域 "skills" 服务）：SKILL.md 目录包与单文件 `<name>.md` 双形态，四根发现（`.duo/skills` → `.agents/skills` → `~/.duo/skills` → `~/.agents/skills`，同名高优先根胜），启动加载、清单片段进 prompt 注册表；`skill` 工具供模型按名加载指令全文（走六段管线）；yml 禁用配置
- prompt 注册表插件化（M7 工单 01）：`PromptPlugin` 发布 "prompts" 服务（config.systemPrompt 为最前用户指令片段）——技能清单、AGENTS.md 等装配级片段的注册点
- AGENTS.md 注入（M7 工单 02，agent 域）：`AgentsMdPlugin` 加载 `~/.duo/AGENTS.md`（用户全局）+ 项目根 AGENTS.md（.git 定根），64KB 预算超限截断，注册为 agents-md 片段进 prompt 注册表——项目约定对运行时 agent 自动可见
- 技能用户直调（M7 工单 03）：AgentRepl 输入 `/技能名 [任务]` 即注入该技能指令全文——点名的能力立即生效；未知名提示可用技能
- 计划模式（M7 工单 04，引导式）：`/plan [任务]` 进入（挂计划指导片段：先探索再设计、不做修改性操作）、`exit_plan_mode` 工具呈交计划、用户批准后执行 / 打回带反馈继续；状态存 `plan/mode` 会话事件（续接恢复）；复核无人应答 fail-closed 保持计划模式
- AgentRepl M7 装配（M7 工单 05）：内置演示技能 release-notes（.duo/skills，dogfood 形态）；三路触发与计划模式端到端可验收

### Fixed

- 已知限制清单对账：删除"Web 双面界面未开始"（M8 已交付失效）；补记多标签共用服务端当前会话的边界（与 BUG-20260914-01 同源）
- 切换会话后消息"丢失"（BUG-20260914-01）：`/api/session/switch` 换绑后现重建对话执行者——此前 agent 仍写旧会话，消息落错会话（页面看不到自己的消息、刷新"丢失"、切到别的会话反而可见）；`Session.events()` 改快照语义，流式追加期间的并发回放不再中断
- 计划呈交在 Web 无卡片可答（BUG-20260914-02）：`exit_plan_mode` 现渲染为计划呈交卡（批准/打回带反馈），回答口径对齐工具判定——此前呈交后永久挂起至超时；`ask_user` 结果同样按工具名冻结提问卡

### Changed

- `LlmConfig` 从 `dev.duo.harness.llm.internal` 移至 `dev.duo.harness.llm` 根包（平铺契约约定对齐：跨模块消费的配置 record 属公共契约）
- DSH 核心功能全景研究落盘（`docs/research/DSH/核心功能全景.md`，锚点 c291e796 = release 0.1.5）：agent 循环能力、内置工具目录、交互与 HITL 机制（审批 seam / ask-user / 权限预设 / plan 模式）、会话与上下文治理、扩展生态（skills / subagent / hooks / bundle / preset）、LLM 适配——含与 duo-harness 现状的事实映射表，供后续里程碑对齐参考

## 0.2.0（2026-09-11）

### Added

- 新模块 `duo-harness-llm`：provider 中立的 LLM 调用契约（流式 chunk 回调）+ OpenAI 兼容适配器（SSE 流式）；配置加载自 `~/.duo/config.yml`（`DUO_LLM_*` 环境变量可覆盖）——agent 能力第一块基石
- `DuoHome`（core）：用户级默认目录 `~/.duo` 约定（`DUO_HOME` 可重定向），会话/配置等运行时数据统一收在其下
- 新模块 `duo-harness-mcp`：经官方 MCP Java SDK 连接 MCP 服务器（stdio）——断连自动重连（指数退避 + 稳定窗口 + 预算耗尽）、远端工具自动同步进工具域（`mcp__<server>__<tool>` 命名，`list_changed` 自动重同步）；插件停止即断连并注销工具
- 审批策略服务：pre-execute 决策三态（allow / deny / ask）——治理插件或工具声明需审批，策略服务裁决；预设 `always-deny`（缺省，未配置即拒）与 `auto-approve`（白名单）；审批决策审计日志
- 输出契约：`ToolDefinition.output()` 声明结果 JSON Schema，违约转 error 结果点名原因；MCP 远端 `outputSchema` 同标准（双轨制，未声明宽松透传）
- guard 单调否决：`ToolsService.guard(registrant, check)`——审批之后、本体之前的动态拒绝，理由即拒、null 放行、拒绝无法翻回；随注册作用域销毁自动摘除
- demo 扩展 M2 段：一条命令演示 MCP 连接、远端工具调用真实文件、审批拒绝与 guard 拦截、拔连接后工具消失
- 文档站上线：`https://dengdz.github.io/duo-harness/`（VitePress 构建，push main 自动部署）；docs/ 即站点源目录，内部开发文档不上站（ADR-0005）

### Changed

- MCP Java SDK 0.10.0 → 0.18.1（传递依赖 mcp-core + mcp-json-jackson2，networknt json-schema-validator 2.0.0 随之引入）

## 0.1.0（2026-08-25）

### Added

- 引入 agent 工程规范体系：`AGENTS.md` 路由总纲（需求分流、显式命令、红线）
- 引入 9 个 `.agents/skills/` 技能：duo-workflow（含版本号规范）、duo-code-review、duo-tracker、duo-pre-push-checks、duo-release-workflow、duo-prose-standard、duo-project-structure、duo-trim-cot-leakage（含示例与检索模式集）、duo-doc-standards
- 引入领域文档 `docs/agents/`：`domain.md`（术语表）、`issue-tracker.md`（本地工单规范）
- 引入 `.gitignore`（构建产物、IDE、密钥环境、日志；`.scratch/` 随库入库）
- 引入 duo-research 技能与 `docs/research/` 研究资产：首份产出为 DSH 项目总览（架构、模块、数据流、技术栈、目录结构）
- 新增 DSH 插件化架构研究三件套（`docs/research/DSH/插件化架构/`）：Cordis 容器核心（Context/Fiber/Registry/Reflect/Events）、profile→bundle patch 层序合成、工具/MCP/页面三类插件的插拔机制、生命周期与依赖驱动启停、双面模块系统——为从零构建插件化框架提供架构参考
- 完成插件化框架方案 grilling：建立术语表 `CONTEXT.md`（13 个领域术语）与四份 ADR（自研插件容器内核、同步 API 与虚拟线程、Jackson 统一序列化与配置绑定、三支柱先行路线图）
- 发布 M1 spec（`.scratch/m1-plugin-kernel/spec.md`）：插件容器内核 + 示例插件的 25 条用户故事、全部实现决策（含配置行结构与六态状态机浓缩形状）、双接缝测试方案
- M1 spec 拆分为 7 张 tracer-bullet 工单（`.scratch/m1-plugin-kernel/issues/01~07`）：依赖拓扑 01→{02,03}→04→05→07、06 旁路可并行，每张含验收清单
- 工单 01 落地：duo-harness 首个代码模块 `duo-harness-core`（插件容器内核最小闭环）——Plugin/Context/Disposable 公共 API、config record 的 Jackson 绑定（失败即加载失败、点名插件与字段路径）、副作用栈逆序回滚与级联停子、销毁后拒绝注册；9 个接缝 B 用例全绿；docs 立起 index 导航与架构篇（模块划分）
- 审查修复（行为变更）：config 绑定启用严格模式——record 缺字段由 Jackson 默认补 null/0 改为绑定失败（错误前移）；声明了 config 类型却未提供配置由静默传 null 改为点名报错；父作用域并发销毁时已启动子实例不再泄漏；销毁后注册/加载的拒绝行为与 null 参数快速失败写入 JavaDoc 契约
- 工单 02 落地：事件总线五种分派模式（emit 隔离 / parallel 虚拟线程并发聚合 / serial·bail 顺序投票 / waterfall 洋葱管线——否决、参数改写、返回值包装）；监听器表全树共享、注册即作用域副作用随插件停止自动摘除；引入 SLF4J（api + simple test），清理与隔离错误改为可观察的 warn 日志；30 用例三连跑全绿
- 工单 03 落地：服务注册表与视图接口寻址——provide/Service 基类（构造即发布）、`ctx.as(视图)` 动态代理（方法名即服务名、惰性解析、类型校验）、`Plugin.inject()` 依赖声明（未声明读取点名拒绝）、依赖缺失挂起 + awaitStartup 真实阻塞语义、服务注销与提供方插件停止的级联传导（依赖方自动停止）；注册表 (服务名, 作用域) 二阶键 isolate 预留；44 用例三连跑全绿
- 工单 04 落地：依赖驱动生命周期——六态状态机（PENDING/LOADING/ACTIVE/FAILED/UNLOADING/DISPOSED）与 epoch 依赖指纹：依赖消失回 PENDING、服务回归自动重启、换实现自动重启；状态迁移经 `plugin/status` 事件广播、`PluginHandle.state()` 可查询。行为变更：启动失败不再阻断 plugin() 调用，错误统一经 handle（awaitStartup 重抛 / state=FAILED），对齐 DSH fiber 语义；54 用例三连跑全绿
- 工单 05 落地：配置驱动 boot——`Boot.from(yml)` 单文件引导（行结构 id/name/config/disabled，行序无加载语义），收尾审计点名（FAILED 带原始错误、PENDING 列缺失服务、类不可加载点名），任何失败整树回滚后抛带阶段标签的 `BootException`；`Context.hasService` 存在性查询；66 用例三连跑全绿——M1 核心链路（配置 → 插件树 → 服务/事件/生命周期）闭环
- 工单 06 落地：`duo-harness-tools` 模块——工具域骨架与三段执行管线：`ToolsPlugin` 挂树发布 "tools" 服务，`register(registrant, def)` 注册即注册方作用域副作用（插件停止自动注销），execute 走 `tools/pre-execute`（准入否决）→ `tools/execute`（around 本体）→ `tools/post-execute`（结果改写/转错误）瀑布管线；工具异常收敛为 error 结果不上抛；76 用例三连跑全绿
- 工单 07 落地（M1 收官）：`duo-harness-example` 模块——示例插件集（服务提供者/消费者对、工具插件、管线拦截者、disabled 行）+ demo 配置 + DemoMain；验收命令 `mvn -pl duo-harness-example -am package exec:java` 一条命令输出 34 条状态/事件叙述，覆盖配置驱动 boot、行序无关、视图寻址、三段管线否决与治理、拔服务级联停止、整树回滚；`Boot.from` 补 prepare 钩子（对齐 DSH）；docs 立起 01-入门章节；77 用例三连跑全绿
- duo-code-review 技能补目录分层审查维度：新类落位对照 duo-project-structure（按功能域分包、禁 `controller`/`service`/`util`/`impl` 大筐、根包不放类、新包同 diff 带 `package-info.java`），diff 涉及的包越过约 10 个类的拆包阈值时要求按功能边界拆子包；堆放类问题按 suggestion 报
- 终审复检（行为变更）：boot 配置解析新增配置行 id 重复检查（重复 id 即审计锚点失效，按解析错误点名拒绝）；ToolExecution 构造器 null 契约自足；已知限制唯一清单 `docs/limitations.md` 建立（七条 M1 限制归口）；duo-code-review 技能固化 ocr 分批/换模型/续跑执行策略；79 用例三连跑全绿
- 文档审计修复：ADR 约定收紧为落卷即冻结（`docs/agents/domain.md`：Status 只标记立卷时点，决策被取代时新开 ADR 注明取代关系，旧文件不动）；README 与 CONTEXT.md 能力枚举统一（工具 / MCP / 页面 / agent 循环），README 版本口径对齐 CHANGELOG 锚点（0.1.0-SNAPSHOT，未发布）
