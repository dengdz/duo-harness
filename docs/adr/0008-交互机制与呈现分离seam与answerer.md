# 交互机制与呈现分离：seam + answerer

Status: 批准（2026-09-13，M6 grill 产出）

## 背景

M2 立审批三态（allow / deny / ask）时，`ask` 的裁决者只有策略预设（always-deny / auto-approve），"人怎么说"没有承载者。M6 要在 CLI 上实现交互式审批与模型提问（ADR-0007 v3），M8 要在 Web 面再做一遍。DSH 0.1.5 的 interaction 域给出了经过验证的切分：审批与提问都是"seam + 可注册的回答者（answerer）"——机制声明与界面呈现分离，随宿主演进只换回答者（见 [DSH 核心功能全景](../research/DSH/核心功能全景.md) 第三节）。

## 决策

1. **一个 seam 承载两类交互**：审批（harness 发起，ask 策略裁决为"需人作答"）与提问（模型发起，`ask_user` 工具执行本体）都交给回答者回答。
2. **回答者注册制**：交互服务提供 `register(registrant, answerer)`，随注册作用域自动摘除——与 tools / guard / prompts 完全同构。AgentRepl 注册 console answerer（M6），Web 面注册 web answerer（M8），机制零改动。
3. **fail-closed**：无回答者在场、或人未作答（EOF / 中断）一律按拒绝处理；**不做"永久放行 / remember"**（DSH 明确不做，持久授权属策略层未来课题）。
4. **归属 tools 模块**：审批在工具域治理链内、`ask_user` 是工具——seam 接口与 interactive 策略、提问工具定义都放 tools 域；依赖方向保持向下（example 注册 console answerer 合法，agent 循环无感知）。若 seam 放 agent 模块，tools→agent 反向依赖与 M5 的 agent→tools 成环——被依赖图否决。
5. **会话留痕**：问答复用 `tool/call` + `tool/result`（提问即工具调用，零新词汇）；审批新增 `approval/requested` / `approval/decided` 事件类型（携带工具名、声明来源、决定、回答者来源），由装配层的**装饰性审计回答者**（包装真实回答者，委托前后写入会话）实现——机制核不依赖会话与监听链，留痕是可组合的关注点；终端呈现由 console answerer 自身承担，`AgentListener` 不扩。

## 拒绝的选项

- **interactive 策略直读终端**（System.in）：实现最快，但策略被 CLI I/O 绑死——测试无法挂载、M8 需重写策略，机制与形态焊死；省下的成本在 M8 全部还回。
- **seam 放 agent / 独立 interaction 模块**：前者制造 tools→agent 循环依赖；后者为一个接口立模块，M6 体量不值（升级路径：交互面扩大时再立模块）。
- **审批不做会话留痕（仅日志）**：M8 Web 面要回放审批历史，届时补事件词汇即破坏性演进；M6 一次做对。

## Consequences

- M6 新公开契约：交互服务（answerer 注册）、interactive 审批策略、`ask_user` 工具定义、`approval/*` 会话事件类型（可选字段向后兼容，M4 先例）。
- M8 的 Web answerer 是本决策的直接验证：只加呈现位，不动机制；若 M8 实现被迫改机制，即本 ADR 证伪。
- 词汇表新增：交互 seam、回答者、fail-closed、提问工具（CONTEXT.md）。
