---
name: duo-workflow
description: 开发总入口：三级分流（L1 大需求/L2 功能 bug/L3 微调）与各级收口（wrap-up）判据。触发：加功能、修 bug、实现效果、调整页面、微调。
---

# duo-harness 开发流程总规范

收到开发类请求，先判级再动工。版本号与分支规则统一在 [版本号规范](references/版本号.md)。

## 第一步：判级（三级分流）

| 级 | 判定 | 首个落盘物 |
|---|---|---|
| L1 大需求 | 新模块 / 架构变更 / 影响公开 API | `.scratch/<feature>/spec.md` |
| L2 常规功能 / bug | 现有模块内增能力或修缺陷，不动公开 API | 版本分支 + CHANGELOG 条目 |
| L3 微调 | 样式 / 文案 / 页面不一致 / 小效果，无逻辑面 | 单提交 |

- 判级依据随首个落盘物留痕：L1 写入 spec 背景，L2 并入 CHANGELOG 条目，L3 并入提交信息。
- 介于两级之间：按较高层级走，动工回复首句说明理由；用户点名较低级，以用户为准。
- 完成判据：级已确定、依据留痕位置已明确、本级流程已进入第一步。

## L1 大需求

1. **设计访谈**（用户门禁）：出示 `/grill-me`（要沉淀决策记录用 `/grill-with-docs`，产出术语表 + ADR）并停止，等用户键入——用户键入前不进入下一步。
   完成判据：用户已键入命令，访谈结论（裁定清单）在对话中成型。
2. **spec / 工单**（用户门禁）：出示 `/to-spec`（落 `.scratch/<feature>/spec.md`）与 `/to-tickets`（拆 `.scratch/<feature>/issues/NN-*.md`）并停止，等用户键入。
   完成判据：spec.md 存在，且每张工单含 What to build / Blocked by / Status 三要素——缺一即未拆完。
3. **逐单实现**（用户门禁）：出示 `/implement`（一单一会话，会话间 `/handoff` 交接）并停止，等用户键入；核心逻辑测试先行：Call the Skill tool with "tdd" 进入红绿循环（测试 seam 先与用户确认）。
   完成判据：工单 Status 按 docs/agents/issue-tracker.md 的 Status 值域流转，无悬置。
4. **审查**：Call the Skill tool with "duo-code-review"，四轴并行审查即修复流水线（Standards / Spec / 行级 / Java 规范）。
   完成判据：四轴报告齐全且落盘（duo-code-review 收口节的判据）。
5. **验收**：Call the Skill tool with "duo-acceptance"。
   完成判据：用户亲手运行验收件并确认（done 的定义）。
6. **理解关卡**：Call the Skill tool with "duo-comprehension"（先学后考）。
   完成判据：`<feature>/comprehension.md` 凭证 ≥90 分——未获通过不拆下一阶段工单。
7. **版本合并**：Call the Skill tool with "duo-release-workflow"（分支名 = 版本号；main 只收验收合并，根 AGENTS.md 红线 7）。
   完成判据：合并与推送经用户确认完成。

## L2 常规功能 / bug

1. **轻量确认**：意图 + 影响面，2~3 个问题，确认后动工——不展开完整访谈。
   完成判据：用户已答复确认，判级依据已并入 CHANGELOG 条目。
2. **bug 先诊断**：Call the Skill tool with "diagnosing-bugs" 定位机制层根因；修复必须带回归测试。
   完成判据：根因写到机制层，回归测试先红后绿。
3. **分支与实现**：核心逻辑默认 Call the Skill tool with "tdd"（seam 先确认）；分支与版本规则见 [版本号规范](references/版本号.md)，版本合并走 duo-release-workflow。
   完成判据：变更在版本分支上，测试全绿。
4. **审查 → 验收 → 提交**：审查与验收同 L1 第 4–5 步；提交走 Call the Skill tool with "git-commit-gen"（conventional commits，中文描述）。
   完成判据：收口判据通过 + 提交经用户确认。

## L3 微调

1. **定位 → 修改**：UI 改动改前截图留档；非 UI 定位最窄改动点。
   完成判据：改动仅触及样式 / 文案 / 展示层，无逻辑面变更。
2. **验证**：UI 用 browser-use 截图对比；非 UI 跑覆盖该处的最窄测试。
   完成判据：UI 视觉对比经用户确认（红线 5：视觉确认才算完成）；非 UI 最窄测试全绿。
3. **提交**：提交信息并入判级依据；提交走 Call the Skill tool with "git-commit-gen"。
   完成判据：提交经用户确认，提交信息含判级依据。

## 提交前核对清单

任何提交（含独立的提交请求）动 git 前：

1. **验收件就绪**：功能类提交已按验收步备好三件套；用户已运行确认（或明确说先提交后补验）。完成判据：三件套路径可出示。
2. **版本上下文**：读 CHANGELOG.md 顶部版本段；用户可见变更在未发布段补条目（L2/L3 判级依据并入）。完成判据：条目已写入。
3. **分支归属**：当前应在版本分支上；在 main 上（或仓库尚无提交）时，先按 [版本号规范](references/版本号.md) 建/切版本分支。完成判据：`git branch --show-current` 输出版本分支名。
4. 以上通过后 Call the Skill tool with "git-commit-gen"（暂存分组确认，未经确认不 commit）。

## 工程设施

本地 issue tracker 与领域文档（术语表 / ADR）的使用：Call the Skill tool with "duo-tracker"。

**路由同步**：根 AGENTS.md 的路由表与各技能 description 是同一路由的两个视图——增删触发分支必须同一 diff 改两处。
