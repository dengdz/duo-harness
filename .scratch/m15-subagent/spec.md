# M15 Spec：subagent 任务分解——内嵌子 agent、模板化装配与父流呈现

> ADR-0015（2026-09-16 grill 裁定：六问全裁 + DSH 源码核对）为直接依据；术语沿用术语表"子代理 / 种子边界"。基线 0.9.0 已发布；目标版本 **0.10.0**（开工首日切 `0.10.0` 分支）。

## Problem Statement

agent 遇到多步大任务时只能在单循环里串行硬扛：上下文被中间过程灌满（探索性子任务的失败尝试、海量中间输出全部挤占主对话窗口），任务分解只能靠模型在一条思维链里自己排队。框架没有"把子任务交给一个干净上下文的执行者、收回结论"的机制——对照 DSH 的 subagent 能力（spawn/fork + 控制面），这是 agent 从"单循环"走向"可分解任务"的结构缺口。

## Solution

`agent.subagent` 子包引入同进程内嵌子 agent：父 agent 调 `spawn`（全新）/ `fork`（带父历史播种）即刻返回 agent id，子 agent 在后台虚拟线程用**部署者预定义的模板**（工具清单 + 可选专属提示）独立运行、事件记入独立子会话；父会话以引用事件呈现"子任务卡"，子完成时最终回答进入父的上下文聚合；控制面三件（`send_message` / `interrupt_agent` / `list_agents`）归父治理。禁嵌套（深度 = 1），模型只选模板不配工具。

## User Stories

1. As a 使用者, I want 父 agent 把探索型子任务交给子 agent, so that 中间过程不污染主对话上下文
2. As a 使用者, I want 子 agent 完成后其结论出现在父对话中, so that 我在一条对话里看到分解与聚合的全貌
3. As a 使用者, I want 对话中看到子任务卡（状态：运行中/完成/中断 + 结果概要）, so that 分解过程可见不黑箱
4. As a 使用者, I want 子任务完成后能打开子会话看它的完整过程（含 fork 继承的背景）, so that 深挖排查有据可查
5. As a 使用者, I want 侧栏不被子会话淹没, so that 会话列表仍以人类对话为单位
6. As a 父 agent（模型）, I want spawn 时点名任务模板并给任务描述, so that 子 agent 用预审定的工具集干活而不需要我逐个配
7. As a 父 agent（模型）, I want fork 出带着我们对话背景的子 agent, so that 子任务不用重新交代前因后果
8. As a 父 agent（模型）, I want 子 agent 完成时它的最终回答自动进入我的上下文, so that 我能聚合结果继续主任务
9. As a 父 agent（模型）, I want send_message 给运行中的子 agent 补充指示, so that 中途纠偏不用推倒重来
10. As a 父 agent（模型）, I want interrupt_agent 中止跑偏的子 agent, so that 失控任务及时止损
11. As a 父 agent（模型）, I want list_agents 查看各子 agent 的状态, so that 分解了几个任务、各自进展心中有数
12. As a 部署者, I want 在 yml 里定义子 agent 模板（工具清单 + 可选专属提示）, so that 子 agent 能力边界由我审定
13. As a 部署者, I want 交互工具与控制面工具永远不进子模板, so that 子 agent 不会绕过我 directly 问人或互相治理
14. As a 部署者, I want spawn/fork 工具只在装配了 subagent 模板的实例上出现, so that 不需要分解能力的部署零变化
15. As a 审计者, I want 子 agent 的全部事件落独立会话日志, so any 时刻可回放任一子任务全程
16. As a 审计者, I want fork 子会话标记种子边界（前 N 条来自父）, so that 继承背景与子自身行为可区分
17. As a 框架维护者, I want 后端抽象只实现内嵌一种但留接口位, so that 跨 harness 委派等远期形态有干净的扩展点
18. As a 框架维护者, I want 禁嵌套（深度 = 1）, so that 无失控递归风险、无深度配额复杂度
19. As a 框架维护者, I want 子 agent 与父共享工具域注册表（并发安全已有）, so that 工具生态零复制
20. As a 使用者, I want 未配置 subagent 模板时 spawn/fork 工具不出现、对话行为与 0.9.0 完全一致, so that 升级零感知

## Implementation Decisions

**落位与后端**（Q2/Q1 裁定）

- `agent.subagent` 子包（预计 10~12 类）：后端接口 + 内嵌实现 + spawn/fork/控制面工具 + 子代理注册表 + 模板装配 + 事件适配。
- 后端接口仅实现"同进程内嵌"一种；跨 harness 委派留接口位（远期池）。

**双工具与控制面**（Q1 裁定）

- `spawn`（模板名 + 任务描述）/ `fork`（同 spawn，另播父历史）双工具语义独立；控制面三件 `send_message`（运行中纠偏 / 空闲开新轮）/ `interrupt_agent` / `list_agents`。
- 后台异步模型：spawn/fork 立即返回 agent id，子 agent 在后台虚拟线程运行；子完成写父会话 `subagent/completed`（最终回答投影进父 LLM 上下文）。同步等待模式不做。
- 控制面三件不进子模板可用集（控制权归父）；五件工具仅在装配了 subagent 模板的实例上注册（未配置部署零变化）。

**子会话与呈现**（Q3 裁定）

- 子 agent 独立 JSONL 会话：独立进程锁（与会话域语义一致），存放于会话目录子目录、侧栏列表排除；完成后可正常打开回放。
- 父会话引用事件：`subagent/spawned`（id、模板名、模式、fork 源引用；不投影——卡片专用）与 `subagent/completed`（结果概要 + 最终回答；最终回答投影进父 LLM 上下文）。
- Web/CLI"子任务卡"：复用现有卡片机制与 SSE 流，状态三态（运行中/完成/中断）。

**fork 播种**（Q5 裁定，DSH 同款）

- 播种 = 父日志平衡完成轮前缀（事件 0 到最近完成轮末尾，进行中半截轮排除），逆写为子会话日志开头段（复用现有事件类型的投影形态）。
- 子会话记录种子边界（"前 N 条来自父日志"），审计与回放据此区分继承背景与子自身行为；审计链经父会话 spawned 事件的 fork 源引用闭合。

**模板制装配**（Q6 裁定）

- `subagent` 插件 config：一套或多套模板（工具清单 + 可选专属提示）；spawn/fork 参数只有模板名 + 任务描述（+ fork 源语义内建）。
- 默认过滤（强制的，非模板可绕过）：交互工具（ask_user / exit_plan_mode）与控制面工具不进子模板可用集。
- 子 agent 的模型（LLM adapter）唯一从父继承；治理管线与父同配置（fork 大前缀必需）。

**禁嵌套**（Q4 裁定）

- 深度 = 1：子模板可用集不含 spawn/fork；无深度计数、无配额守卫。

## Testing Decisions

- 零新 seam 原则：全部经既有公共面——工具域（五件工具的注册与执行走 ToolsService execute，先例 ApprovalPolicyTest/AskUserTool 类）、会话域（子会话落盘与种子边界走 Session/SessionEvent 公共 API，先例 SessionTest）、装配（模板解析与过滤走 config 严格绑定，先例 PresenterAssemblyTest 的 governance 段解析）。
- 并发用例：spawn 后台运行与 completed 回流（虚拟线程时序用 latch 确定性断言）；interrupt 的中止语义；fork 播种的平衡前缀切点（进行中半截轮排除）。
- 投影用例：completed 进父投影、spawned 跳过（先例 approval 事件投影跳过用例）。
- 端到端：mock LLM 脚本驱动的"父 spawn 子 → 子调工具 → 完成 → 父聚合"全链（先例 AgentReplMainTest 的 Function Calling 闭环形态）。
- 呈现（子任务卡）验收期浏览器实测（UI 改动须视觉验证，红线 5）。

## Out of Scope

- 跨 harness 后端（真实 Claude Code / Codex 桥）——远期池，仅留接口位
- 同步等待模式（spawn 阻塞收结果）——控制面已覆盖
- 嵌套多层级（深度 > 1）与深度配额——禁嵌套
- 子模板外的动态工具定制（模型配工具）——模板制拒绝项
- 子 agent 的并行工具调用、steering/inbox 泛化——远期池
- 子会话的 Web 侧实时流式观看——完成后打开回放已覆盖，实时流待需求

## Further Notes

- DSH 源码核对结论（grill 期）：spawn 参数无 tools（模板/preset 制）；fork 播种 = 平衡完成轮前缀**落子会话日志** + 种子边界元数据；这两点是 Q5/Q6 裁定的直接事实依据。
- 工具域注册表并发安全（ConcurrentHashMap / CopyOnWrite）是多子 agent 并行的结构前提，M15 前已具备。
- 本里程碑全程在 `0.10.0` 版本分支进行，验收后合 main 发布（红线 7）。
