# M2 理解关卡

> duo-comprehension 产物。讲义为主体（用户以后翻项目先翻它），错题本与凭证是闸门口径。
> 范围：0.1.0..HEAD（工单 01–05）。考试 ≥90 分放行 0.2.0 合并 main。

## 讲义

### 第 1 课：MCP 连接生命周期

- **解决什么问题**：MCP 服务器是独立子进程，会崩会断。把"连上→用着→断了→重连→放弃"做成无人值守的状态机，插件停止时干净收场。
- **核心概念**（术语表：MCP 客户端连接）：一条虚拟线程跑 `runLoop` 状态机（CONNECTING→CONNECTED→BACKOFF/GAVE_UP/STOPPED）。
- **实现要点**：
  - 装配 `McpClientSupport.connect:22-51`：配置归一化（`McpConnectionOptions.from:43`，serverName 正则 `:40`）→ serverName 占坑 `provide("mcp-connection/<name>")`（:27，重复配置装载即点名）→ `ctx.effect(supervisor::stop)`（:48，插件停止即断连）。
  - 首连放行时序 `runLoop:141-142`：`listener.onConnected`（工具同步）**之后**才 `firstAttempt.countDown()`——同步属于"连接就绪"的一部分（countDown 被挤掉曾致测试全挂起）。
  - 断连检测 = `awaitForExit()`（:147）：server 进程退出即返回，无心跳无轮询。
  - catch 的 `closed` 判据（:177-182）：dispose 的 interrupt 会让 awaitForExit 抛错落进 catch——没有判据会把正常停止误报为连接失败、把 STOPPED 倒回 BACKOFF。
  - 防僵尸（:189-190）：首连失败且 failOnStartupError → 直接 GAVE_UP，不拉注定失败的子进程。
  - `stop()` 次序（:288-291）：先 interrupt 循环线程，再关连接（关连接可能等对端）。
  - `ReconnectPolicy` 纯函数三件套：`failuresAfterDrop`（稳定窗口清零重计 1，:30）、`budgetExhausted`（:35）、`backoffDelayMs`（指数退避封顶，:40）。
- **ADR 取舍**：ADR-0006（官方 Java SDK 0.18.1，SDK 隔离在 internal）；ADR-0002（虚拟线程跑循环，锁用 ReentrantLock 不 pin）。

### 第 2 课：远端工具同步

- **解决什么问题**：远端工具变本地工具，且任何时刻不出现"半新半旧"或"真空"中间态（清单会变、会断连）。
- **核心概念**（术语表：工具同步）：两阶段换新——阶段一拉取+转换+命名冲突校验（不动注册表，失败旧代原样可用）；阶段二注销旧代、注册新代（失败按快照 best-effort 恢复旧代）。
- **实现要点**：
  - 两来源一把锁：`sync`（连接建立，:58）/ `onToolsChanged`（list_changed 通知，:76）经 `syncLock`（ReentrantLock）串行。
  - `apply`（:128-149）：旧代定义快照 → 注销旧代 → 注册新代 → 失败 `restoreOldGen`（:151）。先注销后注册是 `ToolsService.register` 拒绝重名的契约所迫——严格原子不可得，快照恢复是务实解。
  - `activeClient = client` 在新代全部就位后（:146）。
  - 通知三道闸（:76-113）：断连期间抵达丢弃（activeClient==null）→ 名字集合未变跳过（server 每次 addTool 都发通知，冗余重同步会制造工具短暂消失窗口）→ 作用域已销毁静默 / 命名冲突 warn 点名（与 sync 抛错语义一致）。
  - `convert`（:202）：outputSchema 非空转入本地 output()（双轨制声明轨）；execute 映射 **isError 先于 structuredContent**（错误形态可能带结构化载荷，反了会把失败当成功——review 真 bug）；structuredContent 优先、text 回退。
  - `unregisterAll`（:115）：预算耗尽撤全部工具——回不来的连接，下线比挂着诚实。
- **ADR 取舍**：ADR-0006（SDK 0.18.1，Tool 才有 outputSchema 字段）；命名清洗冲突的校验前置到阶段一（error-forwarding 惯例）。

### 第 3 课：审批策略（ask 三态）

- **解决什么问题**：审批语义下"谁声明要问"与"谁裁决"必须解耦——策略可插拔而不牵动治理代码。
- **核心概念**（术语表：ask、审批策略）：pre-execute 决策三态 allow/deny/ask。声明者（`ToolDefinition.requiresApproval()` 或监听器 `requestApproval()`）只置位；裁决者（审批策略插件的解析监听器）`resolveApproval` 写回；工具域只消费结果。
- **实现要点**：
  - 载荷即协议：`ToolExecution` 三标志（denyReason / approvalRequested / approvalDecision，:19-23），deny 与 ask 互斥（deny 占先）。
  - **协作走事件总线不走服务读取**：服务读取撞 inject 纪律（拒读未声明服务），声明 inject 则工具域硬依赖审批插件（缺它永久 PENDING）。监听器表全树共享无需 inject——内核既定的可选协作机制。**本课最重要决策**。
  - `gateFor`（ApprovalPlugin:57-66）：先 `next.invoke` 再裁决（around）→ 与注册次序无关，看到的是内层意愿落定后的载荷；内层否决或无人声明 ask 不介入——**策略只裁决被声明的 ask**（review 纠正：不是全局闸门）。
  - 一段 b（ToolsServiceImpl:110-126）：决策缺失即"未配置即拒"（来源 none，安全默认）；审计日志打结果+来源+工具名；拒绝的错误结果含策略来源。
  - 策略：AlwaysDenyPolicy（缺省）/ AutoApprovePolicy（白名单精确匹配）；config.policy 省略即 always-deny，未知策略装载即点名。
- **ADR 取舍**：可变载荷即协议（ToolExecution 贯穿三段，任何一段可读全量治理状态）。

### 第 4 课：输出契约 + guard 单调否决

- **解决什么问题**：事后防线×2——结果不符合承诺的结构（契约）；审批后本体前的不可协商动态拒绝（guard）。均不侵入工具本体。
- **核心概念**（术语表：输出契约、guard）：双轨制——`output()` default null 宽松透传，声明即校验；guard 理由即拒、null 放行、无"允许"结果。
- **实现要点**：
  - 契约校验在二段 b（ToolsServiceImpl:147-153）：`!resultIsError` 才校验；违约 `markError` 与工具异常同一出口（post-execute 照常可见）；违约消息点名（networknt Error 拼接）。
  - networknt 2.0.0（偏离票据 1.5.0）：mcp-json-jackson2:0.18.1 的 compile 硬依赖，1.5.0 缺 Dialects 类（Maven 最近优先撞旧版）；API 换 SchemaRegistry/SpecificationVersion/Error。
  - guard 注册（:80-87）：`registrant.effect` 挂注册方作用域（插件停止自动摘除）+ CopyOnWriteArrayList。
  - 一段 c（:127-135）：顺序遍历、首个非 null 直接 return——**单调性由结构保证**（后续 guard/本体/post 均不执行），`guardDenialCannotBeFlippedBack` 用 pre+post 监听器验证翻不回。
  - 时机：一段 waterfall → 审批 → guard → 本体（审批拒绝时 guard 计数 0，demo 第三幕时序证据）。
  - 生命周期局部语义（偏离票据"作用域局部生效"）：内核无调用方作用域概念（execute 不带作用域、事件表全树共享）——落地为随注册作用域销毁摘除 + 全局生效，limitations #5 记录。
- **ADR 取舍**：default 方法（ToolDefinition.output/requiresApproval）让 M1 既有实现零改动；诚实标注偏离而非硬凑字面。

### 第 5 课：M2 全景——六段管线与端到端 Demo

- **解决什么问题**：把 01–04 的机制拼成一条可观测、可验收的完整执行线。
- **核心概念**：execute 六段时序——一段 waterfall（准入/ask 声明）→ 一段 b 审批 → 一段 c guard → 二段本体 → 二段 b 输出契约 → 三段结果治理。每段独立筛选器，谁先拒绝后面全部不执行。
- **实现要点**：
  - 时序可观测：demo 第三幕以 guard 计数 0 证明"审批先于 guard"（不是文档声明是行为）。
  - `WriteProtectorPlugin`：治理插件示范——ask 声明 + guard 两种介入，工具本体零改动（治理是管线的事不是工具的事）。
  - `MiniFileSystemServer`：自包含迷你 server（真实 stdio + 真实文件 IO，零外部依赖），`resolve()` 越界即拒。
  - `subprocessClasspath` 双 fallback（:212-230）：java.class.path（IDEA/裸 java）→ ClassRealm URL 枚举（exec:java）。
  - 契约双道防线：SDK server 侧校验（防线一）+ 本地 validateOutput（防线二）——测试判行为不判实现位置。
  - 拔除即消失：插件 dispose → 断连 → 工具注销（注册即 effect 闭环）——无需显式清理代码。
- **ADR 取舍**：自写迷你 server 换取一条命令自包含（不赌 node/网络）。

## 错题本（题 → 当时错选 → 正解一句话）

| # | 考点（课） | 当时错选 | 正解一句话 |
|---|---|---|---|
| 1 | catch 的 closed 判据（1） | 不知道 | catch 里 closed 判据区分"自己人打断"与"真故障"，防 STOPPED 倒回 BACKOFF 与假日志 |
| 2 | 失败计数推演（1） | C | 稳定窗口清零只看断连时刻存活时长；catch 累加时 uptime=0 一律 +1 |
| 3 | failFast 防僵尸（1） | D | 失败路径也 countDown 放行（不会死锁）；防僵尸判据让 loopThread GAVE_UP 退出 |
| 4 | 名字集合比对（2） | 不知道 | 防冗余通知触发完整换新——换新中间的工具下线窗口会造成偶发调用失败 |
| 5 | 未配置即拒（3） | 不知道 | decision==null 时管线兜底 deny 来源 none——"要审批但没人批"绝不等于放行 |
| 6 | 异常与契约出口（4） | A | 本体异常先 markError 收敛，!resultIsError 判据跳过契约校验——异常与违约永不同时出现 |
| 7 | 失败计数推演（1，重考） | B | **成功连接即清零**——账本属于当前连接，一断一立账重开 |
| 8 | 契约出口正常路径（4，重考） | B | 双轨制切换条件是"是否声明契约"：声明了就严格校验，合法 JSON ≠ 符合 schema |

## 凭证

- 日期：2026-09-11
- 最终得分：**15/16 = 93.75 分（百分制折算）**
- 结果：**通过**（≥90，M2 理解关卡放行）
- 过程：5 课源码走读 → 16 题统考（10 对）→ 第 1 课重讲 + 重考（3 题对 2）→ 错题考点二轮重考（4 题对 3）
- 弱项备忘：失败计数推演（连续两轮错后过关）——复习锚点：`ConnectionSupervisor:117` 成功清零 / `:159` 断连按存活判 / catch `:190` uptime=0 累加
