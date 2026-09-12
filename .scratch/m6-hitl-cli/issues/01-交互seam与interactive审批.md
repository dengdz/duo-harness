# 01: 交互 seam 与 interactive 审批

**What to build:** 审批三态的 ask 首次获得真实回答者（ADR-0008 机制核）：治理插件或工具声明需审批后，`interactive` 策略把"需人作答"的请求交给交互服务，由在场的回答者呈现并作答——没有回答者、或人未作答（EOF / 中断）一律按拒绝处理（fail-closed）。每次请求与决定以 `approval/requested` / `approval/decided` 事件落会话（可选字段演进，旧会话文件向后兼容）。机制核不依赖会话与终端——留痕与呈现都是可组合的关注点。

**Blocked by:** None (can start immediately)

**Status:** implemented（2026-09-13，待用户验收）

## Checklist

- [x] 交互服务（tools 域）：`register(registrant, answerer)` 随作用域摘除；请求遍历在场回答者；无回答者 fail-closed
- [x] `interactive` 审批策略：ask 委托交互服务，allow / deny 两态返回并署名来源
- [x] SessionEvent 新增 `approval/requested` / `approval/decided`（载荷：工具名、声明来源、决定、回答者来源），JSONL 可选字段向后兼容（M4 先例）
- [x] 测试（mock answerer）：allow 放行 / deny 转错误结果 / 无回答者 DENY / 回答者异常 fail-closed
- [x] 会话回放断言：审批交互后事件对完整、旧格式样例回放不破坏（SessionTest 先例）
- [x] 文档同步：词汇表已立（交互 seam / 回答者 / fail-closed），如实现与词条有出入回改词条

## 实现记录（2026-09-13）

- 契约：`InteractionRequest`（approval/question 两类）、`InteractionAnswer`（allow/deny/answered + failClosed）、`Answerer`（返回 null = 放弃作答权交下一个）、`InteractionService`（"answers"）+ `AnswersView` + `InteractionPlugin`
- 装配形态与 grill 时的单一插件设想不同：**interactive 策略立为独立插件 `InteractiveApprovalPlugin`**（inject answers + Plugin<Void> 无配置），与 ApprovalPlugin 二选一挂载（同服务名占坑互斥）。原因：插件作用域不解析兄弟服务，策略作为 ApprovalPlugin 的配置项需要跨域寻址会破坏内核作用域语义；inject 是内核的标准消费者模式，且 answers 缺位时插件 PENDING 点名可见、工具域退回"未配置即拒"，fail-safe 不破
- 三段闸门提取为 `internal.ApprovalGate`（两个审批插件共用）；`InteractivePolicy` 经 ctx 惰性寻址 + 双重 fail-closed（服务缺位 / 无回答者）
- 测试：InteractionRegistryTest 6 例 + InteractiveApprovalTest 4 例 + SessionTest 审批事件回放 1 例；全量回归 336/0/0
- 教训入档：PENDING 插件的 `awaitStartup()` 无限等待依赖——测试装配缺失依赖的插件时绝不可 await（挂死过一次，dump 定位）

## Comments

spec：[../spec.md](../spec.md)；机制立约：ADR-0008。faill-closed 与"无 remember"是红线语义，任何实现不得引入"缺省放行"路径。
