# 里程碑重排：agent 循环提前，Web 双面后置

Status: 批准（v3，2026-09-13 修订）

修正 [ADR-0004](0004-三支柱先行路线图.md) 的里程碑排序：agent 循环提前、Web 双面后置，并细化为小步渐进序列（M3 单次对话 → M4 上下文 → M5 工具循环 → M6 打磨+HITL → M7 skills+plan-mode → M8 Web → M9+ 扩展）。v3 依据 DSH 0.1.5 功能全景研究（[docs/research/DSH/核心功能全景.md](../research/DSH/核心功能全景.md)，锚点 c291e796）校准 M6-M9+ 各期内容清单——骨架与依赖方向不变，HITL 机制切分、prompt 装配粒度、skills/plan-mode 语义均获得参照物验证。

## 背景

ADR-0004 排序时（M1 内）的理由有二：Web 消费会话事件，依赖顺序自然；agent 循环会反向对内核提需求，容易把内核带向单体，放最后倒逼接口不偏。M2（0.2.0）交付后重新评估，两个前提都变了：

1. **无会话的 Web 面只是开发者诊断页**。能展示的只有插件六态、工具清单、连接状态——这些信息控制台叙述已完整传达。DSH 的 Web 有价值恰恰因为背后有 agent 循环与会话；"Web 消费会话事件"的依赖顺序要等会话存在才真正兑现。
2. **内核被带偏的担忧已减弱**。内核接口经 tools、mcp 两个消费方锤炼两个里程碑；且工具域治理链（审批 / guard / 输出契约）恰是为"不可信的自动调用"准备的——agent 的前置能力已就绪。

DSH 依赖结构印证：其 Web 面（UI 与持久化）都是 `session/event` firehose 的消费者；`agent-loop` peer 依赖 llm / session / system-prompt / tools——**这四者加工具域构成"能对话"的不可分割闭环**，拆开建会出现"建了没人消费"的空转期。

## 决策

agent 循环提前（方向不变），并**细化为小步渐进序列**——agent 涉及提示词、工具、上下文、记忆、workspace 等大量子域，单个里程碑塞下"四件套"粒度过大、做废风险集中。每期小而实、结束即稳定态，方向偏差的损失被限制在当前一期：

| 里程碑 | 主题 | 依赖 |
|---|---|---|
| M3 | 模型单次对话：LLM 适配器（OpenAI 兼容 + 流式）+ CLI | 无 |
| M4 | 上下文：会话事件溯源 + JSONL 持久化（`~/.duo/sessions`）+ 会话恢复 | M3 |
| M5 | 工具循环（真 agent）：tool-call → 工具域治理链 → 回填 → 循环 | M4 + 工具域 |
| M6 | agent 打磨 + HITL（CLI 版）：system-prompt 组装注册表 + 审批/提问终端交互策略 + LLM 重试 + repeat 防失控 guard + REPL 打磨小件 | M5 |
| M7 | agent skills + plan-mode + AGENTS.md 注入：技能系统（三路触发 + 发现根）、计划模式（引导式，计划人批准再执行）、项目指令文件注入 | M6 |
| M8 | Web 双面（最小面）：HTTP 服务 + 会话事件流 + 对话/状态界面 + HITL Web answerer（复用 M6 交互 seam，验证机制呈现分离） | M4 + M6 |
| M9+ | 具名候选期（按需逐期）：上下文治理（compaction + token 计量 + 工具结果修剪 + spill，四件一套）、subagent、并发工具调度、hooks、workspace/fs 工具族、附件、会话查询/导出、权限预设（依赖沙箱概念） | 各自独立 |

排期逻辑：

- **HITL 是通道随宿主演进，不是单一里程碑**：策略接口（seam）M5 立住，M6 加 CLI 交互形态，M8 加 Web 交互形态——策略可插拔，形态演进不动机制。DSH 的 answerer waterfall 验证了这一切分：审批与提问都是"seam + 可注册的回答者"，谁在场谁作答，无人应答 fail-closed。
- **M6 吸收两个可靠性小件**（v3）：LLM 按策略重试是 limitations 挂账的已知限制，DSH 证明它是独立的步边界装饰件；repeat 防失控 guard（同一工具调用连读多轮逐级提醒）为硬迭代上限配软手段。二者与 HITL 同属"把 agent 从演示形态变可用形态"，单拆有损里程碑粒度。
- **plan-mode 在 HITL 之后**：其语义是"计划要人批准再执行"，无 HITL 通道则无处批准——DSH 的 plan-review 即提问 seam 的一个 intent（M7 复用 M6 机制）。
- **agent skills 在 system-prompt 注册表之后**：技能 = 提示段 + 工具 + 流程的打包加载，提示注册表是挂载点；AGENTS.md 注入同属"给 agent 注入知识"，与 skills 同期顺路（v3 新增，duo 特有场景——仓库自身即以 AGENTS.md 管理）。
- **M8 收紧为最小面**（v3）：会话事件流 + 对话界面 + HITL Web answerer；DSH 的 50+ UI 插件（工具调用树 / trajectory / jobs 面板 / 设置页）列为远期菜单非本期范围——先验证"机制不变、换呈现位"。
- **workspace 在扩展期**：它是 fs 工具族的路径边界，M3–M6 的文件操作走 MCP filesystem server（自带路径约束）。
- **M9+ 从杂项堆改为具名候选期**（v3）：上下文治理四件套（compaction、token 计量、工具结果修剪、spill）应作一整期；其余按需逐期，顺序到时再定。

## 拒绝的选项

- **维持原序（M3 做 Web）**：无内容供给，验收效果差；M4 接会话时 Web 面大概率返工。
- **会话域与 agent 循环拆两期**：依赖图上闭环不可分——会话没有 LLM 与循环就没有事件源，循环没有会话就没有状态基座。
- **agent 优先且不做会话域（内存直连）**：事件溯源是 Web 面（firehose 消费）、持久化、compaction 的共同基座，欠账会在 M4 全部补课。
- **retry / repeat guard 单拆独立小期**（v3）：二者体量小（各一个插件包），单拆使里程碑数膨胀、每期都要走完整验收与理解关卡流程；并入 M6 的"打磨"语义更贴切。
- **权限预设提前**（v3）：DSH 的三档预设打包的是"沙箱模式 + 审批策略"，duo-harness 无沙箱概念，照搬是空壳——留 M9+ 与 sandbox 一并考虑。

## Consequences

- 会话事件词汇（`user/message`、`assistant/chunk|message`、`tool/call|result` 等）成为 M3 的核心公开契约——M4 的 Web 面直接消费它，设计须面向消费方。
- 工具域治理链首次获得真实消费者：审批在无界面的 CLI 下如何交互成为 M3 待决问题（策略缺省 always-deny 仍安全）。
- ADR-0004 的"三支柱先行、agent 最后"排序废止；"能力以插件组装、每步可示例验证"的总原则不变。
- **交互机制与呈现分离升级为 M6 的立约决策**（v3，M6 grill 中落 ADR-0008）：审批与提问都是"seam + 可注册的回答者（answerer）"，M6 交付 CLI answerer，M8 交付 Web answerer；无人应答一律 fail-closed，不做"永久放行"。
- M6 的会话事件词汇预计扩展（审批请求/决定、模型提问/回答的审计事件）——事件溯源词汇是公开契约，扩展须向后兼容（M4 先例：可选字段）。
