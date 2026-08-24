# AGENTS.md — duo-harness 路由

duo-harness：Java 多模块（Maven）+ 内嵌 Web UI 的 agent harness，根包 `dev.duo.harness`。本文件只做"你说什么 → 走哪里"，细则都在对应 skill 里。

## 你会说 → 走哪里

| 你说 | 走 |
|---|---|
| 开发一个功能 / 修 bug / 大需求 / 调整页面 / 实现效果 | duo-workflow（三级分流） |
| 提交一下代码 | duo-workflow 的提交前核对 → git-commit-gen |
| 推一下 / 推送前检查 | duo-pre-push-checks |
| 审查一下改动 | duo-code-review |
| 清理一下注释 | duo-trim-cot-leakage |
| 建个模块 / 这个类放哪 | duo-project-structure |
| 写 spec / 建工单 / 记 ADR / 更新术语表 | duo-tracker |
| 看一看 DSH 某功能 / 看看某项目怎么做的 | duo-research |
| 这个文档放哪 / 整理文档 | duo-doc-standards + duo-prose-standard |
| 发布版本 / 开分支 / 合并推送 | duo-release-workflow |

## 显式命令（模型不能自动触发，由你调用）

| 命令 | 何时用 |
|---|---|
| `/grill-me` | L1 需求动手前打磨方案 |
| `/grill-with-docs` | 同上，且要沉淀 ADR / 术语表 |
| `/to-spec` | grill 结论成型后落 spec |
| `/to-tickets` | spec 拆工单 |
| `/implement` | 逐工单实现 |
| `/handoff` | 会话收尾，交接给下一会话 |

## 红线

1. 未经用户确认：不 commit、不合并到 main、不推送
2. API key / 密钥永不入库，`.env` 必须在 `.gitignore`
3. 文档与代码同一 diff 内同步
4. 引入新依赖先征得同意
5. UI 改动必须视觉验证后才算完成
6. CHANGELOG.md 是版本唯一锚点，用户可见变更同 diff 记账
7. 开发提交不落 main，main 只收验收后的版本合并
