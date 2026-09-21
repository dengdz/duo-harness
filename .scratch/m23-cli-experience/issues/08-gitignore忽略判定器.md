# 08: .gitignore 忽略判定器

## What to build

glob/grep/@file 补全三个消费点共用同一套自研忽略判定：逐级堆叠解析各目录 `.gitignore` 与 `.git/info/exclude`；语法全常用子集（`!` 反选、`**`、`*`、`?`、`[]` 字符类、尾 `/` 目录限定、前导 `/` 锚定、`\` 转义），坏行静默跳过（git 同语义）；判定取 `.gitignore` 规则 ∪ 硬编码产物目录（node_modules/target 等）∪ VCS 元数据目录并集。端到端行为：仓库里 .gitignore 忽略的目标在 glob/grep 结果与 @file 补全里同时消失，补全看得到的 grep 一定看得到——口径分裂消灭。

决策依据：ADR-0025 决策三（自研不捆绑 rg，红线 4）；limitations M12#2（引擎替换点）。判定器组件放工具域 fs 包，agent 模块的 @file 索引经依赖引用；三消费点各自写死的排除逻辑全部替换。全局 core.excludesFile 不做；性能护栏沿用既有条目上限。

## Blocked by

无（可与主线并行）

## Status
ready-for-agent

## Checklist
- [ ] 判定器组件：逐级堆叠 + exclude + 全常用子集 + 坏行跳过（纯函数，语法矩阵单测）
- [ ] 三源并集（.gitignore ∪ 产物目录 ∪ VCS 目录）与三个消费点接线，删除各自写死的排除逻辑
- [ ] negate/目录限定/锚定的边界用例（含 `!` 反选、`**` 跨层、目录 symlink 不下钻）
- [ ] 测试（先例 FileReferenceIndexTest / FsToolsTest seam）：判定器矩阵 + glob/grep/@file 各一条集成断言（同口径）
- [ ] 工单级验收件：样例仓库验证「补全与 grep 同口径」演示，用户手动确认
- [ ] CHANGELOG 未发布段记账
