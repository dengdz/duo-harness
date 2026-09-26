# 05: 压缩熔断计数 + summary 预算隔离

## What to build
microcompact 的两张安全网：① 压缩熔断计数——summary 压缩连续失败达阈值时熔断（停止继续尝试压缩，会话继续可用），防"压缩失败→重试→再失败"循环卡死；② summary 预算隔离——microcompact 与既有 summary 压缩并存互不干扰（各管各的预算，用户故事 14/15）。交付后的可感行为：长会话在任何压缩异常下都能继续聊。

## Blocked by
04

## Status
done（2026-09-26 用户亲手跑 ContextGovernanceFuseTest 验收通过——熔断状态机 5 用例全绿，WARN 轨迹与状态机预期逐一对应）

## Checklist
- [x] 压缩熔断计数（连续失败阈值 + 熔断后行为：会话继续、状态可见）
- [x] microcompact 与 summary 压缩并存互不干扰（预算隔离用例）
- [x] tdd 红绿循环（熔断触发 + 并存用例，seam 见 Comments 记档）

## Comments
- 2026-09-25：**机制裁定（ZCode 压缩治理同构）**：①熔断 = summary 压缩连续失败达 3 次置熔断——自动压缩静默暂停、会话照常出投影；仅成功压缩清零计数并解除（auto 与 manual 两条成功路径都清）；manual /compact 不受熔断约束（用户显式指令优先）；空摘要与异常同计入失败（审查修复：LLM 可达但持续返空白同样是每轮空烧路径，两轴共振抓出）。②状态可见 = occupancy 新增 compactionTripped 字段全链透出（ContextGovernance→WebFace /api/status→状态面「⚠ 压缩已熔断」标注），字段 volatile（HTTP 线程读）。③并存互不干扰 = 熔断只挡 summary，microcompact 照常裁剪（专项用例锁定）；latestUsage 计量感知扩展到裁剪点（04 留的"计量口径请 05 复核"兑现：治理点后旧 usage 作废回本地估算，防虚高误触发）。
- 2026-09-25：**TDD seam**（自主模式按 spec Testing Decisions 定，ContextGovernanceFuseTest 5 用例）：①短结果夹具连败 3 次熔断 + 熔断后投影照常（calls 冻结）②成功清零恢复 + 清零后重计数（压缩点后补新历史再验，审查修掉断言虚过）③熔断后 manual 照常且成功清零④熔断态 micro 照常裁剪（keepRecent=2 配置下新组落窗口外，calls 冻结证明互不牵连）⑤裁剪点作废旧 usage（fromProvider 翻转）。实现期两处夹具/断言缺陷由测试运行抓回修正。
- 2026-09-25：**rapid-refill 有意不做**（spec 决策五未点名，工单 Checklist 无此项）：ZCode 的"3 工具轮内连环压缩中止 turn"防的是压缩成功后急速回涨——duo 的 compaction 事件化投影（摘要替换后不重触发）+ microcompact 先行卸载已天然缓解；挂账 backlog 观察，若实测出现连环压缩再立项。
- 2026-09-25：全量 975 用例 0 失败（2 既有 skip）。
- **验收通过（2026-09-26，用户亲手跑熔断套件）**：5 用例全绿；WARN 轨迹与状态机预期逐一对应（manual 用例 1/3→2/3→熔断、连续失败用例同轨迹、清零恢复用例 1/3→2/3→修复后清零）。首跑因验收命令漏 `-Dsurefire.failIfNoSpecifiedTests=false` 旗标报错（命令由 agent 提供，已修正重发）——记档：给用户的单测命令必须带该旗标（-am 拉起的上游模块无匹配测试时会报错退出）。工单转 done。

## 审查轮（2026-09-25·第 1 轮·四轴两路合并）

**覆盖**：6 文件 = 已审 6 + 跳过 0（行级主代码 6/6 100%；文档改动 3 处由双轴覆盖）

### 阻断
- **CHANGELOG 缺 05 条目** [Standards，红线 6] → **已修**：补熔断 + 状态可见 + 并存互不干扰记账

### 建议（两轴共振优先）
- **blank summary 不计失败——熔断盲区** [行级+Standards 共振] → **已修**：空摘要与异常统一收敛 compactionFailed（计数 + 达阈熔断 + warn 带因）
- **测试空转（清零恢复用例尾段断言虚过）** [Spec+行级共振] → **已修**：压缩点后补新历史 + 新 usage 再验单次失败不熔断
- **E-04 熔断 warn 丢堆栈** [Java MAJOR] → **已修**：compactionFailed 末参加 cause（置位一次也带）
- **并发可见性** [Java MAJOR] → **已修**：计数与熔断标志加 volatile（occupancy 自 HTTP 线程读）
- **F-09 超 120 行 ×2** [Java CRITICAL] → **已修**：折行
- **O-18 常量 public** [Java] → **已修**：收窄包私有（测试同包引用）
- **compact/latestUsage javadoc 契约滞后** [Standards] → **已修**：补熔断失败模式与双断点语义
- **app.js 注释过程叙述** [Standards] → **已修**：改代码契约表述
- **术语表缺「压缩熔断」词条** [Standards] → **已修**：并入压缩点/microcompact 姊妹链
- **rapid-refill 未记档** [Spec] → **已修**：Comments 记档有意不做 + 挂账理由
- 记档不修：status 面字段无逐字段参考文档（全字段均无，既有欠账非本单引入）/ 用例 4 calls 断言可被阈值早退替代满足（calls 冻结仍是熔断态必要证据，保留）

### 测试覆盖
- 熔断状态机 5 用例（触发/状态可见/清零恢复/manual 不受限/并存互不干扰）+ 04 既有计量语义用例扩展
- 状态可见链路（occupancy→WebFace→app.js）payload 字段人工核对 + 前端 undefined falsy 防御在册
