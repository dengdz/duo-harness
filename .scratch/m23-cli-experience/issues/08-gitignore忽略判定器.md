# 08: .gitignore 忽略判定器

## What to build

glob/grep/@file 补全三个消费点共用同一套自研忽略判定：逐级堆叠解析各目录 `.gitignore` 与 `.git/info/exclude`；语法全常用子集（`!` 反选、`**`、`*`、`?`、`[]` 字符类、尾 `/` 目录限定、前导 `/` 锚定、`\` 转义），坏行静默跳过（git 同语义）；判定取 `.gitignore` 规则 ∪ 硬编码产物目录（node_modules/target 等）∪ VCS 元数据目录并集。端到端行为：仓库里 .gitignore 忽略的目标在 glob/grep 结果与 @file 补全里同时消失，补全看得到的 grep 一定看得到——口径分裂消灭。

决策依据：ADR-0025 决策三（自研不捆绑 rg，红线 4）；limitations M12#2（引擎替换点）。判定器组件放工具域 fs 包，agent 模块的 @file 索引经依赖引用；三消费点各自写死的排除逻辑全部替换。全局 core.excludesFile 不做；性能护栏沿用既有条目上限。

## Blocked by

无（可与主线并行）

## Status
done

## Comments
- 2026-09-22：实现与双轴审查完成，全量 BUILD SUCCESS（tools 209 含判定器矩阵 12 用例）。判定器纯函数形态（规则按目录惰性解析缓存），三消费点接线：glob/grep 换 ignore.ignored 过滤（Files.walk 不剪枝形态与现状持平）、@file 索引剪枝式逐 entry 判定（FileReferenceService 构造自建同根实例）。
- 2026-09-22：双轴修复 12 项——CHANGELOG 补记账、limitations M12#2 销账、git 语义三处（字符类 && 交集转义 / 转义尾空格保留 / 未闭合 [ 收缩宣称记档）、死测试方法（excludedDirsAreSkipped 丢 @Test 被静默停用——插入用例时吞掉注解）、「共用同一实例」注释失真改「同口径」、未用 import、套件叙述计数、类 javadoc 同步、取反字符类与未锚定目录限定补用例。
- 2026-09-22：验收通过（四步演示：headless 步骤 1-3 代跑全过 + Web @ 补全用户确认），转 done。验收过程顺带修正验收命令缺陷：headless 验收需 cwd 在样例仓库而 -pl 需在项目根——用 `-f <仓库pom>` 解决（workspace = 启动目录，两全）。
- 2026-09-22：记档——性能非回归但存在优化点（glob/grep walk 不剪枝 + 前缀判定 O(深度²)，条目上限护栏兜底；后续可换 walkFileTree 剪枝）；判定按会话缓存不随 .gitignore 带外编辑失效（limitations 已记）；术语表「忽略判定」词条的剪枝/不回溯裁定随工单 11 收口复核补记。

## Checklist
- [ ] 判定器组件：逐级堆叠 + exclude + 全常用子集 + 坏行跳过（纯函数，语法矩阵单测）
- [ ] 三源并集（.gitignore ∪ 产物目录 ∪ VCS 目录）与三个消费点接线，删除各自写死的排除逻辑
- [ ] negate/目录限定/锚定的边界用例（含 `!` 反选、`**` 跨层、目录 symlink 不下钻）
- [ ] 测试（先例 FileReferenceIndexTest / FsToolsTest seam）：判定器矩阵 + glob/grep/@file 各一条集成断言（同口径）
- [x] 工单级验收件：样例仓库验证「补全与 grep 同口径」演示，用户手动确认（2026-09-22 通过：步骤 1-3 headless 代跑——glob/grep 同口径消失、硬源并集、反选救回与不可救；步骤 4 Web @ 补全用户肉眼确认）
- [ ] CHANGELOG 未发布段记账

## 审查轮（2026-09-22，双轴→修复→OCR→修复，两轮齐全收口）

### 第 1 轮·双轴

**覆盖**：10 文件全审（覆盖率 100% 全集口径：6 主代码 + 3 测试 + 工具目录文档）

### 发现（已修/记档）

- **P1**：CHANGELOG 缺工单 08 记账（红线 6）——已补。
- **P2**：limitations M12#2 过期未销账——已按 #3 行先例销账（残留姿态差异一并记档）。
- **P2**：git 语义三处偏差——字符类 `&&` 触发 Java 交集语义（已修：转义 `&&`）；转义尾空格被 stripTrailing 吃掉（已修：剥未转义尾空白）；未闭合 `[` 按坏行 vs git 字面（收缩 javadoc 宣称 + limitations 记档）。
- **测试缺陷**：excludedDirsAreSkipped 丢 @Test 被静默停用（插入用例吞注解）——已恢复。
- **P3 已修**：「共用同一实例」注释失真改「同口径」、未用 import Set、套件叙述计数、类 javadoc「VCS 目录跳过」同步。
- **P3 记档**：walk 不剪枝 + O(深度²) 前缀判定（非回归，条目上限护栏兜底）；Windows 分隔符；显式单文件搜索不经忽略判定（与 rg/git 显式路径约定一致）。

### 第 2 轮·OCR（委托模式）

**覆盖**：preview 名单 6 主代码文件 = 已审 6 + 跳过 0（覆盖率 100% 全集口径：测试/文档由双轴轮与修复轮覆盖）；规则组 1 组（typos/dead code/命名/注释纪律）。

### 发现（已修）

- **字符类 body 反斜杠翻倍破坏 `\]` 转义**（已修，medium）：`\` 翻倍与 `]` 转义冲突——`&&` 与 `\` 两场景不重叠，只转义 `&&` 保留 `\` 原样（Java 字符类内 `\]` 同语义）。
- 其余五文件行级过目：无 dead code、无拼写、无调试残留；判定顺序（isDir/symlink 先于 ignored）、缓存键 normalize 一致。

### 复核

- 修复后 IgnorePolicyTest 12/12 绿；全仓 test BUILD SUCCESS（tools 210）。
- **两轮齐全核对（SKILL.md 收口硬判据）**：第 1 轮·双轴 ✓ + 第 2 轮·OCR ✓——本次审查收口。
