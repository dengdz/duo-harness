# M11 Spec：CLI 插件化——呈现位对称与通用启动器

> ADR-0011（2026-09-14 grill 裁定，五项全 A）为直接依据。M10（0.5.0）已发布；本里程碑把最后一个非插件呈现位（CLI）改造为与 WebPlugin 对称的 Boot 插件，并补齐通用启动器。目标版本 **0.6.0**（分支已切）。
>
> **过程记录**：grill 五问全按推荐裁定（模块归属 / 启动器与 AgentReplMain 命运 / /exit 语义 / 回答者路由 / ChatReplMain 去留）；理解关卡欠账（M3-M10）按用户指示继续挂账，/implement 开工前须清（流程闸门）。

## Problem Statement

CLI 是全仓唯一不是插件的呈现位：装配、呈现、启动器三职责焊死在 example 模块的入口程序 `AgentReplMain` 里。后果：① CLI 不可配置、不可关闭——不想要终端入口的部署也得带着它，与"不挂载零残留"的插件原则相悖；② example（演示定位）承担正式运行时职责，模块定位漂移；③ 纯 Web 部署形态从未成立——JVM 存活靠 CLI 的阻塞读；④ CLI 与 Web 的装配代码双份维护，交互工具注册的会话绑定语义含糊。

## Solution

新增模块 `duo-harness-cli`：`CliPlugin`（Boot 插件，对称 `WebPlugin`）——`apply()` 装配完整执行链（LLM 配置 → 重试 adapter → 会话续接/新建【独占锁】→ 共享装配器出 agent + 治理 → ConsoleAnswerer + 审计桥 + 交互工具查重注册），并以虚拟线程启动 REPL 循环（交互沿 `AgentReplMain` 既有形态：/new、/exit、/技能名直调、审批 y/n、ask 选项、思考模型）。`/exit` 或 EOF = CLI **idle**：循环退出、会话锁释放、回答者摘除，插件保持挂载、整树与 Web 不受影响。

通用启动器 `DuoMain`（example 模块）：`Boot.from(yml)` + 非守护等待 + shutdown hook 级联 `dispose()`——只负责把插件树跑起来并保活，不含业务装配；Ctrl-C 经 hook 释放全部会话锁。`AgentReplMain` 瘦身为兼容壳（main 委托 DuoMain；`run(in, out)` 保留为测试入口）。

WebPlugin 切换到共享装配器（装配逻辑单点化），行为零回归。

## User Stories

1. As a 部署者, I want yml 删除 cli 行得到纯 Web 部署, so that 无终端依赖的环境只跑浏览器入口
2. As a 部署者, I want yml 只保留 cli 行得到纯 CLI 部署, so that 无浏览器场景照常用全部 agent 能力
3. As a 部署者, I want cli 行标 `disabled: true` 保留配置地关闭, so that 临时停用不必删配置
4. As a CLI 使用者, I want REPL 交互与现状完全一致（/new、/exit、/技能名直调、审批 y/n、ask 选项序号/自由输入）, so that 升级零学习成本
5. As a CLI 使用者, I want `/exit` 只结束终端入口（释放会话锁、插件转 idle）, so that 同进程的 Web 面不被误杀
6. As a CLI 使用者, I want 启动续接的最新会话被占用时得到明确提示并改开新会话, so that 不静默分脑（M10-03 语义延续）
7. As a CLI 使用者, I want `/new` 后旧会话锁立即释放, so that 他处可随时续接旧会话
8. As a CLI 使用者, I want LLM 未配置时得到明确的配置指引提示, so that 知道如何修（现状沿袭）
9. As a Web 使用者, I want Web 面行为零变化, so that 升级无感
10. As a 纯 CLI 部署的使用者, I want ask_user 与计划呈交工具照常可用, so that HITL 能力不因部署形态缺失
11. As a 插件开发者, I want CliPlugin 与 WebPlugin 结构对称（apply/stop/配置/回答者）, so that 新增呈现位有清晰先例可抄
12. As a 框架维护者, I want 两个呈现位的执行链装配单点化（共享装配器）, so that 双份装配代码不再漂移
13. As a 框架维护者, I want 通用启动器不含任何业务装配, so that 启动器稳定且可长期复用
14. As a 框架维护者, I want Ctrl-C / 停树经 shutdown hook 级联 dispose, so that 全部会话锁与资源确定性释放
15. As a 测试者, I want REPL 循环可注入输入/输出流做脚本化测试, so that 交互行为自动化验证
16. As a 测试者, I want 纯 CLI yml 装配可 Boot 测试, so that 交互工具随装配在册有回归保障
17. As a 框架维护者, I want WebPlugin 切共享装配器后既有测试全绿, so that 重构零回归有证据
18. As a CLI 使用者, I want Ctrl-C 后会话锁被 shutdown hook 释放, so that 他处可立即续接会话（比现状"靠 OS 杀进程"更干净）

## Implementation Decisions

- **新模块 `duo-harness-cli`**：依赖 core/tools/session/agent/llm；被 `duo-harness-example` 依赖（DuoMain 与测试用）。包 `dev.duo.harness.cli`。
- **CliPlugin（Boot 插件）**：默认构造走 System.in/out；注入构造收 `BufferedReader in, PrintStream out, Path sessionsDir`（测试 seam，沿 `AgentReplMain.run(in, out)` 先例）。`apply()`：LlmConfig 装载 → `RetryingAdapter(OpenAiCompatAdapter)` → 会话续接/新建（独占锁；占用 = 插件 FAILED 点名会话，沿 WebPlugin 语义）→ 共享装配器出 ChatAgent + 治理 → 注册 ConsoleAnswerer + 审计桥（生产同款：只注册审计装饰器）→ 交互工具查重注册 → 虚拟线程跑 REPL 循环。`stop()`：置停止标志 + 中断循环线程 + 尽力关闭注入输入流（System.in 不可关，进程退出由守护线程语义兜底）+ 关会话。
- **REPL 循环**：交互沿 `AgentReplMain` 既有形态全量搬迁（/new、/exit、/技能名直调、审批 y/n、ask 选项/自由输入、思考模型叙述、治理提醒呈现）。`/exit` 或 EOF = idle：循环退出 → 关会话（释放锁）→ 摘回答者 → 插件保持挂载。`/new`：新建会话 + 关旧会话（锁释放）。
- **共享装配器（agent 模块）**：把 WebPlugin 与 CliPlugin 重复的执行链装配提取为单点（adapter+重试、ContextGovernance、ChatAgent 构建、交互工具查重注册）——两个插件各调一次，参数化会话与回答者。
- **ConsoleAnswerer 上移**：example 的 `agentrepl.ConsoleAnswerer` 移入 `duo-harness-cli`（CLI 专属呈现件）；example 内引用同步调整。
- **DuoMain（example 模块）**：`Boot.from(yml)` + CountDownLatch 非守护等待 + `Runtime shutdown hook` 调 `root.dispose()`。`AgentReplMain` 瘦身：LlmConfig 装载、工具注册、会话管理、REPL 全部移出，main 委托 DuoMain；`run(in, out)` 保留注入形态供测试。
- **回答者路由**：沿注册序（yml 行序即路由，首个非空胜出）——双开时审批/提问落在行序靠前的呈现位；文档写明；广播等待先答列为演进方向（ADR-0011）。
- **会话语义沿 M10-03**：续接撞锁 → 明确提示；CLI 侧改开新会话继续（既定行为）；不共享、不协调。
- **Boot `disabled: true`** 为呈现位开关正式机制（保留行、不加载——内核既有能力，本里程碑起作为呈现位开关文档化）。

## Testing Decisions

- **只测外部行为**：REPL 的输入→输出与会话事件、装配产物（工具清单）、锁的占用/释放；页面与终端的视觉呈现不自动化。
- **测试 seam（一个新 seam：CliPlugin 注入式 REPL；其余复用）**：
  - **CliPlugin REPL seam（新）**：注入 BufferedReader/PrintStream（脚本输入）+ mock LlmAdapter + TempDir 会话目录——断言：会话事件序列（user/message、assistant/message）、/new 后事件落新会话、/exit 与 EOF 后会话文件可重新加锁（idle 语义）、审批 y/n 经 ConsoleAnswerer 呈现、/技能名直调注入指令。
  - **装配 seam（对齐 WebPluginAssemblyTest 先例）**：纯 CLI yml（tools + prompts + answers + cli）Boot 后断言 ask_user 与计划呈交工具在册；cli + web 双开 yml 断言双注册查重生效。
  - **占用 seam（沿 M10-03 registry 语义）**：会话被同进程实例持有时 CliPlugin 启动 FAILED 并点名会话。
  - **回归**：WebPluginAssemblyTest / WebFaceTest / AgentReplMainTest（兼容壳与 run(in,out)）全绿——共享装配器切换零回归的证据。
- **测试环境注意**：CliPlugin.apply 与装配测试经 LlmConfig 装载读取 `~/.duo/config.yml`（沿 WebPluginAssemblyTest / AgentReplMainTest 先例，非密闭测试已在注释披露）。
- **测试环境坑**：跑全量前先杀 18080 监听进程（demo 装配 Web 占端口）。

## Out of Scope

- 回答者广播路由（双开时"两边都能答"）——注册序维持，演进方向（ADR-0011）
- 会话共享 / 跨入口写协调——独占语义维持（M10-03）
- CLI 富终端交互（TUI 框架、语法高亮输入行等）
- `ChatReplMain` 改造或删除（保留为 M4 独立演示）
- 侧栏被占会话标注（backlog 已记，随"会话切换无刷新 + 分页"期实现）
- Web 面任何行为变更（零回归约束除外）
- 插件运行中热插拔（启停经重启生效；运行中 dispose 机制不在本期）

## Further Notes

- ADR-0011 为架构依据；两处语义须在文档写明：**yml 行序即回答者路由**、**/exit 后 CLI 转 idle 需重启恢复**。
- 已知限制沿袭（非本票引入，留档）：交互工具查重注册下先到方的会话绑定生效（多入口 plan-mode 状态归属按先注册方）；纯 CLI 装配测试依赖本机 `~/.duo/config.yml`（先例披露）。
- 规格缺口提示（对齐 ADR-0011 决策 2 措辞）：**F5 刷新仍是全量快照**（ADR-0010 决策 2）；"刷新增量"已裁定走 backlog 的尾部窗口分页范式，不在本里程碑。
- 理解关卡：M3-M10 欠账须在 `/implement` 开工前清零（用户已知情，流程闸门）。
- 实施对账（2026-09-15，工单 03）：`run(in, out)` 测试入口未在 AgentReplMain 保留——REPL 测试入口已由 CliPlugin 注入构造承担（注入 in/out/sessionsDir/mock LLM 能力超集），spec 此句按实现演进对账；demo 专属装配（MCP 挂载+演示片段）经 `DuoMain.run` 的 ContextConsumer 回调注入，AgentReplMain 兼容壳保留该回调委托。
