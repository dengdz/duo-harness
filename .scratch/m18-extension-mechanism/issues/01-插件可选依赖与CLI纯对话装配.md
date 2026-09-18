# 01: 插件可选依赖 + CLI 纯对话装配

**What to build:** 部署者在 boot yml 里不装 fs 插件行，CLI 照常启动、能聊天——`/permission` 这类依赖 workspace 的命令降级为"未挂载"提示而非整树起不来（backlog 原始用例）。插件开发者获得 `optionalInject()` 声明："就绪则用、缺失不拦"，服务出现/消失自动重载、升级↔降级双向对称。

**Blocked by:** None (can start immediately)

**Status:** done（2026-09-18 用户真机验收通过：纯对话装配聊天照常、/permission 降级未挂载、对照组档位原样）

语义权威：ADR-0019 决策 7/8/9；spec 见 `.scratch/m18-extension-mechanism/spec.md`（可选依赖小节）。

- [x] `Plugin` 新 default 方法 `optionalInject(): Set<String>`（空集缺省，与 inject 对称）；`as()` 读取许可放宽为 `inject() ∪ optionalInject()`——未声明名字读取仍点名报错，实例仍只经声明名字取
- [x] 声明可选依赖的服务缺失时插件照常 ACTIVE（不 PENDING）；`apply` 内以 `hasService()` 判存并按在场性接线（降级桩形态）
- [x] epoch 语义：依赖指纹缺失项跳过（不置 null）、在场项照常参与；可选服务 provide/unprovide 经既有 recheck 自动重载，升级↔降级双向对称
- [x] 不新增读取 API：`hasService()` 判存 + `as()` 惰性视图；DSH 式 `ctx.get(name)` 使用点探测不引入
- [x] yml 路径 BootLoader 审计对仅可选缺失的插件不报"PENDING 等待"（缺失清单逻辑抽取，供 02 复用）
- [x] 端到端验收：boot yml 去掉 fs 插件行 → CLI 照常聊天，`/permission` 提示"未挂载"（点名 workspace 服务）；装配级自动断言 + duo-acceptance 真机验收件
- [x] 测试走 Boot 双接缝（LifecycleTest/BootTest 叙事：接缝 A = yml 全链路 / 接缝 B = 编程 API），只断言外部行为（插件状态、视图可用性、审计文案），不测内部实现

## Comments

**实现摘要（2026-09-18）**

- core 五处：`Plugin.optionalInject()`（JavaDoc 立语义"就绪则用、缺失不拦"）；`PluginInstance` 双 TreeSet（硬/可选）+ `readPermission` 并集传 scope + `computeEpochLocked` 可选缺失跳过（在场照常拼串）+ `dependsOn()` 传导口径 + `missingHardDependencies()` 诊断点名；`ContextImpl.plugin()` 交叠声明加载时点名拒绝（"缺失是否阻塞启动"二义，错误前移）；`Context.as()` JavaDoc 与许可报错文案同步（"未在依赖声明中（inject/optionalInject）"）；`PluginRegistry` 传导过滤改 `dependsOn`。
- CLI：`inject()` 摘除 workspace、`optionalInject()` 声明之；`apply` 以 `hasService()` 判存接线（缺席即 null）；`/permission` 前置降级分支"workspace 服务未挂载（未装配 fs 工具插件），/permission 不可用。"

**裁定留痕**

- "缺失清单逻辑抽取"以**口径统一**而非物理搬移落地：BootLoader 侧保持对 Plugin 反射实例的 `missingServices`（yml 审计输入是插件描述），实例侧新增 `PluginInstance.missingHardDependencies()`（输入是运行期实例，02 的 awaitStartup 超时点名消费）——两侧文案同源（"缺失服务: …"）。
- 交叠声明选择拒绝而非"硬依赖优先"：静默取硬语义会掩盖声明笔误。

**测试证据**

- core 84 例全绿：新增 `OptionalInjectTest` 7 例（接缝 B 五：缺失即激活 / 出现升级重载 / 消失降级重载 / 未声明读取点名 FAILED / 交叠拒绝；接缝 A 两：仅可选缺失 boot 成功 / 审计点名硬缺失行而不累探针行）。`ServicesTest` 一处旧文案断言同步。
- cli 19 例全绿：新增 `CliOptionalWorkspaceTest`（脚本"你好 → /permission → /exit"，断言聊天照常 + 降级提示；无 fs/审批插件挂载）+ `CliPluginAssemblyTest` 增纯对话装配用例（新资源 cli-pure-conversation-test.yml，无 fs 行 boot 成功、CliPlugin ACTIVE、ask_user 在册）。
- 全量 `./mvnw package` 十模块 BUILD SUCCESS（40 web + 19 cli + 12 example + 84 core 等）。

**验收件（待手动验证）**

演示命令与交互脚本（隔离 DUO_HOME，对照组带 fs 行）；预期对照表：

| # | 动作 | 应出现 |
|---|---|---|
| A1 | 纯对话 yml 启动 | 欢迎行 `会话 <id>（工具循环上下文）。/exit 退出，/new 开新话题。`，无任何 PENDING/启动失败报错 |
| A2 | 输入 `你好` | 模型正常回复（内容不限）——纯对话装配聊天可用 |
| A3 | 输入 `/permission` | `workspace 服务未挂载（未装配 fs 工具插件），/permission 不可用。`（降级，非崩溃非 未知命令） |
| A4 | 输入 `/exit` | `=== 对话结束 ===`，进程正常退出、无栈痕迹 |
| B1 | 对照组（带 fs-tools 行）启动后 `/permission` | `当前预设: read-only（可选: read-only / workspace-write / danger-full-access）`——有 workspace 时功能原样 |

测试路径叙述行（套件级）：`OptionalInjectTest —— 可选依赖：缺失即激活、出现/消失双向重载、许可合一、交叠拒绝、boot 审计不误报（7 用例）`；`CliOptionalWorkspaceTest —— 纯对话装配：无 workspace 时聊天照常、/permission 降级未挂载（1 用例）`；`CliPluginAssemblyTest —— CLI 装配：HITL 交互工具随装配注册、纯对话装配（无 fs 行）照常启动（2 用例）`。
