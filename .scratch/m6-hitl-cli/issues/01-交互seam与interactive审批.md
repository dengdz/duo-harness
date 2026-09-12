# 01: 交互 seam 与 interactive 审批

**What to build:** 审批三态的 ask 首次获得真实回答者（ADR-0008 机制核）：治理插件或工具声明需审批后，`interactive` 策略把"需人作答"的请求交给交互服务，由在场的回答者呈现并作答——没有回答者、或人未作答（EOF / 中断）一律按拒绝处理（fail-closed）。每次请求与决定以 `approval/requested` / `approval/decided` 事件落会话（可选字段演进，旧会话文件向后兼容）。机制核不依赖会话与终端——留痕与呈现都是可组合的关注点。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

## Checklist

- [ ] 交互服务（tools 域）：`register(registrant, answerer)` 随作用域摘除；请求遍历在场回答者；无回答者 fail-closed
- [ ] `interactive` 审批策略：ask 委托交互服务，allow / deny 两态返回并署名来源
- [ ] SessionEvent 新增 `approval/requested` / `approval/decided`（载荷：工具名、声明来源、决定、回答者来源），JSONL 可选字段向后兼容（M4 先例）
- [ ] 测试（mock answerer）：allow 放行 / deny 转错误结果 / 无回答者 DENY / 回答者异常 fail-closed
- [ ] 会话回放断言：审批交互后事件对完整、旧格式样例回放不破坏（SessionTest 先例）
- [ ] 文档同步：词汇表已立（交互 seam / 回答者 / fail-closed），如实现与词条有出入回改词条

## Comments

spec：[../spec.md](../spec.md)；机制立约：ADR-0008。faill-closed 与"无 remember"是红线语义，任何实现不得引入"缺省放行"路径。
