# Issue tracker：本地 Markdown

本仓库的 spec（即 PRD）与实现工单以 markdown 文件形式存放在 `.scratch/`，随 git 入库（工单是持久流程工件，也是 code-review 的 spec 探测来源）。

## 约定

- 一个 feature 一个目录：`.scratch/<feature-slug>/`
- spec 位置：`.scratch/<feature-slug>/spec.md`（模板：Problem / Solution / User Stories / Implementation Decisions / Testing Decisions / Out of Scope / Further Notes）
- 实现工单一文件一单：`.scratch/<feature-slug>/issues/<NN>-<slug>.md`，从 `01` 起编号——**禁止**把多张工单合写进一个文件
- 工单模板：

  ```markdown
  # <标题>

  ## What to build
  <目标与验收标准>

  ## Blocked by
  <NN, NN；无阻塞则省略本节>

  ## Status
  ready-for-agent

  ## Checklist
  - [ ] <可独立验收的步骤>
  ```

- 状态记录在文件顶部附近的 `Status:` 行：`ready-for-agent`（待实现）/ `in-progress` / `done`
- 评论与过程记录追加到文件末尾 `## Comments` 小节之下

## 技能说"发布到 issue tracker"时

在 `.scratch/<feature-slug>/` 下新建文件（目录不存在则创建）。

## 技能说"取相关工单"时

读取引用路径处的文件；用户通常会直接给路径或工单号。

## Wayfinding operations

供 `/wayfinder` 使用（当前未启用，保留接口约定以便将来升级）。**map** 是一个文件，每个子工单一个子文件：

- **Map**：`.scratch/<effort>/map.md` —— Notes / Decisions-so-far / Fog 主体
- **子工单**：`.scratch/<effort>/issues/NN-<slug>.md`，从 `01` 起编号，问题写在正文；`Type:` 行记录类型（`research`/`prototype`/`grilling`/`task`）；`Status:` 行记录 `claimed`/`resolved`
- **Blocking**：文件顶部附近的 `Blocked by: NN, NN` 行；所列文件全部 `resolved` 才解除阻塞
- **Frontier**：扫描 `.scratch/<effort>/issues/` 中 open、无阻塞、未认领的文件，编号最小者优先
- **Claim**：动手前先置 `Status: claimed` 并保存
- **Resolve**：在 `## Answer` 小节下追加答案，置 `Status: resolved`，并在 `map.md` 的 Decisions-so-far 追加上下文指针（摘要 + 链接）
