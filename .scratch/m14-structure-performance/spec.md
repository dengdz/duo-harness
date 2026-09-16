# M14 Spec：结构治理与性能——agent 拆包、事件快照零拷贝、测试密闭化与 limitations 定稿

> ADR-0014（2026-09-16 grill 裁定：四问 + 80K 实测校准）为性能分支直接依据；拆包 / 密闭化 / limitations 定稿属工程债清偿，无争议不立 ADR。基线 0.8.0 已发布；目标版本 **0.9.0**（开工首日切 `0.9.0` 分支）。理解关卡（R6）按用户 2026-09-16 裁定**延后补考**，不进本里程碑范围、不构成阻拦。

## Problem Statement

框架维护者的四笔债（roadmap M14 定位：为 M15 subagent / 生态期打地基）：

1. **agent 根包超载**：27 类平铺一个包（duo-project-structure 阈值 ~10），循环、治理、技能、计划、提示五个域混杂——M15 要在 agent 域扩展 subagent，届时再拆，搬移面与审查面翻倍。
2. **事件读取的结构性浪费**：`events()` 每次调用全量拷贝（读侧每次 O(n)），分页定窗三趟遍历（其中一趟是恒等冗余）——limitations M13#3 白纸欠账（"增量投影 / O(n) 单趟优化属 M14"）。
3. **装配测试非密闭**：两个装配用例经 `LlmConfig.load()` 读本机真实 `~/.duo/config.yml`（测试注释自披露"依赖本机真实配置"）——换机器、改配置都可能无端红。
4. **limitations 陈旧与缺失**："M7（未发布）/M8（未发布）"标题陈旧（实查 CHANGELOG 已随 0.3.0 发布）；M9-M11（0.4.0-0.6.0）期间留档的已知限制从未入册。

## Solution

四分支独立可分批：**A** agent 按域拆四个子包（governance / skills / plan / prompt），循环核心与装配辅助留根包，纯机械搬移零行为变更；**B** 事件快照改写侧重建读侧共享（CoW 零拷贝）+ 消息窗口消冗余趟次，基准固化落档（ADR-0014，达标线：80K 事件单次"定窗+投影"≤ 15ms）；**C** DuoHome 解析链加系统属性一级（`duo.home` > `DUO_HOME` > `~/.duo`），两处非密闭装配测试改设属性 + 临时目录配置，彻底脱离本机状态；**D** limitations 标题改版、M9-M11 补录、M13#3 随 B 同 diff 消账。

## User Stories

1. As a 框架维护者, I want agent 根包按域拆为治理/技能/计划/提示四个子包, so that 每个包职责单一、类数达标，新域（如 subagent）有明确落位
2. As a 框架维护者, I want 工具循环核心类留在根包作为对外门面, so that web/cli 等消费方的认知路径不变
3. As a 框架维护者, I want 拆包纯机械零行为变更（只搬类 + 改 import）, so that 全量测试绿即等价证明，不引入行为风险
4. As a 框架维护者, I want `events()` 读侧零拷贝（共享不可变快照）, so that 投影/回放/分页不再每次付出全量拷贝成本
5. As a 框架维护者, I want 消息窗口计算消除冗余趟次, so that 分页定窗不再做恒等重复的第三次全量遍历
6. As a 框架维护者, I want 80K 事件性能基准固化落档, so that 后续里程碑有回归参照，性能退化有数字可指
7. As a 框架维护者, I want 并发安全语义（读侧稳定视图、遍历无 CME）在优化后原样保留, so that Web 回放与 agent 流式追加并发的既有保证不被削弱
8. As a 贡献者, I want 装配测试完全脱离本机 `~/.duo` 配置, so that 任何机器上 `mvn test` 行为一致、无环境噪音
9. As a 贡献者, I want DuoHome 支持测试注入（系统属性优先级最高）, so that 密闭注入不必 hack 环境变量
10. As a 部署者, I want DuoHome 的 `DUO_HOME` 环境变量重定向语义不变, so that 0.8.0 及之前的部署方式零影响
11. As a 维护者, I want limitations 无"未发布"陈旧段, so that 已发布版本的限制清单位置准确
12. As a 维护者, I want M9-M11 期间留档的已知限制补录入册, so that limitations 恢复"唯一权威来源"的完整性
13. As a 维护者, I want M13#3（投影优化属 M14）在优化落地同 diff 内删除, so that 限制清单与代码事实同步（文档与代码同一 diff 红线）
14. As a 使用者, I want 0.9.0 升级后对话/审批/分页/切换行为与 0.8.0 完全一致, so that 技术债清偿不伴随任何使用面变化

## Implementation Decisions

**分支 A——agent 拆包（Q2 裁定：方案一）**

- 新建四个子包，类归属：`agent.governance`（ContextGovernance / ContextBudget / ContextOccupancy）、`agent.skills`（Skill / SkillRegistry / SkillTool / SkillsPlugin）、`agent.plan`（PlanMode / ExitPlanModeTool）、`agent.prompt`（PromptFragment / PromptPlugin / PromptRegistry / PromptsView / AgentsMd / AgentsMdPlugin）。
- 根包保留循环核心与装配辅助：ChatAgent / AgentListener / AgentReply / ToolInvocation / AuditingAnswerer / SessionTitles，既有 `internal/` 与 `presenter/` 原位不动。
- 纯机械搬移：只动 package 声明与 import，零逻辑改动；波及面 web/cli 两模块（example 对 agent 零 import，已实查）。
- 拆包后根包 6 类（+package-info）、各子包 2~6 类，全部达 duo-project-structure 阈值；拆包后模块图落验收件。

**分支 B——事件快照零拷贝与单趟窗口（Q3/Q4 裁定：方案一 + ADR-0014）**

- Session 维护 volatile 不可变事件快照引用：append 锁内追加后重建（写侧 O(n)），`events()` 返回共享引用（读侧 O(1)）；对外 javadoc 语义（稳定视图、并发隔离）不变，实现语义更新为 CoW 共享。
- 消息窗口：利用 `earlier ≡ tailStart` 恒等式消第三趟；起点定位用容量 O(max) 的投影下标滑动缓冲单趟完成（允许至多两趟兜底）；边界语义（消息边界对齐、tool/result 回折、未截断保留前导非投影事件）逐字不变，既有窗口用例全量兜底。
- 基准固化：80K 事件程序化生成 + 计时的探针默认跳过（不拖全量套件），基准数字落 ADR-0014 与验收件；达标线 15ms（现状 17-26ms）。
- 投影结果缓存 / 增量投影记 backlog（Q3 裁定拒绝项）。

**分支 C——装配测试密闭化（实现层代定，无产品取舍）**

- DuoHome 解析链加一级：系统属性 `duo.home` > 环境变量 `DUO_HOME` > 缺省 `~/.duo`；`DUO_HOME` 语义与优先级不变（部署零影响），系统属性为测试注入专用口。
- 两处非密闭装配用例改为：套件前置设系统属性指向临时目录 + 写入最小 `config.yml`，套件后置清除；测试不再依赖本机任何真实状态。
- 密闭化后删除两处"依赖本机真实配置"的先例披露注释（披露的对象消失）。

**分支 D——limitations 定稿（实现层代定）**

- "M7（未发布）"→"M7（0.3.0）"、"M8（未发布）"→"M8（0.3.0）"（已实查 CHANGELOG 0.3.0 段覆盖 M6/M7/M8）。
- M9-M11（0.4.0 / 0.5.0 / 0.6.0）补录段：实施时从 M9-M11 的 spec / ADR / 工单系统搜"留档 / 有意取舍 / 不做"清单化入册（已认知候选：M11 idle 无热恢复）。
- M13#3（全量投影优化属 M14）随分支 B 完成同 diff 删除；CHANGELOG 同 diff 记账。

**实施顺序建议**：C → B → A → D（先小后大、性能数字先行、拆包搬家最后、文档收口垫后）；每分支独立提交，按批次推进（批次划分随 /to-tickets 落）。

## Testing Decisions

- **零新 seam**：四分支全走既有测试面——拆包随类搬移既有测试（改 import）；性能走 Session 公共 API seam（`events` / `deriveMessages` / `tailWindow` / `windowBefore`，先例 SessionTest 窗口 10 例）；密闭化走既有装配 seam（先例 WebPluginAssemblyTest / AgentReplMainTest，只换配置注入方式）；limitations 是文档对账无测试面。
- 拆包等价性证明 = 全量测试绿 + 零非 import 逻辑 diff。
- 性能用例只测外部行为：快照不可变性、并发隔离、窗口边界语义；耗时断言只进默认跳过的基准探针，常规套件不断言绝对耗时（避免环境抖动假红）。
- 密闭化验证：两用例在清空 `DUO_HOME` / 无 `~/.duo/config.yml` 的环境假设下仍绿（临时目录自足）。

## Out of Scope

- 投影结果缓存 / 增量投影（backlog，ADR-0014 拒绝项：等可感卡顿再立项）
- 理解关卡清零（用户裁定延后补考，路线图 R6 欠账保留记录）
- /compact 手动压缩（归 M16 斜杠命令注册表）
- 分页页长配置化（limitations M13#2 维持：进配置即承诺契约）
- session 模块以外的性能项（前端 DOM 窗口化 S5 等维持 roadmap 原判）
- 新功能、新依赖（零依赖增量）

## Further Notes

- **grill 期实测基线**（80K 事件、20 轮均值、M 系列 Mac）：`events()` 0.13ms / `deriveMessages()` 8.78ms / `tailWindow(50)` 8.31ms；一次刷新链路投影总账 ~17-26ms，端到端不可感——B 的定位是债务兑现 + 防回退，不是可感提速（ADR-0014 引言已明示）。
- 拆包波及面实查：web 引 agent 6 类、cli 引 10 类、example 零引用；全部经 presenter / 根包门面，无深引 internal。
- 本里程碑全程在 `0.9.0` 版本分支进行，验收后合 main 发布（红线 7）。
