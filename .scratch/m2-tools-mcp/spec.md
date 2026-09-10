# M2 工具域强化 + MCP 接入 — Spec

Status: ready-for-agent

## Problem Statement

M1 之后，duo-harness 的工具域只是骨架：工具返回什么没有契约（结果可信度无从谈起）、工具调用没有审批语义（危险的调用与普通调用无区别）、没有动态否决手段（治理只能靠静态监听器）。同时，AI harness 生态的事实标准是 MCP——外部世界的真实能力（文件系统、git、数据库……）都封装在 MCP 服务器里，duo-harness 目前只能用手工写的本地工具，接不上这个生态。用户要的是：工具的结果可信、危险调用可控、外部能力即插即用。

## Solution

三个交付面：(1) **工具域强化**——工具声明输出契约（执行结果过校验，违约变错误结果）、guard 单调否决（执行前动态检查，只能拒不能放）、审批策略服务（`ask` 决策由策略裁决，M2 提供 auto-approve / always-deny 两个预设，交互式审批推迟到 M3 的 Web 面）；(2) **MCP 接入**——新模块 duo-harness-mcp 用官方 Java SDK 连接 MCP 服务器（stdio 优先），把远端工具以 `mcp__<server>__<tool>` 命名自动注册进工具域，生命周期全套（首连开关/指数退避重连/稳定窗口/预算耗尽注销）；(3) **端到端验收**——demo.yml 加 MCP 配置行，filesystem server 的工具自动出现并被三段管线治理，demo 展示"AI 通过 MCP 读真实文件"。并发安全分类与 scope 遮蔽**明确推迟到 M4**（消费方是 agent loop）。

## User Stories

**框架使用者（配置 MCP 与审批）**

1. As a 框架使用者, I want 在 yml 里加一行 MCP 配置（连接命令 + server 名）就接入一个 MCP 服务器，so that 不写代码就能获得它的全部工具。
2. As a 框架使用者, I want 同一配置行支持多个实例（连多个 MCP 服务器），so that 文件系统和 git 服务器可以并存。
3. As a 框架使用者, I want MCP 服务器启动失败时可以选择"容忍降级"或"启动即失败"（failOnStartupError 开关），so that 可选能力不拖垮核心。
4. As a 框架使用者, I want MCP 服务器断连后自动指数退避重连，so that 临时故障自愈而不用重启 harness。
5. As a 框架使用者, I want 重连预算耗尽后该服务器的工具被干净注销（而不是留一堆不可用的僵尸工具），so that 下游能明确感知能力消失。
6. As a 框架使用者, I want 给工具调用配置审批策略（auto-approve / always-deny），so that 危险工具默认被拦、安全工具自动放行。
7. As a 框架使用者, I want 审批决策由独立的策略服务做出，so that M3 的交互式审批上线时不用改工具域代码。

**插件作者（写工具、写治理）**

8. As a 插件作者, I want 给工具声明输出契约（schema），so that 调用方能信任结果形态。
9. As a 插件作者, I want 执行结果违约契约时自动变成带原因的错误结果，so that 契约违规在调用方一眼可见而不是产生静默的坏数据。
10. As a 插件作者, I want 用 guard 对工具执行做单调否决检查（返回理由即拒绝），so that 动态风控（如基于参数内容的检查）不依赖事件监听器的顺序。
11. As a 插件作者, I want guard 在普通 ctx 注册全局生效、在 agent 作用域注册只影响该作用域，so that 全局风控与局部风控可以并存。
12. As a MCP server 接入者, I want 我服务器的工具自动以 `mcp__<server>__<tool>` 命名注册，so that 工具来源一目了然且不会与本地工具撞名。
13. As a MCP server 接入者, I want 我声明的 outputSchema 被尊重（结果过契约校验），未声明则宽松透传，so that 契约跟着远端的能力走，不被强加。
14. As a MCP server 接入者, I want 我的工具列表发生变更（tools/list_changed）后 harness 自动同步，so that 我升级工具后 harness 不需要重启。
15. As a 插件作者, I want SDK（协议细节）被隔离在实现层，so that 我面向的工具契约与 MCP 无关。

**维护者（运维与诊断）**

16. As a 维护者, I want MCP 连接的每一步（首连/重连/预算耗尽/工具同步）都有可读日志，so that 连接问题可以远程诊断。
17. As a 维护者, I want 重连采用指数退避且带稳定窗口清零，so that 不会打挂一个正在重启的 MCP 服务器。
18. As a 维护者, I want 预算耗尽后工具注销但 MCP 插件不进 FAILED，so that "外部 server 挂了"与"插件代码错了"两种失败可区分。
19. As a 维护者, I want 审批决策（放行/拒绝/策略来源）有日志可查，so that 安全审计有据可查。
20. As a 维护者, I want SDK 依赖被隔离在 mcp 模块的实现层，so that 未来替换 MCP 实现不影响下游。

## Implementation Decisions

已 grilling 确认（10 项），详细理由见对话记录与 ADR-0006；术语定义见 CONTEXT.md「MCP 域」。

**范围裁剪**

- 并发安全分类（isConcurrencySafe）与 scope 遮蔽**推迟到 M4**（消费方是 agent loop / delegation runtime，当前缺位）——limitations.md 相应条目在本 spec 实现后更新。

**MCP 接入**

- 新模块 `duo-harness-mcp`（功能模块，依赖 tools）：契约在域根包（tools 同款平铺形态），SDK 隔离在 internal。
- MCP 协议实现采用官方 `io.modelcontextprotocol.sdk:mcp:0.10.0`（ADR-0006）；工单 01 核实其传递依赖，重依赖则评估 exclude。
- 传输：stdio 优先（`command` + `args` + `env` 启动外部进程）；HTTP（streamable）后置补齐。
- 工具命名：`mcp__<serverName>__<toolName>`；serverName 全局唯一（重复配置在装载时报错）；非法字符清洗规则与 DSH 对齐。
- 工具同步两阶段：先取全量远端工具列表并校验（重名/命名清洗冲突直接失败），再原子换新（旧代全部注销、新代逐个注册）；同步失败保留旧一代继续服务。
- `tools/list_changed` 通知触发自动重新同步。
- 生命周期语义照搬 DSH：首连默认容忍（`failOnStartupError: true` 时变启动失败）；断连指数退避重连（500ms 起、30s 封顶）；连接存活 ≥ 30s 清零失败计数（稳定窗口）；连续失败达预算（10 次）→ 注销该 server 全部工具并停止重连，**MCP 插件本身不进 FAILED**（外部 server 故障与插件代码错误可区分）。
- 工具异常与错误结果语义与本地工具一致：远端调用失败（isError / 传输失败）收敛为 error 结果。
- 配置行结构：`id` / `name: McpClientPlugin`（或专用子类）/ `config: { serverName, command, args, env, failOnStartupError, reconnect{...} }`。

**工具域强化**

- **输出契约**：ToolDefinition 增加 output 契约声明（结果 schema）与校验；违约 → error 结果（点名契约违约原因），与工具异常收敛同一出口。校验库 networknt json-schema-validator（1.5.0）。本地工具与 MCP 工具同标准；MCP 远端未声明 outputSchema 时宽松透传（双轨制）。
- **审批策略**：pre-execute 决策从两态（放行/否决）扩为三态（allow / deny / **ask**）；`ask` 委托审批策略服务裁决。M2 提供两个预设：`auto-approve`（按配置白名单放行）与 `always-deny`（默认，未配置即拒）。策略服务以服务形式发布（视图接口可寻址），M3 交互式审批是未来的一种策略实现——机制与形态分离。
- **guard 单调否决**：`ToolsService.guard(registrant, checkFn)`——返回理由字符串即拒绝（error 结果，理由透出），返回 null 即放行；**没有"允许"结果，监听顺序无法把拒绝翻回放行**（与 waterfall 监听器的可翻转性形成有意对照）。全局注册全局生效；guard 检查位于审批（pre-execute）之后、工具本体之前。
- 工具域三段管线的既有语义（异常收敛/error 结果/注册即 effect）全部不变，强化件是**加挂点**不是改管线。

**工程形态**

- 根 pom 注册 duo-harness-mcp；新依赖两项均经用户同意（官方 MCP SDK、networknt json-schema-validator），版本以 nexus 实测可得为准。
- 0.2.0 一次发布（不拆 0.2/0.3）。
- 分支 0.2.0 已建（从 main 切出）。

## Testing Decisions

- 只测外部行为：断言落在公共 API 与可观察输出（注册表状态、执行结果、日志叙述、连接状态），不测实现细节。
- 三条测试接缝（已与用户确认）：
  - **接缝 A（boot 全链路，沿用）**：MCP 配置行经 yml 装载 → 工具自动出现 → demo.yml 扩展后的端到端验收延伸此缝。
  - **接缝 B（tools 服务视图，沿用）**：三项强化件经 ToolsService 视图直测——output 违约变 error、guard 否决不可翻回、审批三态（ask 在两种预设下的行为）、审批决策与工具异常的日志可观察性。
  - **接缝 C（MCP 连接，新增）**：真实 stdio filesystem server 的集成测试（工具同步/命名/调用/错误映射——CI runner 自带 Node，可执行）；进程内假 MCP server（脚本式 stdio 进程）测重连退避、稳定窗口、预算耗尽注销、failOnStartupError 两态。
- 良好测试标准沿用 M1：AIR / BCDE、断言外部可观察状态、无 mock 框架（假 server 是真进程不是 mock 对象）、测试包镜像主包。

## Out of Scope

- 并发安全分类（isConcurrencySafe）与 scope 遮蔽——推迟 M4（与 agent loop 一起设计）
- 交互式审批（人在 UI/CLI 点确认）——M3 Web 面板交付；M2 只有两预设策略
- MCP HTTP（streamable）传输——stdio 优先，HTTP 后置补齐
- MCP 资源（resources）与提示（prompts）能力——M2 只接工具（tools）
- 文档站内容补齐（02 指南 / 05 参考）——独立任务
- 02 指南中"MCP 接入教程"篇章——随本 spec 实现后在文档站补写（独立小任务）

## Further Notes

- 设计依据：grilling 十项决策（2026-08-25）+ ADR-0006（MCP SDK 路线）+ CONTEXT.md「MCP 域」六术语 + DSH 研究三件套的 mcp-client 章节（`docs/research/DSH/插件化架构/`）。
- 工单拆分（5 张，依赖序）：1 MCP 骨架+SDK+stdio 连接 → 2 远端工具同步 → 3 审批策略服务 → 4 output 校验+guard → 5 端到端验收 demo；3 与 4 可并行。
- 已知待核实：SDK 0.10.0 的传递依赖树（工单 01 首个动作，重依赖则评估 exclude 或显式接受并记录）。
- M1 遗留的"两表分离动手实验"（comprehension.md 待办）与本 spec 无依赖关系，但建议在 M2 实现期间顺手完成。
