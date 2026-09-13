# 02: AGENTS.md 注入

**What to build:** agent 知道项目规矩：装配时加载 `~/.duo/AGENTS.md`（用户全局，可缺）与项目根 AGENTS.md（从 cwd 向上找 .git 定根；无 .git 则仅用户全局），按"用户全局 → 项目根"顺序拼接，64KB 总预算超限截断尾部并注明，注册为 `agents-md` 片段（排在用户配置片段之后、其他插件片段之前）进 prompt 注册表。

**Blocked by:** None (can start immediately)

**Status:** implemented（2026-09-13，待用户验收）

## Checklist

- [x] 加载器：.git 定项目根 + `~/.duo/AGENTS.md` 用户全局；两文件均可缺省
- [x] 64KB 总预算：按序拼接、超限截断尾部、附加"（内容超预算已截断）"注明
- [x] 注册为 `agents-md` 片段（source 名），组装顺序 = 用户配置片段之后（yml 行序：prompts → agents-md → skills）
- [x] 测试（ChatRequest 捕获 seam）：两文件齐 / 仅项目根 / 仅用户全局 / 全缺（不注册片段）/ 超预算截断——实现调整：组装断言由 ChatRequest 捕获承担的部分落在工单 05 的装配集成（本单以 AgentsMdTest 5 例锁定装载与拼接语义）
- [x] 文档同步：CHANGELOG 未发布段记 AGENTS.md 注入；词汇表词条随 M7 收口统一落 CONTEXT.md

## 实现记录（2026-09-13）

- 契约：AgentsMd（load(cwd, userGlobal, budget)，candidates 顺序 = 用户全局 → 项目根）+ AgentsMdPlugin（config {budgetChars} 可省即 64KB；两文件全缺不注册片段）
- yml 行序即组装序：prompts（用户指令）→ agents-md（项目约定）→ skills（技能清单）——行序控制注册序，注册序即 compose 序
- 测试：AgentsMdTest 5 例（按序拼接 / 单文件 / 全缺 / 截断注明 / .git 定根）；过程中修了预算测试的断言算术（截断注解是附加的，不占预算）

spec：[../spec.md](../spec.md)。嵌套目录链与 fs 操作后增量发现在 Out of Scope（M9+）。已知副作用：duo-harness 本仓库内运行会注入本仓库自己的 AGENTS.md（dogfood 噪声）——验收建议临时目录。
