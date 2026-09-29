# M29 三章大纲（outline.md）

> 状态：已确认（2026-09-29，工单 01 硬关卡通过，02/03/04 解锁）。每节三行口径：**讲什么**（机制点）/ **拿什么讲**（示例锚点，全部活体）/ **引什么**（既有文档互链）。三章共性：篇幅各 300-600 行；风格对齐既有文档（`> 状态：` 引言块、里程碑+ADR 括注、无 frontmatter、示例锚定真实代码与装配文件）；与既有四章分工——01-入门讲跑起来、02-指南讲操作路径、04-架构讲设计、05-参考讲清单，**03-高级讲进阶能力怎么写、怎么运转**，互链不重复。

---

## 第一章 技能编写指南

**章定位**：不翻源码就能写出合规技能的插件开发者。底座 = 《技能写作规范》教程化 + 机制侧补齐。

### 1.1 技能是什么
- 讲什么：SKILL.md = 模型可加载的指令包；「发现 → 名录进系统提示 → 按名加载全文」全链一句话；与子代理技能名录（ADR-0017 opt-in）的边界——**该裁定尚未实现**，如实写 limitations 挂账现状
- 拿什么讲：`.agents/skills/` 下 16 个 duo-* 实例（SKILL.md + references/experience.md 形态）
- 引什么：技能写作规范、ADR-0027、工具目录 §skill 工具

### 1.2 放在哪：发现根与优先级
- 讲什么：四发现根降序（项目 `.duo/skills` → 项目 `.agents/skills` → `~/.duo/skills` → `~/.agents/skills`）；项目根以 `.git` 向上定位、找不到即 cwd；同名先到先得；禁用表整名跳过；目录不存在静默跳过；双形态——目录包 `<名>/SKILL.md` 与单文件 `<名>.md`
- 拿什么讲：duo-* 技能即「项目 .agents/skills」活体；单文件形态用内置技能对照
- 引什么：术语表技能域（发现根词条）、01-入门 REPL 技能段

### 1.3 怎么写：SKILL.md 解析与五支柱教程化
- 讲什么：frontmatter 只认 `name`/`description`（name 缺省取目录/文件名主干；空名/空白正文非法跳过）；正文即指令全文。五支柱逐一「怎么做到」教程化：description 三规则怎么落、完成判据怎么立、检查点怎么阻塞、领先词怎么选、否定收敛怎么措辞——**规范是标准，本节是操作路径**，互链不照搬
- 拿什么讲：duo-workflow 等 SKILL.md frontmatter 实例；规范 15 机制覆盖表；duo-skill-lint 11 项核对作自检清单引用
- 引什么：技能写作规范（五支柱全文）、ADR-0027（调用权分流）、术语表技能写作域 8 词条

### 1.4 怎么被用：两条加载路与治理语义
- 讲什么：模型侧 skill 工具——入参仅 `name` 一个必填串，指令以工具结果进上下文，未知名报错点名可用技能；用户侧斜杠命令直调——`/技能名` 命中即返回 content 可携带输入。审批语义：工具缺省 `requiresApproval=false` 不弹审批卡，但六段管线不豁免（guard/输出契约/超时照常）
- 拿什么讲：AGENTS.md 显式命令表（/grill-me 等直调实例）；demo 装配下模型自主调 skill 的会话
- 引什么：工具目录 §skill、02-指南（Web 斜杠前置命令）、ADR-0012

### 1.5 怎么改怎么生效：热加载
- 讲什么：WatchService 监视四根**及其父目录**（捕捉根首次创建）；2s 心跳窗口 + 200ms 去抖合并；清单 digest 有变才重发名录、只重发一次（新增技能文件不重启即生效，最迟一个心跳窗口）；watch 不可用降级启动扫描 + 警告；已知残留——`~/.agents` 整链新建不感知（limitations 在册）
- 拿什么讲：会话中新增技能文件实测生效演示
- 引什么：ADR-0025、limitations 热加载条目、术语表（技能热加载词条）

---

## 第二章 MCP 深入

**章定位**：连接问题可自查的部署者与集成维护者。散落 7+ 处素材聚合升格——02-指南讲「怎么接」，本章讲「怎么运转」。

### 2.1 MCP 在 duo 中的位置与隔离边界
- 讲什么：连接即接入、远端工具自动进目录；官方 Java SDK 隔离在 internal 包（SDK 类型不外泄，契约包只出 McpClientPlugin/McpToolNames）；「管线第一个真实客户」的设计叙事
- 拿什么讲：mcp 模块契约/internal 两包结构
- 引什么：ADR-0006、设计主线、模块划分 §mcp 包边界

### 2.2 接一个服务器：config 面全表
- 讲什么：yml 行全字段——serverName/command/args/requestTimeoutMs/reconnect（initialDelayMs/maxDelayMs/maxAttempts）；stdio 子进程形态与拉起时机；编程形态挂载对照
- 拿什么讲：agent-demo.yml mcp 行、demo-m2.yml + MiniFileSystemServer（真实 stdio 协议活体）
- 引什么：02-指南 §第四步（操作互链）、插件配置参考 §编程形态

### 2.3 连接生命周期
- 讲什么：首连同步语义；断连与指数退避重连（initial/max/maxAttempts 三参行为）；预算耗尽放弃；两阶段换新——旧连接排水与新连接就位的切换语义
- 拿什么讲：ConnectionSupervisor / ReconnectPolicy 源码实测；agentrepl 日志里的重连现场
- 引什么：模块划分 §关键语义表、limitations M2（SDK 版本耦合、子进程 classpath 探测限制）

### 2.4 工具同步与命名
- 讲什么：tools/list → 公开名 `mcp__<server>__<名>__<8hex>`（哈希防同名前缀互伤）；`McpToolNames.matches` 去哈希精确匹配原语——安全策略按名匹配的正路（前缀匹配已被实证误伤）；list_changed 增量重同步；停止期同步拒绝走类型化异常（`ScopeDestroyedException`），消费方按类型判定而非消息文案
- 拿什么讲：mcpfs WriteProtectorPlugin（`mcp__files__` 前缀治理活体）、0.24.0 写保护插件回归精确匹配的变更注记
- 引什么：ADR-0026（命名裁定）、工具目录 §MCP 远端机制

### 2.5 状态面、排障与治理范例
- 讲什么：connectorStatus 状态板（多连接行聚合、onGaveUp 注销句柄随装配销毁）；排障三分——服务未挂载（静默 null）/ 服务在场解析失败（warn 点名）/ 重复发布（预探测按复用）；远端工具零改动施治理的两板斧——pre-execute 声明 ask + guard 单调拒绝，post-execute 结果改写（提醒类 advisory 不改错误形态）
- 拿什么讲：Web 状态面连接器区截图位；mcpfs 三插件 walkthrough（MiniFileSystemServer + WriteProtectorPlugin + RepeatReminderPlugin 全程可复跑）
- 引什么：01-入门 M2 演示段、工具目录 §MCP、扩展点清单 §服务名全表

---

## 第三章 多插件协同

**章定位**：照着做就能挂上第一个插件的进阶用户。主线 = duo-harness-stats「从零写一个插件」端到端 + 组装协同。

### 3.1 插件模型速览
- 讲什么：`Plugin<T>` 骨架——configType 严格绑定（配错即启动 FAILED 点名，不静默纠正）、apply = 装配点、可逆副作用与作用域回滚（停止即注销）、六态 snapshots 可观测
- 拿什么讲：ToolStatsPlugin 骨架（三类一模块的最小完整形态）、greeting Config
- 引什么：ADR-0001、设计主线 §主线一、插件配置参考 §出错怎么看

### 3.2 从零写一个插件：tool-stats 全程
- 讲什么：四扩展点逐个接线——`inject()` 声明 `{tools, commands}`（缺服务不启动、错误前移）、`ctx.on(POST_EXECUTE)` 事件监听（只观察不改写）、`tools.register` 注册查询工具（零参 schema 与实现诚实一致）、`commands.register` 注册 `/toolstats`（ANY + busySafe 执行中可敲）；接入仅两行（example 依赖一行 + yml 一行），零内核改动、不装即零感知；依赖方向 stats → agent/tools → core、无任何反向
- 拿什么讲：duo-harness-stats 三类逐段对应（ToolStatsPlugin / ToolStats / StatsViews——视图接口方法名即服务名逐字的 ADR-0019 纪律）
- 引什么：插件扩展点清单、工具目录 §工具统计插件、BootYmlTest（yml 装载全链路用例）

### 3.3 服务协同：provide / inject / optionalInject
- 讲什么：provide 发布 + camelCase 服务名惯例；inject 硬依赖（缺即 PENDING 挂起）vs optionalInject 可选降级（CLI 纯对话装配先例）；跨插件消费经 `ctx.as(视图接口)`；双向自动重载（epoch 指纹翻动触发 recheck）与撞名禁忌
- 拿什么讲：greeting 服务对（提供方/消费方/契约接口三件套）；approval 三策略三选一互斥挂载（agent-demo.yml 注释）
- 引什么：ADR-0019 决策 7-10、扩展点清单 §服务面、经验档「新服务发布先核对命名惯例」

### 3.4 事件与钩子协同
- 讲什么：事件面五式（emit/parallel/serial/waterfall/bail）与可听/不可听事实边界；tools 三段管线时序——审批先行、guard 次之、输出契约兜底（审批拒绝的调用到不了 guard，以计数为零可证）；hooks.json 外部进程形态（matcher/裁定/fail-open）与插件监听分工；协同边界——插件听不到会话事件（limitations M27 在册）
- 拿什么讲：tools 子包 ToolGuardPlugin（pre 否决 + post 改写）、contract 三幕演示（时序证明活体）、mcpfs RepeatReminderPlugin（advisory 改写）
- 引什么：ADR-0018/0019、插件配置参考 §hooks.json、limitations M27

### 3.5 装配与行序
- 讲什么：yml 顶层 plugins 行反射装载、行序即装载序；web 行先于 cli 行的双启动校验 fail-fast（cli 行 apply 即阻塞 REPL，web 后置即静默缺席——Boot 预检 + web 装载期二道闸）；mcp 行经 optionalInject 自动重载自愈故为建议序；agent-demo.yml 全量逐行导览
- 拿什么讲：agent-demo.yml 全量（含三选一审批段、mcpfs 指路注释、tool-stats 接入行）、DuoMain 预检
- 引什么：ADR-0029（M27 交付·行序契约）、插件配置参考逐行表、启动器词条

---

## 交付口径（三章一致）

- 每章成章即四轴内审查（spec 轴对大纲、文档轴对风格与锚点）+ 用户验收后才动下一章
- 示例锚点全部活体：写章时对源码关键行再抽查（大纲机制点来自两轮探索+本大纲前实测，成文时以源码为准绳）
- 章间互链与 05-参考分工：清单类内容（工具目录/扩展点清单/配置参考）只引不抄
