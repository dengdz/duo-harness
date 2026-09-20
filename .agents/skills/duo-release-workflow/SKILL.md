---
name: duo-release-workflow
description: |
  duo-harness 的版本发布流程：创建版本分支、开发、审查、验收、合并回 main、
  推送的完整生命周期。用户说"发布版本"、"开个分支"、"创建分支"、
  "合并推送"、"准备发布"时触发。需求入口分流与提交核对由 duo-workflow 负责，
  版本号规则见 duo-workflow 的 references/版本号.md。
---

# duo-harness 版本发布流程

**引导态守卫**：本流程假设仓库已初始化（存在 main 分支与带版本号的构建文件）。仓库或构建体系尚未建立时，不硬套流程——先与用户确认初始化方案（git 仓库、构建工具、初始版本号），从当前实际状态切入。

## 版本号规则

语义化版本三位编号的递增时机、当前版本锚点、分支模型，统一见 [版本号规范](../duo-workflow/references/版本号.md)。

## 完整流程

### 第一步：需求分级

| 类型 | 判定标准 | 版本号变化 | 前置动作 |
|---|---|---|---|
| **大需求** | 新模块、架构变更、影响公开 API | minor+1（0.7.0→0.8.0） | 设计访谈（grilling）→ 决策记录 |
| **功能版本** | 在现有模块上增加能力 | minor+1 | 确认范围即可 |
| **bug 修复** | 修 bug、小优化、不改接口 | patch+1（0.7.0→0.7.1） | 无 |

### 第二步：创建分支

```bash
# 确定版本号后，从 main 创建分支
git checkout main
git checkout -b {版本号}    # 如 0.7.0
```

**分支命名 = 版本号**（如 `0.7.0`、`0.7.1`）。分支模型（main 只收验收合并、引导期以版本号为初始分支、独立提交请求先归位分支）见 [版本号规范](../duo-workflow/references/版本号.md)。

### 第三步：开发

在版本分支上进行（实现方式按 AGENTS.md 三级分流）：

1. **代码实现**——L1 大需求按工单逐单推进（`/implement`，会话间 `/handoff` 交接）；核心逻辑默认 tdd 红绿循环，先与用户确认测试 seam
2. **单元测试**（新功能必须有测试覆盖）
3. **验证 Demo**（在示例模块写 XxxDemo，自判定 OK/FAIL）
4. **文档同步**（同一 diff 内完成，按 [duo-doc-standards](../duo-doc-standards/SKILL.md) 归位）：
   - 新功能 → docs/ 对应章节指南
   - 接口变更 → 参考篇更新
   - CHANGELOG.md：分支创建时建版本段（条目记账按 [duo-workflow](../duo-workflow/SKILL.md) 的提交前核对清单执行）
   - README.md 特性列表（如有新特性）

### 第四步：代码审查

| 审查方式 | 适用场景 | 工具 |
|---|---|---|
| OCR 审查 | 每次 diff | `ocr review --audience agent` |
| 双轴审查 | 大需求 / 多工单改动 | code-review（Standards + Spec 两轴，比较基点取分支起点） |
| 通用 Java 规约 | Java 代码 | 并入 OCR 行级意见（执行与分批策略见 [duo-code-review](../duo-code-review/SKILL.md)） |
| 专项审查 | 并发/安全/性能 | 按需针对性推演 |

审查发现的问题**全部修复并验证后**才进入下一步。审查标准见 [duo-code-review](../duo-code-review/SKILL.md)。

### 第五步：用户验收

- 用户运行验证 Demo
- 涉及 UI 的改动：先用 browser-use 截图与改前留档对比做视觉验证，再交用户在页面上手动验收
- **用户确认通过后才可合并**

### 第六步：推送前检查

```bash
# 1. 密钥安全扫描
# 检查所有待提交文件中是否有：
#   - API key（sk-、Bearer、x-api-key 后跟真实密钥）
#   - 内部 URL、.env 文件内容
# 确认 .env 在 .gitignore 中

# 2. 版本号确认
# 构建文件的版本声明与分支名一致

# 3. 文档完整性
# CHANGELOG 已更新；文档结构符合 duo-doc-standards
# 动了 docs/ 时：config.mts 的 nav/sidebar 与 docs/ 文件双向对账
# （新 ADR/篇章必须有 sidebar 条目——导航不可见是最易漏的发布缺陷）
```

更完整的证据选择规则见 [duo-pre-push-checks](../duo-pre-push-checks/SKILL.md)。

### 第七步：合并与推送

```bash
# 1. [可选] 创建备份分支（大规模操作前）
git branch backup-{描述}

# 2. 切换到 main
git checkout main

# 3. 合并版本分支
git merge {版本号}
# 如有版本号冲突：保留当前版本号

# 4. 推送
git push origin main

# 5. [可选] 推送版本分支做记录
git push origin {版本号}
```

### 第八步：发布确认

- 推送后验证远端引用与本地一致
- 文档站（GitHub Pages）：push main 后 GitHub Actions 自动构建部署（`.github/workflows/docs.yml`），在仓库 Actions 页确认 `docs-site` 工作流成功、站点内容已更新；失败时查该工作流日志

## 红线（必须遵守）

红线的唯一权威是根 AGENTS.md（密钥不入库、合并/推送须用户确认、文档同 diff 同步、新依赖先征得同意、CHANGELOG 版本锚点）——本流程不抄录副本，与红线相抵触即停；密钥扫描的具体动作在第六步。发布流程特有的守卫：

1. **推送后验证远端引用与本地 HEAD 一致**
2. **改写历史必须走精确租约强推**（`--force-with-lease`，见 [duo-pre-push-checks](../duo-pre-push-checks/SKILL.md)；裸 `--force` 永远不允许）
