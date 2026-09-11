# Changelog

本文件记录 duo-harness 的用户可见变更。版本号规则见 `.agents/skills/duo-workflow/references/版本号.md`。

## 未发布

### Added

- LLM 适配器（`duo-harness-llm` 新模块）：provider 中立流式调用契约 + OpenAI 兼容适配器——`baseUrl/apiKey/model` 配置化，DeepSeek/通义/Kimi/vLLM 等兼容 provider 开箱即用
- 聊天演示 `ChatReplMain`：REPL 交互（`你> `/`AI> `、`/exit` 退出）、流式打印、错误原样呈现
- `LlmConfig` 支持 `llm.systemPrompt` 可选配置（缺省内置指令；组装注册表属 M6）

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
