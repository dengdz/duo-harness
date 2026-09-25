# 09: duo-tracker + duo-doc-standards + duo-prose-standard + duo-project-structure 改写

## What to build
按规范改写四技能：description 三规则重写（tracker 的身份句删除、触发词独立成句尾部）；`../../../docs/agents` 三级外链改显式指引；表格化分支保留（规范明示的强项）；duo-doc-standards 的机械核对命令（sidebar 对账）保持并校验有效；duo-project-structure 判例节保留；duo-prose-standard 的排除/范围语句补 fail-fast 形态。四技能无相互依赖，可一批完成。

验收标准（重放 seam）：手写一张小工单走 duo-tracker 新流程；一次文档归属咨询走 doc-standards 新流程并核对其 sidebar 对账命令；一次结构评审走 project-structure 新流程。

## Blocked by
04

## Status
done（2026-09-25；四技能改写落地，"不推倒"边界经 git diff 逐行核验；三 seam 完成——手写工单 13 / 归属咨询实测 / 结构评审判定）

## Checklist
- [x] 四技能 SKILL.md 按规范改写（tracker 181→72 字 + 外链显式指引 + 门禁形态；doc-standards 144→77 字 + 三处显式调用 + 对账差值判据化；prose-standard 191→80 字 + fail-fast 两态 + 协作节增规范指针行【ADR-0027 决策一，阻断抓回】；project-structure 159→79 字 + 结构审查 fail-fast 边界 + 判例节 5 条无损；末尾换行符回归修复）
- [x] 重放：手写工单——工单 13（prose 指针行补录）以 tracker"顺手记一张小工单"分支真实手写（issue-tracker 模板三要素），修复同 diff 落地
- [x] 重放：文档归属咨询——"docs/research/mattpocock-skills/ 是否上站"按 doc-standards 上站范围条款判定（srcExclude 域，不上站）；对账命令实测 27 文件 + 28 链接，差值=1 ✓
- [x] 重放：结构评审——.scratch/c1-skill-writing/ 目录归属判定（spec + issues 平铺同构 issue-tracker 惯例，可辩护形态，无分组错误）

## 审查轮（2026-09-25）

**覆盖**：4 个文件 = 已审 4——四技能 SKILL.md；修复阶段扩展 prose-standard（阻断补录）与规范（4 处锚点快照化/节名化）。行级/Java 轴空集执行；第 0 步预检：SHA 7382b93 + stat 4 文件出示。

**四轴**（Standards / Spec 双子代理）：保命题逐分句全过（四技能 diff 逐行核验均在授权范围：prose/structure 仅 description 与指定节）；表格化分支/判例节/对账命令三保留资产实测无损；四 description 终值 72/77/80/79。

- **阻断（已修）**：prose-standard 缺《技能写作规范》指针行（ADR-0027 决策一"改写段同 diff"，工单 04 留给改写段，本单漏加）——协作节补指针行，并衍生手写工单 13（即 seam 1）。
- **MAJOR（已修 3）**：文件末尾换行符静默丢失（Write 工具回归，od 实测）；project-structure:25 弱指针改显式调用；规范 3 处锚点随改写漂移（tracker :38/:132、doc-standards :70、project-structure :62）节名化/快照化。
- **MINOR（已修 2）**：对账命令写死"N=24"改差值判据（防过期，完成判据"差值=1"）；doc-standards trim 引用第二处（:46）同步显式调用。
- **裁定（2）**：AGENTS.md 路由行无需同 diff（本单仅词面收敛未增删触发分支——规范 1.4 只约束增删；同概念不同词面已标注）；"fail-fast"一词两义（报告即停 vs 先确认边界）——语义同族（异常路径前置处理），术语表暂不拆词。
- **测试覆盖**：四 description wc 终值 + 对账命令实测 + od 换行检查 + docs:build。零 Java 触碰。

**处置汇总**：发现 8 项（合并去重）→ 修复 8（阻断 1 + MAJOR 3 + MINOR 2 + 裁定记录 2）。终值：tracker 72 / doc-standards 77 / prose-standard 80 / project-structure 79 字。
