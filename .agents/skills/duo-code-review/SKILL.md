---
name: duo-code-review
description: 审查 duo-harness 仓库的代码变更、提交或 PR 时使用。将审查者定向到本仓库的标准（文档同步要求、证据要求、依赖政策、目录分层标准）与代码本身看不出来的检查项。当用户说"审查一下"、"review 这个改动"、"看看这次提交有没有问题"时触发。
---

# 审查 duo-harness 代码变更

**本技能是指导，不是完整清单。** 先看清楚变更范围（`git diff --stat` / `git show <commit>`），读足够的上下文代码理解设计意图，再逐项检查。优先级排序：正确性 > 生命周期/并发 > 安全 > 破坏既有行为 > 风格。短而实证的审查优于冗长的 nit 清单。

## 审查流水线（两段式，顺序固定）

1. **第一段——OCR 行级审查（先跑）**：`ocr review --audience agent -b "<业务上下文>"` 对 diff 出行级意见；端点大面积失败（403/400/timeout，多为模型过期）时按下方「ocr 执行策略」第 3 条切换**委托模式**（`ocr delegate preview/rule` + 本 agent 行级审查），不得跳过本段。
2. **第二段——`code-review` 技能双轴（后跑）**：调用 mattpocock `code-review` 技能（Standards/Spec 双轴并行子代理）。fixed point 取该工单改动前的提交（单工单审 `HEAD~1`，批次/里程碑审分支基点或用户指定点）；Spec 轴的 spec 来源 = `.scratch/<feature>/spec.md` 与对应工单文件。
3. **合并报告**：OCR 行级意见 + Standards/Spec 双轴发现合并为一份报告，blocker 与 suggestion 分级不变；两轴发现不重排不合并（双轴分离的本意）。

## 事实来源（读原文，不要凭记忆转述）

- `docs/` 与 `README.md`（README 未建，建成后并入）：对外承诺的行为。**文档与代码不一致按 blocker 处理**——文档里的 API 签名、默认值、行为描述对照源码核实，不信转述。
- 已知限制清单 `docs/limitations.md`：变更若消除了某条限制，文档必须同步删除该条。
- 决策记录 `docs/adr/`：历史决策的载体。与既有决策相抵触时是设计讨论，不是自动否决——但要在报告中显式提出。
- 通用 Java 规约（命名/异常/并发/日志）检查：交由 `open-code-review` 插件（`ocr` CLI）执行，`ocr review --audience agent -b "<业务上下文>"`；其对 diff 的行级意见与本节其余检查合并报告。

### ocr 执行策略（端点不稳时的分批、续跑与委托降级）

用户的 ocr 配置有多个可用模型（各约百万 token 上下文），端点/模型会间歇性 403/400/超时。应对：

1. **分批小范围优先于全量大 diff**：大范围（整分支）一次跑 40+ 文件时失败面大且难定位。用 `--exclude '<批次外路径>'` 按模块分批（如先 core、再 tools、再 example），每批失败独立、续跑代价小。
2. **`--resume <sessionId>` 续跑同一会话的失败项**；resume 要求输入未变——审查后若有新 commit，重新起会话而非强续。
3. **端点大面积失败（403/400/timeout 占多数，通常为模型过期或 key 失效）时：切换委托模式**——按 [open-code-review-delegate](../../../.zcode/cli/plugins/cache/open-code-review/open-code-review/1.0.0/skills/open-code-review-delegate/SKILL.md) 执行：`ocr delegate preview`（定文件清单与 diff 模式）+ `ocr delegate rule`（取审查规则），行级审查由本 agent 自己完成（委托模式 LLM-free，不依赖 ocr 的 LLM 端点）。同时提示用户修复 ocr 模型配置，修复后恢复普通模式；委托审查的发现与普通模式同格式合并进报告。
4. 已在历史轮次过审的文件，本轮端点失败造成的缺口**由手工维度补位**并在报告中标注——不因 ocr 缺席静默放行。

## Blocking 级要求

1. **文档与代码同步。** 配置项、默认值、异常行为、公开 API 签名变更时，同一 diff 内更新 README + docs/ 对应章节。Javadoc 声明的 `@throws` 必须与实现抛出的异常类型精确一致。
2. **证据存在。** 确认作者跑过覆盖该 diff 的测试（见 [duo-pre-push-checks](../duo-pre-push-checks/SKILL.md)）；审查其语义缺口。
3. **依赖与项目红线。** 引入新依赖必须先获得用户明确同意；根 AGENTS.md 的红线是唯一权威，变更与其相抵触按 blocker 处理。

## 手工检查维度

- **意图与接口契约**：每个改动的接口两侧都追一遍。实现是否匹配意图；错误、取消、资源所有权是否处理。
- **并发与生命周期**：先识别本仓库的并发惯用模式（线程模型、串行化点、取消标志），再逐项核对：发布前竞态、await 期间取消、回调重入、`finally` 清理完整性、迟到回调是否被标志挡住。订阅/回调句柄必须在 `finally` 释放（内存泄漏）；回调链的 onNext/onComplete/onError 恰好一次且串行。
- **SLF4J 陷阱**：尾随 Throwable 参数不填充占位符，必须显式 `toString()`。
- **能力与消费方匹配**：新增公开方法若只有一个内部调用方，质疑是否应为 private；反之，通用服务上出现消费方专用行为也是泄漏。
- **范围与必要性**：每个新抽象/状态机/选项/兼容路径映射到当前生产消费方。挑战无关功能与投机泛型。
- **包组织与目录分层**：新类落位对照 [duo-project-structure](../duo-project-structure/SKILL.md)：按功能域分包、禁止 `controller`/`service`/`util`/`impl` 大筐、根包不放类、新包同 diff 带 `package-info.java`。数一下 diff 涉及的包的类数，越过约 10 个的拆包阈值时要求按功能边界拆子包（不按文件类型拆）。堆放类问题按 suggestion 报，不与语义问题争位。
- **配置默认值**：每个默认值问"什么当前消费方证据或先例支持它"。没有证据时要求显式决策或推迟。
- **测试强度**：断言应在预期回归上失败；验证外部状态（事件顺序、流接收顺序）而非复述实现。mock 必须回放真实组件的异步语义——同步 mock 会让超时与竞态逻辑永不生效。
- **中文注释与文档**：新注释是否泄漏推理过程（审查编号残留、"本次修复"、变更叙述）？用 [duo-trim-cot-leakage](../duo-trim-cot-leakage/SKILL.md) 判定。

## 报告发现

陈述：缺陷、位置（文件:行号）、影响、证据。局部缺陷给到最紧的 diff 范围；跨切面问题（架构/范围）用总评。**blocker 与 suggestion 分离**，已由绿灯测试覆盖的问题不再重复。收到审查意见时逐条技术性核实或反驳，不做表演性认同。

审查输出分级：错误（编译不过/行为矛盾）/ 不准确（措辞偏差）/ 遗漏（应有未覆盖）/ 确认（抽查一致项）。
