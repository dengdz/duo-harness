# subagent 任务分解：内嵌子 agent、模板化装配与父流呈现

Status: 批准（2026-09-16，M15 grill 裁定：六问全裁 + DSH 源码核对）

M14（0.9.0）拆包后 agent 域为 subagent 预留了落位。本决策引入任务分解能力：父 agent 经 spawn（全新）/ fork（父历史播种）把子任务交给**同进程内嵌的子 agent**，经控制面三件（send_message / interrupt / list）治理，子 agent 全程事件独立成档、过程经父会话引用事件呈现。关键事实前提：工具域注册表并发安全（ConcurrentHashMap / CopyOnWrite），多 agent 实例共享 ToolsService 无结构障碍；单飞语义在呈现层（Web/CLI）而非 agent 内部——并发子 agent 无既有结构冲突。

## 背景

roadmap M15（任务分解，★）与 DSH 研究的差距项。DSH 源码核对（`packages/subagent/`）：spawn 工具参数只有任务描述与 prompt（**没有 tools**）——子 agent 组成由部署侧 preset 决定；fork 播种 = 父日志"平衡完成轮前缀"（到最近 `turn/end` 止，半截轮排除）**写入子会话日志开头**，并在子会话元数据记"前 N 条来自父"的种子边界——不记则冷读子会话无法重建真实历史。跨 harness 后端（真实 Claude Code / Codex 桥）为 DSH 六种后端之一，本期不做。

## 决策

1. **落位与后端**：`duo-harness-agent` 新增 `agent.subagent` 子包（M14 拆包预留位）。后端抽象只实现"同进程内嵌 agent"一种（接口留扩展位，跨 harness 委派属远期池）。
2. **双工具 + 控制面**：`spawn` / `fork` 两个工具（语义独立、描述各自清晰），控制面三件 `send_message`（运行中纠偏、空闲开新轮）/ `interrupt_agent` / `list_agents`。spawn/fork 立即返回 agent id——**后台异步模型**，子 agent 在后台虚拟线程运行；同步等待模式不做（控制面已覆盖）。
3. **子会话独立成档**：每个子 agent 一个独立 JSONL 会话（进程级锁语义与会话域一致），存放于会话目录的子目录、**不进侧栏列表**；子 agent 完成释放锁后可被打开查看全程。父会话写轻量引用事件：`subagent/spawned`（id、模板、模式、fork 源引用）与 `subagent/completed`（结果概要 + 最终回答）——completed 的最终回答**投影进父 LLM 上下文**（父聚合结果的数据源），spawned 不投影（呈现卡片专用）。
4. **fork 播种（DSH 同款）**：父日志的平衡完成轮前缀逆写为子会话日志开头段（复用现有事件类型投影形态），并在子会话记录种子边界（"前 N 条来自父日志"）——子会话自包含可冷读，审计链经父会话 spawned 事件的 fork 源引用闭合。
5. **模板制装配**：`subagent` 插件 config 定义一套或多套子 agent 模板（工具清单 + 可选专属提示），spawn/fork 时模型只点名模板——**工具配置权在部署者**（M12 权限预设同一治理哲学），模型没有配工具的权力。交互工具（ask_user / exit_plan_mode）与控制面工具不进子模板可用集；子 agent 的模型（LLM adapter）唯一从父继承，治理管线与父同配置（fork 大前缀必需 spill/compaction）。
6. **禁嵌套（深度 = 1）**：子模板工具集不含 spawn/fork——防失控递归，不发明深度配额守卫；多层需求出现再放开。

## 拒绝的选项

- **跨 harness 后端**（把真实 Claude Code / Codex 当子 agent）：工作量倍增且依赖外部 CLI 安装，远期池。
- **同步等待模式**：spawn 阻塞到子完成——控制面三件形同虚设（无运行态可治理），与决策 2 冲突。
- **内存前缀播种**（种子不落子日志）：子会话不自包含，冷读缺背景——与"子完成后可打开看全程"（决策 3）的体验矛盾；DSH 同款落日志方案成本可控。
- **播种落日志但无边界标记**：真用户消息与播种消息在审计上不可区分——种子边界是必须品不是装饰。
- **spawn 参数内联配工具**：把工具配置权交给模型，越权组合不可预期——被模板制取代。
- **子会话进侧栏实时可切**：活跃子会话锁被持有，点开即撞锁；侧栏被子会话淹没。
- **限深 N 层嵌套**：为不存在的需求发明深度配额与守卫管线。

## Consequences

- session 域新增 `subagent/spawned` / `subagent/completed` 事件词汇与投影规则（completed 进父投影、spawned 跳过）。
- 工具域新增五件注册（spawn / fork / send_message / interrupt_agent / list_agents），模板过滤在 subagent 装配处实现，工具域注册机制零改动。
- `agent.subagent` 子包预计 10~12 类；Web/CLI 新增子任务卡渲染（复用现有卡片机制与 SSE 流）。
- 术语表新增"子代理（Subagent）""种子边界（Seed Boundary）"词条。
- 已知限制（进 limitations）：同步等待不做；子模板外的工具定制后加；跨后端仅接口位。
