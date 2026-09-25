# 02: memory 域服务与注入（记忆读路径）

## What to build
记忆本的最小读路径端到端：项目级 `.duo/MEMORY.md`（不入 git）作为记忆本，每次模型请求把其内容注入上下文（meta_user 通道：system 规范段 + 记忆索引），带预算上限；文件不存在时静默降级零报错。交付后的可感行为：用户手写一条记忆进 MEMORY.md，开新会话，AI 无需任何操作即"知道"这条内容。

设计要点（工单期按探测工单 10 细化）：MEMORY.md 服务走既有服务域架构；注入预算上限（超限截断策略实现期定）；`.gitignore` 增加 `.duo/MEMORY.md`（个人记忆不入库——spec 决策四）。

## Blocked by
01

## Status
in-progress

## Checklist
- [x] MEMORY.md 读写服务（服务域接入，架构一致）
- [x] 每请求注入（meta_user + 预算上限 + 超限策略）
- [x] 文件不存在 / 为空时静默降级（未启用用户零感知）
- [x] `.gitignore` 增 `.duo/MEMORY.md`
- [x] 端到端演示：手写一条记忆 → 新会话模型可见（自动化层已证；用户手动演示见 Comments 复跑命令）
- [x] tdd 红绿循环（seam 先与用户确认）；术语表「memory 记忆本」词条同 diff

## Comments
- 2026-09-25：实现完成。**TDD seam 选择**（spec 授权工单期定，自主模式按既有先例定）：①MemoryBook 纯函数（读载/预算截尾/三态降级）②ToolCallingAgent.buildRequest 注入（捕获式适配器断言请求视图）③Boot.from 装配（服务发布 + 规范段注册 + 预算配置）。11 用例先行红后绿。
- 2026-09-25：**交付形态**：`agent.memory` 新包（MemoryBook "memory" 服务 / MemoryPlugin / package-info）；meta_user 通道 = 现读内容包 `<memory>` 标签附时效免责语，以 user 角色置于消息序列最前（请求视图专用不落会话日志，治理投影之后组装不被压缩吞）；预算 16KB 缺省（budgetChars 可配）截尾+标注；规范段 memory-guide 随记忆本在场注册（读路径语义，写协议文本随工单 03 增补）。CLI/Web/headless 三消费位接线（optionalInject + hasService + 视图接口，服务名与视图方法名逐字一致）；子代理走旧重载零注入（任务域隔离，符合 ZCode 同构）。测试 948 全绿（0 失败，2 既有 skip）。
- 2026-09-25：**Status 停 in-progress（缺用户手动验证）**。手动验收件（复跑命令，跑完可删）：`mkdir -p .duo && printf '- 这个项目用 Maven 构建，回复保持中文\n' > .duo/MEMORY.md`，按运行Demo.md 命令块**整块复制**启动 CLI，新会话问「记忆本里记了什么」——模型应直接答出两条内容（注入生效）；`rm .duo/MEMORY.md` 清场。端到端的自动化覆盖在共享执行链层（MemoryInjectionTest 断言请求视图含 `<memory>` 段、MemoryPluginTest 断言装配发布），CLI/Web 模块级重复测试不另设（spec Testing Decisions 的 web/CLI 端到端由本手动验收承担）。
- 2026-09-25：**用户手动验收（第一轮）记录**：注入通道与现读语义**得证**——次问"最喜欢的数字"模型零工具调用、直接引用"本轮注入的 `<memory>` 段"答出另终端刚改的 42（未重启即生效）；负例（空文件）无报错静默降级通过。同时暴露 **BUG-20260925-02**：首问模型先 `[调工具] read` 文件再答——guide 信息性措辞不构成采信约束、header"可能过时"被模型反向归因到本轮新鲜注入段。已修（guide 增指令句 + header 锚定"本轮最新内容"，Memory 三套件回归绿），档案 `.scratch/bugs/BUG-20260925-02.md`。验收步骤缺陷记档：rm 清场应明示"在终端执行"（用户在 CLI 输入被模型接手走审批，文件被清空未删除）。
- **待复验（采信层，mock 原理上锁不住）**：恢复记忆内容 → 重启 CLI → `/new` → 问"我的记忆本里记了什么？"→ 判据 = **不出现 `[调工具] read`**，直接作答。通过即工单转 done + bug 转 done。Status 维持 in-progress。
- 2026-09-25：**审查记档（第 1 轮·四轴，报告见下）**。挂账/豁免处置：①guide 规范段=装配时快照判定 vs 注入=每请求现读的不对称已 JavaDoc 记档，工单 03 写路径落地时收敛时机；②budgetChars 非法值静默回退缺省（与呈现位 parse* 抛错惯例相悖，但与直接先例 AgentsMdPlugin 逐字同款）——仓库级统一化议题不本单翻案；③budget 解析/截尾逻辑与 AgentsMd 同形重复——第 3 消费方出现时提取共享；④装配 13/15 参线性增长——工单 03 前评估装配参数对象。

## 审查轮（2026-09-25·第 1 轮·四轴）

**覆盖**：15 文件 = 已审 15 + 跳过 0（行级轴只收主代码 7 文件，测试/文档由 Standards/Spec/Java 轴覆盖）

### 阻断
- **`docs/04-架构/模块划分.md:17,50`** [Standards] 新增 `agent.memory` 子包未同步模块角色行与子包清单（红线 3）→ **已修**：两处各补 memory 词条
- **`MemoryBook.java:69`** [Java L-06 BLOCKER] warn 用 e.toString() 丢堆栈 → **已修**：改尾随异常对象 `LOG.warn("…（{}）", file, e)`（与 PresenterAssembly 先例一致；review-log SLF4J 陷阱两形态均避开——占位符数与参数数一致、堆栈保留）

### 建议
- **`MemoryPlugin.java:50-52`** [Java C-01 MAJOR] "budgetChars" 字面量两处 → **已修**：提私有常量 BUDGET_CHARS_CONFIG；测试 `16 * 1024` → `MemoryBook.DEFAULT_BUDGET_CHARS` 引用
- **guide 注册时机契约未写明** [Standards/Spec/行级三轴同报] → **已修**：MemoryPlugin.apply 注释补「装配时刻快照判定 vs 每请求现读」双时机契约 + 工单 03 收敛去向
- **`MemoryPluginTest` 「属性覆盖先例」命题无实据** [Standards] → **已修**：改「本套件 user.dir 覆盖约定」
- **`MemoryBookTest` 套件叙述 5 用例 vs 实 6** [Standards，review-log 已知模式] → **已修**：改 6
- **`.gitignore` 未锚定根** [Spec] → **已修**：改 `/.duo/MEMORY.md`（项目级精确对齐）
- **HeadlessRunner 走旧重载零注入但 yml 已宣告 memory 行** [Spec] → **已修**：headless 接线（判存 + 8 参工厂重载），配置宣告与行为一致，HeadlessRunnerTest 7 用例绿
- 记档不修：装配参数线性增长（显式装配风格可辩解，工单 03 前评估参数对象）/ budget 解析与截尾同形重复（第 3 消费方出现时提取）/ 代理对截断美化项（16KB 边界至多 1 畸形字符）/ budgetChars 下限 MemoryBook ≥1 严于 AgentsMd ≥0（新代码从严，方向正确）

### 测试覆盖
- 核心逻辑三 seam 全有测试：读载矩阵 6 用例（缺席/空白/现读/截尾/IO 降级/meta_user 组装）、注入 3 用例（序首+不落会话/缺席零注入/未装配零注入）、装配 3 用例（服务+规范段/静默降级/预算配置）
- 边界：memory+reminder 共存（reminder 尾部追加 vs memory 序首，位置正交）未单独用例——注入位次已由序首断言锁定，风险低，记档
