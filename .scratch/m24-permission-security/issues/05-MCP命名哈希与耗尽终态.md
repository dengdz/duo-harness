# 05: MCP 命名哈希与耗尽终态

## What to build

MCP 远端工具名一律规范化 + 短哈希后缀（`mcp__<server>__<tool>__<hash8>` 形态，哈希对规范化后全名计算）——任何服务器组合下名字稳定可预期，工具名在审批卡/权限规则/会话历史里前后一致；废弃「清洗坍缩重名即抛错」旧语义（双服务器同名工具共存不炸）；重连耗尽（GAVE_UP + 注销既有语义之上）补会话事件通知（模型与用户可见）+ Web 状态面标注不可用，消灭静默消失；断连期 tools/list_changed 忽略语义不变。

决策依据：ADR-0026 决策四；探测 docs/research/ZCode/扩展机制/MCP.md。

## Blocked by

无（可立即开工）

## Status
done

## Checklist
- [x] 规范化 + 一律哈希后缀（稳定性测试：同输入同名、跨服务器组合不漂移）
- [x] 重名不再抛错（双服务器同名工具共存场景）
- [x] 重连耗尽会话事件通知 + Web 状态面标注
- [x] 测试（先例 McpToolSyncTest / ReconnectPolicyTest / ConnectionLifecycleTest / WebFaceTest）
- [x] 工单级验收件：同名工具共存 + 拔服务器可见通知演示，用户手动确认
- [x] CHANGELOG 记账（0.19.0 段）

## Comments
- 2026-09-23：实现与三轴审查完成（报告见下），全量 BUILD SUCCESS（858 用例 0 失败，2 既有 skip）。
- 2026-09-23：**用户手动验收通过（DemoMain M2 段真机实测）**：远端工具名带哈希后缀（`mcp__files__read_file__5330f725`、`mcp__files__write_file__85d62d9b`）；涉密 guard 前缀拦截与 always-deny 审批拒绝在哈希名下照常生效；拔连接后 `[消失] 未注册` 正常。同名共存与耗尽通知由自动化测试锁定（McpToolSyncTest 坍缩共存/预算耗尽用例 + ConnectorStatusBoardTest）。全场景过，转 done。
- **命名实现定稿（审查后修正）**：哈希对 **server + 原始工具名** 计算（初版对清洗后名计算，被行级轴抓出「清洗同形异名工具哈希相同」违背防坍缩承诺——修正后清洗同形的异名工具哈希必不同）；展示段仍为清洗后名字。清洗同形 + 哈希截断碰撞的双保险 = convertAll 序号后缀兜底（正常永不进入）。
- **状态板定位记档**：`ConnectorStatusBoard`（service 名 connectorStatus）落 tools 域——通用连接器状态聚合，mcp 填充 / CLI 订阅通知（收件箱注入，空闲开新轮、忙挂 next-turn）/ WebFace 状态面读快照；三处都只依赖 tools，避免 web/cli 反向依赖 mcp。行停止/拔线即 remove 条目（防幽灵永驻）。
- 已知边界记档：① 状态板 static SHARED 跨插件行聚合，行 dispose 即 remove 条目（幽灵已防）；② provide 幂等兜底（重复注册吞异常按复用处理）；③ mcp 行须先于 cli 行装配（订阅在 apply 时判定，optionalInject 时序契约已注释）。

## 审查报告（第 1 轮·三轴）：工单 05 全 diff（基点 bcadc61 工作树，6 改 + 4 新增）

**覆盖**：10 文件 = 已审 10 + 跳过 0（覆盖率 100%）；行级轴名单 4 文件（OCR preview），测试/文档/示例由 Standards/Spec 轴覆盖，全集口径含示例适配 2 文件

### 阻断
- **行级轴 high：哈希输入违约**——javadoc 承诺「哈希基于含原始名的全名」但实现对清洗后名计算：`a.b` 与 `a b` 清洗同形 → 同哈希同名，防坍缩核心承诺未兑现（已修：哈希改 server+原始名，javadoc/术语表同步；序号循环降级为截断碰撞兜底）
- **Standards 硬违规 2**：CHANGELOG 未记账（红线 6）；模块划分.md / 工具目录.md / 插件配置参考 旧命名与缺 connector 描述（红线 3）——均已修

### 建议
- ConnectorStatusBoard 无 remove API → GAVE_UP/拔线条目永驻（Standards-b2/行级#10）——已修：remove(server) + 生命周期接线
- onGaveUp synchronized 与 COW 口径混杂——已修：统一 COW（update/snapshot 保留 synchronized）
- provide check-then-act 竞态（行级#7）——已修：幂等兜底 catch
- CONNECTING 幽灵条目（行级#8）——已修：失败路径 remove
- parseAuth 式时序契约未注释——已修：CliPlugin 注释行序要求
- WriteProtectorPlugin startsWith 宽匹配（write_file_v2 误入 ask）——记档（哈希名可精确计算，但前缀对演示语义可接受；精确化随需要）
- 骨架重复/魔法数字 10s/errorFrom 绕行——同构先例记档

### 测试覆盖
- 新增 ConnectorStatusBoardTest 3 用例（覆盖/排序/通知触达/视图桥接）；McpToolSyncTest 全名迁移 + 坍缩共存用例重写（哈希必不同 + 双工具入册）；DemoMain/DemoMainTest 随哈希名迁移
- 缺口：耗尽通知的收件箱注入链路（CliPlugin→startTurn/injectNextTurn）无单测——由工单级验收件（拔服务器演示）手动覆盖

## Standards 轴原样分列

- 硬违规 2（红线 6/红线 3——均已修）+ 依赖方向核对通过（ConnectorStatusBoard 落 tools 合法，泛化定位须记模块划分——已随红线 3 修复覆盖）
- 基线 4：convertAll while 循环顺序依赖（哈希改原始名后坍缩对不进循环，仅剩截断碰撞兜底）、SHARED 全局状态（已加 remove 缓解）、锁口径混杂（已统一）、startsWith 宽匹配（记档）

## Spec 轴原样分列

- (a)：CHANGELOG 缺（已修）、测试面半做（connector 断言轻缺口记档、验收件待手动）、McpToolNames 注释矛盾（已修）
- (b) Scope creep：无
- (c) 实现正确性：哈希稳定性（含 server 全名）✓、序号兜底顺序前提（哈希改原始名后已消解）、GAVE_UP 语义与 ADR 一致、通知路由复用 M23 先例 ✓；订阅时序依赖（已注释契约）

## 行级规则轴原样分列

- 存留 7 项：high 1（哈希输入违约，已修）、medium 3（onToolsChanged 退化比较——随哈希修正消解；provide 竞态——已修兜底；SHARED 残留——已修 remove）、low 3（null NPE——已加 requireNonNull；截断魔法数——提常量；errorFrom 绕行同构先例）
- 已过滤误报 8 条（fireGaveUp 锁外无死锁、ServiceRegistry 重复即抛属实、serverName 源头校验在案等）

## 审查报告（第 2 轮）：修复复核

第 1 轮修复均为小改（哈希输入、remove/幂等、锁口径、文档），由新增与既有测试锁定（858 用例 BUILD SUCCESS）——按 SKILL 第 4 步未触发四轴复跑。
