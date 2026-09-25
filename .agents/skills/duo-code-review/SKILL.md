---
name: duo-code-review
description: 四轴审查即修复：Standards/Spec/行级/Java 规范并行审查，统一修复，测试收口。触发：审查改动 / 对比分支 / 查暂存变更。
---

# duo-harness 审查即修复流程

四轴并行审查、统一修复、测试收口。开工前先读 `.scratch/review-log.md`（不存在则视为无历史），把已知模式纳入四轴重点。红线遵根 AGENTS.md（红线 1：未经用户确认不 commit）；架构类分歧先摆方案给用户确认再动手。

## 流水线（顺序固定，逐步推进）

### 第 0 步：基点预检（fail-fast）

`git rev-parse <基点>` 成功且 `git diff <基点> --stat` 非空（工作树审查用 `git diff HEAD --stat` 加未跟踪文件清单），才进入四轴派发；坏基点或空 diff 在此报错停止——失败发生在派发之前，止于主审。

完成判据：基点 SHA 与 diff --stat 已在对话出示，且 stat 非空。

### 第一阶段：四轴并行审查 → 合并报告 → 修复

1. **四轴并行审查**（四个**新上下文子代理**同时跑——独立性是产出率的来源，禁止本 agent 亲自执行任一轴）：
   - **Standards 轴**：Call the Skill tool with "code-review"，取其 Process 节的标准源清单与 Fowler 坏味道基线（12 条），随 brief 原文下发给 Standards 子代理（子代理无法自行加载技能，材料由主审随 brief 供给）。
   - **Spec 轴**：同上取其 Spec 轴的 brief 要求；Spec 来源 = `.scratch/<feature>/spec.md` 与对应工单文件，发现逐条引 spec 原文；找不到 spec 如实报"无 spec 可对照"。
   - **行级规则轴**：Call the Skill tool with "open-code-review-delegate"，以 `ocr delegate preview` / `ocr delegate rule` 的产物原文为文件名单与规则清单随 brief 下发，行级审查交给独立子代理执行——brief 携固定指令：「逐文件对照规则清单出候选发现，先列全再过滤，宁多勿漏」。行级名单只收主代码（测试与文档由 Standards / Spec 轴覆盖），报告覆盖率按 `git diff --stat` 全集口径标注。
   - **Java 规范轴**：Call the Skill tool with "duo-java-review"，按其 L1 按需清单（programming / exception-log / test-security；mysql-project 默认跳过）加载规则、按其 report-template 输出 BLOCKER/CRITICAL/MAJOR 分级发现。三条 brief 约束：① 适用子集裁剪——MySQL/服务器端章节默认跳过，仓库惯例与新范式（虚拟线程/record/switch 表达式）优先，手册未覆盖新范式的条款不适用；② 反条款倾销——先列候选再过滤，不适用条款写出丢弃理由；③ 分工——只报「规则编号 + 条款原文」类发现，与 Standards 轴重叠的由主审合并处置。范围 = 全部 Java diff（含测试类，走 rules-test-security）。
   - fixed point 取改动前的提交（单工单审 `HEAD~1` 或工作树基点 HEAD，批次/里程碑审分支基点或用户指定点）——已经第 0 步预检。

   完成判据：四份子代理报告回收，每份含候选全列与过滤后结论。
2. **brief 填空模板（派发前逐项填写，brief 从轴定义机械派生）**：
   - **基点**：改动前提交 SHA 或「工作树 vs HEAD」，写明获取命令（`git diff HEAD` / `git diff <基点>`）。
   - **文件名单来源**：行级轴 = `ocr delegate preview` 产物原文；Standards / Spec / Java 规范轴 = `git diff HEAD --stat` + 未跟踪文件清单。
   - **规则清单来源**：行级轴 = `ocr delegate rule` 产物原文随 brief 下发；Java 规范轴 = `duo-java-review/reference` 的 L1 按需清单。
   - **范围口径**：行级轴 = 主代码不含测试/文档，覆盖率全集口径；Java 规范轴 = 全部 Java diff 含测试类。
   - **输出与词限**：候选全列不限词；过滤后报告 ≤400 词；输出格式各行按其技能/模板要求（Java 规范轴须加载 report-template）。
   - **固定指令**：先列全候选再逐条过滤误报，宁多勿漏；只报告不修改。

   完成判据：六要素逐项填写完毕，brief 全文在对话中可指认。
3. **生成审查报告**（模板见下，标注"第 N 轮·四轴"，Standards / Spec / 行级规则 / Java 规范四轴原样分列；重复发现由主审标注合并处置）。

   完成判据：报告已按模板生成并标注轮次。
4. **基于报告修复**：明确缺陷直接修并带回归测试；架构/设计类 blocker 把方案摆给用户确认后再动手。

   完成判据：全部发现已处置（修复 / 记档 / 豁免判定），或 blocker 方案已摆给用户。
5. **修复复核**：修复引入阻断级新代码改动时复跑四轴（基点改为本次报告落盘点）；小修由测试收口覆盖。

   完成判据：阻断级改动已复跑四轴，或已判定为小修并说明。

### 收口（证据先行）

6. **四轴路径出示**：逐项出示四轴报告的落盘路径（Standards / Spec / 行级规则 / Java 规范），每条路径指向已存在的文件；缺任何一轴，列出缺失轴并停止，总结留待路径齐备后输出。
   完成判据：四条路径在对话中可指认，对应文件存在。
7. **豁免判定（两条件合取）**：豁免行级轴或 Java 规范轴成立 = ① 理由已写入工单审查报告与 `.scratch/review-log.md` 两处（出示两处位置）② 用户在本轮明确确认（对话可指认）。缺任一条件即视为未豁免；每次豁免独立判定，先前豁免不构成先例。
   完成判据：豁免成立时两处记档位置与用户确认均可指认；未豁免时该轴报告路径在第 6 步清单中。
8. **测试收口**：跑覆盖该 diff 的最窄测试，全绿即本次审查结束；用户点名复核才再来一轮。
   完成判据：最窄测试命令与全绿结果已出示。
9. **持久化**：四轴报告落盘 + 审查总结追加（见下两节）。
   完成判据：四轴报告文件与 review-log 条目均已落盘。

## 报告模板（每轮一份）

```markdown
## 审查报告（第 N 轮·四轴）：<范围>

**覆盖**：N 个文件 = 已审 X + 跳过 Y（覆盖率 Z%）；跳过逐个附理由

### 阻断
- **`path:line`** — 问题 / 影响 / 证据

### 建议
- **`path:line`** — 问题（可选修法）

### 测试覆盖
- 变更涉及的核心逻辑是否有对应测试用例？
- 新增分支/边界情况是否有测试覆盖？
- 测试用例是否充分？若不足，列出需补充的场景
```

报告尾部保留 Standards / Spec / 行级规则 / Java 规范四轴原样分列；报告只收未被绿灯测试覆盖且抓得住上下文的问题。

## 报告持久化

- 单工单审查 → 并入工单（`.scratch/<feature>/issues/NN-*.md`）末尾「审查轮（日期）」小节；批次 / 里程碑 / 分支审查 → `.scratch/<feature>/reviews/YYYY-MM-DD-<范围>.md`。
- 落盘内容 = 对话里给出的报告全文；修复处置在同一处续写，形成"发现 → 处置 → 复核"闭环。

## 审查总结（持续维护文档）

`.scratch/review-log.md`（与 bug-log 同型）在每次审查收口时追加一节：

- 日期 / 范围 / 四轴发现与修复计数；
- 模式化问题：本仓库反复出现的坑与本次新发现的模式；
- 对后续开发的优化建议：可沉淀为约定、检查项或新工单的。

（技能演进记录见 [references/experience.md](references/experience.md) 的变更历史节。）
