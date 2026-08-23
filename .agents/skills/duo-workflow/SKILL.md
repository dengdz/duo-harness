---
name: duo-workflow
description: duo-harness 开发流程总规范——任何开发类请求的入口：需求三级分流（L1 大需求 / L2 常规功能与 bug / L3 微调）、各级步骤与挂接技能、提交前核对清单、工程设施指引。用户说"开发一个功能"、"修一个 bug"、"调整页面不一致"、"实现某种效果"、"提交一下代码"时先读本技能定级路由。
---

# duo-harness 开发流程总规范

收到开发类请求，先判级再动工。版本号与分支规则统一在 [版本号规范](references/版本号.md)。

## L1 大需求

判定：新模块 / 架构变更 / 影响公开 API。

流程：设计访谈 → spec/ADR → 拆工单 → 逐单实现 → 审查 → 验收 → 版本合并。

1. 设计访谈段**模型不可自动触发**，提示用户显式调用：`/grill-me`（要沉淀决策记录用 `/grill-with-docs`，产出 CONTEXT.md + docs/adr/）
2. 结论成型后提示：`/to-spec`（落 `.scratch/<feature>/spec.md`）→ `/to-tickets`（拆 `.scratch/<feature>/issues/NN-*.md` 工单）
3. 执行段提示 `/implement`，一单一会话，会话间用 `/handoff` 交接；核心逻辑走 tdd 红绿循环（可自动触发）
4. 审查：duo-code-review + code-review 双轴（Standards/Spec）+ ocr 行级意见
5. 合并推送按 duo-release-workflow（分支名 = 版本号）

## L2 常规功能 / bug

判定：现有模块内增能力或修缺陷，不动公开 API 结构。

流程：轻量确认 → 分支 → 实现 + 测试 → 审查 → 验收 → 提交。

- 轻量确认：意图 + 影响面，2~3 个问题，确认后动工，不展开成完整访谈
- bug 必须先诊断根因（diagnosing-bugs），修复必须带回归测试；核心逻辑默认 tdd
- 分支与版本规则：duo-release-workflow 与 [版本号规范](references/版本号.md)；提交：git-commit-gen（conventional commits，中文描述）

## L3 微调

判定：样式 / 文案 / 页面不一致 / 小效果，无逻辑面。

流程：定位 → 直接修改 → 验证 → 提交。

- UI 改动：改前截图留档，改后用 browser-use 截图对比，视觉确认才算完成
- 非 UI：跑覆盖该处的最窄测试

## 提交前核对清单

任何提交（含独立的提交请求）动 git 前：

1. **版本上下文**：读 CHANGELOG.md 顶部版本段；用户可见变更在未发布段补条目
2. **分支归属**：当前应在版本分支上；在 main 上（或仓库尚无提交）时，先按 [版本号规范](references/版本号.md) 建/切版本分支，不往 main 堆开发提交
3. 以上通过后走 git-commit-gen（暂存分组确认，未经确认不 commit）

## 工程设施

本地 issue tracker 与领域文档（术语表 / ADR）的使用规范见 [duo-tracker](../duo-tracker/SKILL.md)。
